/*
 * Copyright 2024-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.lsp.spec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import groovy.json.JsonSlurper;
import nextflow.lsp.util.Logger;
import org.codehaus.groovy.ast.MethodNode;

import static java.net.http.HttpResponse.BodyHandlers;

/**
 * Cache plugin specs that are fetched from the plugin registry.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class PluginSpecCache {

    private static Logger log = Logger.getInstance();

    private URI registryUri;

    private HttpClient client = HttpClient.newBuilder().build();

    private Map<PluginRef, PluginSpec> cache = new HashMap<>();

    private List<PluginRef> currentVersions;

    public PluginSpecCache(String registryUrl) {
        this.registryUri = URI.create(registryUrl);
    }

    /**
     * Get the plugin spec for a given plugin release.
     *
     * If the version is not specified, the latest version is used instead.
     *
     * Results are cached to minimize registry API calls.
     *
     * @param name
     * @param version
     */
    public PluginSpec get(String name, String version) {
        var ref = new PluginRef(name, version);
        if( !cache.containsKey(ref) )
            cache.put(ref, compute(name, version));
        return cache.get(ref);
    }

    private PluginSpec compute(String name, String version) {
        try {
            return compute0(name, version);
        }
        catch( Exception e ) {
            log.error(e.toString());
            return null;
        }
    }

    private PluginSpec compute0(String name, String version) {
        // fetch plugin spec from registry
        var response = fetch(name, version);
        if( response == null )
            return null;

        // select plugin release (or latest if not specified)
        var release = pluginRelease(response);
        if( release == null )
            return null;

        // get spec from plugin release
        return pluginSpec(release);
    }

    private Map fetch(String name, String version) {
        var path = version != null
            ? String.format("v1/plugins/%s/%s", name, version)
            : String.format("v1/plugins/%s", name);
        var uri = registryUri.resolve(path);

        log.debug("fetch plugin " + uri);

        try {
            var request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("Accept", "application/json")
                .build();
            var httpResponse = client.send(request, BodyHandlers.ofString());
            var response = new JsonSlurper().parseText(httpResponse.body());
            return response instanceof Map m ? m : null;
        }
        catch( IOException | InterruptedException e ) {
            log.error(e.toString());
            return null;
        }
    }

    private static Map pluginRelease(Map response) {
        if( response.containsKey("plugin") ) {
            var plugin = (Map) response.get("plugin");
            var releases = (List<Map>) plugin.get("releases");
            return releases.get(0);
        }
        if( response.containsKey("pluginRelease") ) {
            return (Map) response.get("pluginRelease");
        }
        return null;
    }

    private static PluginSpec pluginSpec(Map release) {
        var specJson = (String) release.get("spec");
        var spec = (Map) new JsonSlurper().parseText(specJson);
        var definitions = (List<Map>) spec.get("definitions");
        return new PluginSpec(
            ConfigSpecFactory.fromDefinitions(definitions),
            ScriptSpecFactory.fromDefinitions(definitions, "Factory"),
            ScriptSpecFactory.fromDefinitions(definitions, "Function"),
            ScriptSpecFactory.fromDefinitions(definitions, "Operator")
        );
    }

    /**
     * Set the plugin versions currently specified by the config.
     *
     * @param currentVersions
     */
    public void setCurrentVersions(List<PluginRef> currentVersions) {
        this.currentVersions = currentVersions;
    }

    /**
     * Get the currently loaded spec for a plugin.
     * 
     * @param name
     */
    public PluginSpec getCurrent(String name) {
        if( currentVersions == null )
            return null;
        var ref = currentVersions.stream()
            .filter(r -> r.name().equals(name))
            .findFirst().orElse(null);
        if( ref == null )
            return null;
        return cache.get(ref);
    }

}

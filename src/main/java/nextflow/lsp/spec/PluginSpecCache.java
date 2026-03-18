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
import java.nio.file.Path;
import java.util.Collections;
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

    private Map<PluginRef, PluginSpec> cache = new HashMap<>();

    private Map<Path, List<PluginRef>> pluginsMap = new HashMap<>();

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
            updateCache(ref);
        return cache.get(ref);
    }

    private void updateCache(PluginRef ref) {
        try {
            // fetch plugin spec from registry
            var response = fetch(ref.name(), ref.version());
            if( response == null )
                return;

            // select plugin release (or latest if not specified)
            var release = pluginRelease(response);
            if( release == null )
                return;

            // save plugin spec to cache
            cache.put(ref, pluginSpec(release));
        }
        catch( IOException | InterruptedException e ) {
            e.printStackTrace(System.err);
        }
    }

    private Map fetch(String name, String version) throws IOException, InterruptedException {
        var path = version != null
            ? String.format("v1/plugins/%s/%s", name, version)
            : String.format("v1/plugins/%s", name);
        var uri = registryUri.resolve(path);

        log.debug("fetch plugin " + uri);

        var client = HttpClient.newBuilder().build();
        var request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .header("Accept", "application/json")
            .build();
        var httpResponse = client.send(request, BodyHandlers.ofString());
        var response = new JsonSlurper().parseText(httpResponse.body());
        return response instanceof Map m ? m : null;
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
        var definitions = pluginDefinitions(release);
        return new PluginSpec(
            ConfigSpecFactory.fromDefinitions(definitions),
            ScriptSpecFactory.fromDefinitions(definitions, "Factory"),
            ScriptSpecFactory.fromDefinitions(definitions, "Function"),
            ScriptSpecFactory.fromDefinitions(definitions, "Operator")
        );
    }

    private static List<Map> pluginDefinitions(Map release) {
        var specJson = (String) release.get("spec");
        if( specJson == null )
            return Collections.emptyList();
        var spec = (Map) new JsonSlurper().parseText(specJson);
        return (List<Map>) spec.get("definitions");
    }

    /**
     * Set the plugin versions currently specified for a given config file.
     *
     * Only "main" config files, i.e. files named `nextflow.config`, are recorded.
     *
     * @param uri
     * @param refs
     */
    public void setCurrentVersions(URI uri, List<PluginRef> refs) {
        if( uri.getPath() == null || !uri.getPath().endsWith("nextflow.config") )
            return;
        var parent = Path.of(uri).getParent();
        this.pluginsMap.put(parent, refs);
    }

    /**
     * Get the currently loaded spec for a plugin.
     *
     * Given the URI of the including file, "./nextflow.config" is
     * used if present, otherwise "../nextflow.config" is used if
     * present, and so on.
     *
     * @param uri
     * @param name
     */
    public PluginSpec getCurrent(URI uri, String name) {
        var parent = Path.of(uri).getParent();
        var key = pluginsMap.keySet().stream()
            .filter(p -> parent.startsWith(p))
            .sorted((a, b) -> b.compareTo(a))
            .findFirst().orElse(null);
        if( key == null )
            return null;
        var ref = pluginsMap.get(key).stream()
            .filter(r -> r.name().equals(name))
            .findFirst().orElse(null);
        if( ref == null )
            return null;
        return cache.get(ref);
    }

}

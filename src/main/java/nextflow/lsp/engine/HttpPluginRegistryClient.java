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
package nextflow.lsp.engine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import groovy.json.JsonSlurper;
import nextflow.lsp.spec.ConfigSpecFactory;
import nextflow.lsp.spec.PluginSpec;
import nextflow.lsp.spec.ScriptSpecFactory;
import nextflow.lsp.util.Logger;

import static java.net.http.HttpResponse.BodyHandlers;

/**
 * Fetch plugin specs from the Nextflow plugin registry over HTTP.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class HttpPluginRegistryClient implements PluginRegistryClient {

    private static Logger log = Logger.getInstance();

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI registryUri;

    public HttpPluginRegistryClient(String registryUrl) {
        this.registryUri = URI.create(registryUrl);
    }

    @Override
    public Result fetch(String name, String version) throws InterruptedException {
        try {
            var path = version != null
                ? String.format("v1/plugins/%s/%s", name, version)
                : String.format("v1/plugins/%s", name);
            var uri = registryUri.resolve(path);

            log.debug("fetch plugin " + uri);

            var client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
            var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .build();
            var httpResponse = client.send(request, BodyHandlers.ofString());
            var status = httpResponse.statusCode();
            if( status == 404 )
                return new NotFound();
            if( status != 200 )
                return new Failed("HTTP " + status);
            if( !(new JsonSlurper().parseText(httpResponse.body()) instanceof Map response) )
                return new Failed("unexpected response from registry");

            // select plugin release (or latest if not specified)
            var release = pluginRelease(response);
            return release != null
                ? new Found(pluginSpec(release))
                : new NotFound();
        }
        catch( IOException | RuntimeException e ) {
            return new Failed(e.toString());
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

}

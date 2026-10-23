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
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;
import nextflow.lsp.TestPluginRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class PluginSpecCacheTest {

    static PluginSpecCache pluginSpecCache() {
        return new PluginSpecCache(TestPluginRegistry.url());
    }

    static PluginSpecCache pluginSpecCache(Map<PluginRef,PluginSpec> entries) throws ReflectiveOperationException {
        var result = pluginSpecCache();
        var field = PluginSpecCache.class.getDeclaredField("cache");
        field.setAccessible(true);
        field.set(result, new HashMap<>(entries));
        return result;
    }

    static URI uri(String path) {
        return URI.create("file://" + path);
    }

    @Test
    void fetchPluginSpec() {
        var psc = new PluginSpecCache(TestPluginRegistry.url());

        var spec = psc.get("nf-prov", "1.6.0");
        assertThat(spec.configScopes()).containsKey("prov");
    }

    @Test
    void cachePluginsThatAreNotFoundInRegistry() throws IOException {
        var requests = new ConcurrentHashMap<String,Integer>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (exchange) -> {
            var path = exchange.getRequestURI().getPath();
            requests.merge(path, 1, Integer::sum);
            var status = path.endsWith("nf-missing") ? 404 : 503;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            var psc = new PluginSpecCache("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");

            psc.get("nf-missing", null);
            psc.get("nf-missing", null);
            psc.get("nf-unavailable", null);
            psc.get("nf-unavailable", null);
            assertThat(psc.get("nf-missing", null)).isNull();
            assertThat(requests).containsEntry("/api/v1/plugins/nf-missing", 1);
            assertThat(requests).containsEntry("/api/v1/plugins/nf-unavailable", 2);
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void onlyTrackPluginVersionsForMainConfigFiles() {
        var psc = pluginSpecCache();
        var refs = List.of(new PluginRef("nf-hello", "0.9.0"));

        psc.setCurrentVersions(uri("/project/other.config"), refs);
        assertThat(psc.getCurrent(uri("/project/main.nf"), "nf-hello")).isNull();
    }

    @Test
    void returnCurrentPluginVersionsForScriptFile() throws ReflectiveOperationException {
        var ref1 = new PluginRef("nf-hello", "0.9.0");
        var ref2 = new PluginRef("nf-hello", "1.0.0");
        // equal records, so compare by identity
        var spec1 = new PluginSpec(Map.of(), List.of(), List.of(), List.of());
        var spec2 = new PluginSpec(Map.of(), List.of(), List.of(), List.of());
        var psc = pluginSpecCache(Map.of(ref1, spec1, ref2, spec2));

        // return nothing when no config files are registered
        assertThat(psc.getCurrent(uri("/project/main.nf"), "nf-hello")).isNull();

        // return nothing when script has no parent config file
        psc.setCurrentVersions(uri("/project/nextflow.config"), List.of(new PluginRef("nf-hello", "0.9.0")));
        assertThat(psc.getCurrent(uri("/other-project/main.nf"), "nf-hello")).isNull();

        // return nothing when requested plugin is not enabled
        psc.setCurrentVersions(uri("/project/nextflow.config"), List.of(new PluginRef("nf-other", "1.0.0")));
        assertThat(psc.getCurrent(uri("/project/main.nf"), "nf-hello")).isNull();

        // return plugin spec from nearest parent config
        psc.setCurrentVersions(uri("/project/nextflow.config"), List.of(ref1));
        assertThat(psc.getCurrent(uri("/project/main.nf"), "nf-hello")).isSameAs(spec1);
        assertThat(psc.getCurrent(uri("/project/subdir/module.nf"), "nf-hello")).isSameAs(spec1);

        psc.setCurrentVersions(uri("/project/nextflow.config"), List.of(ref1));
        psc.setCurrentVersions(uri("/project/subdir/nextflow.config"), List.of(ref2));
        assertThat(psc.getCurrent(uri("/project/main.nf"), "nf-hello")).isSameAs(spec1);
        assertThat(psc.getCurrent(uri("/project/subdir/module.nf"), "nf-hello")).isSameAs(spec2);
    }

}

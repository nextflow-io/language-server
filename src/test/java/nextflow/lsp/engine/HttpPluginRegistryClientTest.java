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
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import nextflow.lsp.TestPluginRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies fetching plugin specs from a plugin registry over HTTP.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Isolated // real threads, so other tests would slow it down
@Timeout(60) // hang guard
class HttpPluginRegistryClientTest {

    @Test
    void reportPublishedPluginAsFoundWithItsSpec() throws InterruptedException {
        var client = new HttpPluginRegistryClient(TestPluginRegistry.url());

        var result = client.fetch("nf-prov", "1.6.0");
        assertThat(result).isInstanceOfSatisfying(PluginRegistryClient.Found.class, (found) -> {
            assertThat(found.spec().configScopes()).containsKey("prov");
        });
    }

    @Test
    void reportMissingPluginAsNotFound() throws InterruptedException {
        var client = new HttpPluginRegistryClient(TestPluginRegistry.url());

        assertThat(client.fetch("nf-missing", "1.0.0")).isInstanceOf(PluginRegistryClient.NotFound.class);
    }

    @Test
    void reportUnexpectedStatusAsFailure() throws IOException, InterruptedException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (exchange) -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new HttpPluginRegistryClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
            assertThat(client.fetch("nf-prov", null)).isEqualTo(new PluginRegistryClient.Failed("HTTP 503"));
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void reportInvalidRegistryUrlAsFailure() throws InterruptedException {
        var client = new HttpPluginRegistryClient("http://bad url/");
        assertThat(client.fetch("nf-prov", "1.6.0")).isInstanceOf(PluginRegistryClient.Failed.class);
    }

}

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

package nextflow.lsp.services.config;

import java.util.List;
import java.util.Map;

import nextflow.config.spec.SpecNode;
import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.engine.FixtureRegistry;
import nextflow.lsp.engine.PluginRegistryClient;
import nextflow.lsp.spec.PluginSpec;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ConfigHoverTest {

    static String getHoverHint(TestServer server, String uri, Position position) {
        var hover = server.call(s -> s.hover(new HoverParams(new TextDocumentIdentifier(uri), position)));
        return hover != null
            ? hover.getContents().getRight().getValue()
            : null;
    }

    @Test
    void getHoverHintForConfigScope() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", """
            executor {
            }
            """);
        var value = getHoverHint(server, uri, new Position(1, 0));

        assertThat(value).isEqualTo("The `executor` scope controls various executor behaviors.\n");
    }

    @Test
    void getHoverHintForConfigOptionWithMultipleTypes() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", """
            process {
                arch = 'linux/x86_64'
            }
            """);
        var value = getHoverHint(server, uri, new Position(1, 4));

        assertThat(value).startsWith("`process.arch (Map | String)`");
    }

    @Test
    void getHoverHintForPluginConfigScope() {
        var uri = getUri("nextflow.config");
        var provSpec = new PluginSpec(
            Map.of(
                "prov", new SpecNode.Scope("The `prov` scope allows you to configure the `nf-prov` plugin.", Map.of())
            ),
            List.of(), List.of(), List.of()
        );
        var registry = new FixtureRegistry();
        var server = TestServer.start((o) -> o.registry = (name, version) ->
            "nf-prov".equals(name) && "1.6.0".equals(version)
                ? new PluginRegistryClient.Found(provSpec)
                : registry.fetch(name, version));

        server.open("nextflow.config", """
            plugins {
                id 'nf-prov@1.6.0'
            }

            prov {
            }
            """);
        // the plugin spec is fetched in the background
        server.settle();
        var value = getHoverHint(server, uri, new Position(4, 0));

        assertThat(value).isEqualTo("The `prov` scope allows you to configure the `nf-prov` plugin.\n");
    }

}

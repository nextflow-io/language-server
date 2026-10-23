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
import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestPluginRegistry;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.lsp.spec.PluginSpec;
import nextflow.lsp.spec.PluginSpecCache;
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

    static String getHoverHint(ConfigService service, String uri, Position position) {
        var hover = service.hover(new HoverParams(new TextDocumentIdentifier(uri), position));
        return hover != null
            ? hover.getContents().getRight().getValue()
            : null;
    }

    @Test
    void getHoverHintForConfigScope() {
        var service = getConfigService();
        var uri = getUri("nextflow.config");

        open(service, uri, """
            executor {
            }
            """);
        service.updateNow();
        var value = getHoverHint(service, uri, new Position(1, 0));

        assertThat(value).isEqualTo("The `executor` scope controls various executor behaviors.\n");
    }

    @Test
    void getHoverHintForConfigOptionWithMultipleTypes() {
        var service = getConfigService();
        var uri = getUri("nextflow.config");

        open(service, uri, """
            process {
                arch = 'linux/x86_64'
            }
            """);
        service.updateNow();
        var value = getHoverHint(service, uri, new Position(1, 4));

        assertThat(value).startsWith("`process.arch (Map | String)`");
    }

    @Test
    void getHoverHintForPluginConfigScope() {
        var uri = getUri("nextflow.config");
        var service = new ConfigService(workspaceRootUri());
        var configuration = LanguageServerConfiguration.defaults();
        var provSpec = new PluginSpec(
            Map.of(
                "prov", new SpecNode.Scope("The `prov` scope allows you to configure the `nf-prov` plugin.", Map.of())
            ),
            List.of(), List.of(), List.of()
        );
        var pluginSpecCache = new PluginSpecCache(TestPluginRegistry.url()) {
            @Override
            public PluginSpec get(String name, String version) {
                return "nf-prov".equals(name) && "1.6.0".equals(version)
                    ? provSpec
                    : super.get(name, version);
            }
        };
        service.connect(new TestLanguageClient());
        service.initialize(configuration, pluginSpecCache);

        open(service, uri, """
            plugins {
                id 'nf-prov@1.6.0'
            }

            prov {
            }
            """);
        service.updateNow();
        var value = getHoverHint(service, uri, new Position(4, 0));

        assertThat(value).isEqualTo("The `prov` scope allows you to configure the `nf-prov` plugin.\n");
    }

}

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

package nextflow.lsp.services.config

import nextflow.config.spec.SpecNode
import nextflow.lsp.TestLanguageClient
import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.lsp.spec.PluginSpec
import nextflow.lsp.spec.PluginSpecCache
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigHoverTest extends Specification {

    String getHoverHint(ConfigService service, String uri, Position position) {
        def hover = service.hover(new HoverParams(new TextDocumentIdentifier(uri), position))
        return hover
            ? hover.getContents().getRight().getValue()
            : null
    }

    def 'should get hover hint for a config scope' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, '''\
            executor {
            }
            ''')
        service.updateNow()
        def value = getHoverHint(service, uri, new Position(1, 0))
        then:
        value == 'The `executor` scope controls various executor behaviors.\n'
    }

    def 'should get hover hint for a plugin config scope' () {
        given:
        def uri = getUri('nextflow.config')
        def service = new ConfigService(workspaceRoot().toUri().toString())
        def configuration = LanguageServerConfiguration.defaults()
        def pluginSpecCache = Spy(new PluginSpecCache(configuration.pluginRegistryUrl()))
        pluginSpecCache.get('nf-prov', '1.6.0') >> new PluginSpec(
            [
                'prov': new SpecNode.Scope('The `prov` scope allows you to configure the `nf-prov` plugin.', [:])
            ],
            [], [], []
        )
        service.connect(new TestLanguageClient())
        service.initialize(configuration, pluginSpecCache)

        when:
        open(service, uri, '''\
            plugins {
                id 'nf-prov@1.6.0'
            }

            prov {
            }
            ''')
        service.updateNow()
        def value = getHoverHint(service, uri, new Position(4, 0))
        then:
        value == 'The `prov` scope allows you to configure the `nf-prov` plugin.\n'
    }

}

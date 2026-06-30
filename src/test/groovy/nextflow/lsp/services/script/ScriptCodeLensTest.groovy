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

package nextflow.lsp.services.script

import nextflow.lsp.services.LanguageServerConfiguration
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptCodeLensTest extends Specification {

    def 'should provide a Preview DAG code lens for each workflow' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            ''')
        service.updateNow()
        def lenses = service.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri)))
        then: 'one lens per workflow (named + entry)'
        lenses.size() == 2
        lenses.every { it.getCommand().getTitle() == 'Preview DAG' }
        lenses.every { it.getCommand().getCommand() == 'nextflow.previewDag' }
    }

    def 'should preview the workspace via executeCommand' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when: 'open without a manual update; executeCommand drives the compile'
        open(service, uri, '''\
            process FOO {
                script:
                "echo foo"
            }

            workflow {
                FOO()
            }
            ''')
        def result = service.executeCommand('nextflow.server.previewWorkspace', [], LanguageServerConfiguration.defaults())
        def definitions = result['result']
        then:
        definitions.find { it['type'] == 'process' && it['name'] == 'FOO' } != null
        def entry = definitions.find { it['type'] == 'workflow' && it['name'] == '<entry>' }
        entry != null
        entry['children'].find { it['name'] == 'FOO' } != null
    }

    def 'should return null for an unknown command' () {
        given:
        def service = getScriptService()

        expect:
        service.executeCommand('nextflow.server.bogus', [], LanguageServerConfiguration.defaults()) == null
    }

}

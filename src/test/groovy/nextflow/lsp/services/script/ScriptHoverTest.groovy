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

import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptHoverTest extends Specification {

    String getHoverHint(ScriptService service, String uri, Position position) {
        def hover = service.hover(new HoverParams(new TextDocumentIdentifier(uri), position))
        return hover
            ? hover.getContents().getRight().getValue()
            : null
    }

    def 'should show the signature and documentation when hovering over a function call' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            /**
             * Greet someone.
             */
            def greet(name) {
                return "Hello, ${name}!"
            }

            workflow {
                greet('World')
            }
            ''')
        service.updateNow()
        def value = getHoverHint(service, uri, new Position(8, 5))
        then:
        value == '```nextflow\ndef greet(name)\n```\n\n---\n\nGreet someone.'
    }

    def 'should show the label when hovering over a workflow invocation' () {
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
        def value = getHoverHint(service, uri, new Position(4, 4))
        then:
        value.startsWith('```nextflow\nworkflow HELLO {')
    }

    def 'should expand a named record type when hovering over a process' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            nextflow.enable.types = true

            record SamtoolsIndexInput {
                id: String
                input: Path
            }

            process SAMTOOLS_INDEX {
                input:
                data: SamtoolsIndexInput

                script:
                'samtools index'
            }

            workflow {
                SAMTOOLS_INDEX( channel.empty() )
            }
            ''')
        service.updateNow()
        def value = getHoverHint(service, uri, new Position(16, 4))
        then:
        value == '''\
            ```nextflow
            process SAMTOOLS_INDEX {
              input:
              data: SamtoolsIndexInput {
                id: String
                input: Path
              }

              output:
              <none>
            }
            ```'''.stripIndent(true)
    }

    def 'should return null when hovering over whitespace' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println('Hello!')
            }
            ''')
        service.updateNow()
        then:
        getHoverHint(service, uri, new Position(3, 0)) == null
    }

}

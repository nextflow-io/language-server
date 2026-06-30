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

import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceSymbolParams
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptSymbolTest extends Specification {

    Map<String,SymbolKind> getDocumentSymbols(ScriptService service, String uri) {
        def symbols = service.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)))
        def result = [:]
        for( def symbol : symbols )
            result[symbol.getRight().getName()] = symbol.getRight().getKind()
        return result
    }

    List<String> getWorkspaceSymbolNames(ScriptService service, String query) {
        return service
            .symbol(new WorkspaceSymbolParams(query))
            .collect { it.getName() }
    }

    def 'should provide a document symbol for each definition kind' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            record Sample {
                id: String
            }

            enum Color {
                RED, GREEN, BLUE
            }

            def greet(name) {
                return "Hello, ${name}!"
            }

            process FOO {
                script:
                """
                echo foo
                """
            }

            workflow BAR {
            }

            workflow {
                BAR()
            }
            ''')
        service.updateNow()
        def symbols = getDocumentSymbols(service, uri)
        then:
        symbols['record Sample'] == SymbolKind.Struct
        symbols['enum Color'] == SymbolKind.Enum
        symbols['function greet'] == SymbolKind.Function
        symbols['process FOO'] == SymbolKind.Function
        symbols['workflow BAR'] == SymbolKind.Function
        symbols['workflow <entry>'] == SymbolKind.Function
    }

    def 'should return no document symbols for an empty file' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '')
        service.updateNow()
        then:
        getDocumentSymbols(service, uri).isEmpty()
    }

    def 'should find workspace symbols by case-insensitive substring' () {
        given:
        def service = getScriptService()
        def mainUri = getUri('main.nf')
        def moduleUri = getUri('module.nf')

        when:
        open(service, moduleUri, '''\
            process ALIGN {
                script:
                """
                echo align
                """
            }
            ''')
        open(service, mainUri, '''\
            workflow ALIGN_READS {
            }

            workflow {
            }
            ''')
        service.updateNow()

        then: 'a query matches symbol names across files, case-insensitively'
        def names = getWorkspaceSymbolNames(service, 'align')
        names.sort() == ['process ALIGN', 'workflow ALIGN_READS']

        and: 'a non-matching query returns nothing'
        getWorkspaceSymbolNames(service, 'zzz').isEmpty()

        and: 'an empty query matches every definition'
        getWorkspaceSymbolNames(service, '').size() == 3
    }

}

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

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptCompletionTest extends Specification {

    List<CompletionItem> getCompletions(ScriptService service, String uri, Position position) {
        return service
            .completion(new CompletionParams(new TextDocumentIdentifier(uri), position), 100, false)
            .getLeft()
    }

    def 'should get completion proposals for a property expression' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                workflow.f
            }
            ''')
        def completions = getCompletions(service, uri, new Position(1, 14))
        then:
        completions.size() == 2
        def failOnIgnore = completions.find { it.getLabel() == "failOnIgnore" }
        failOnIgnore.getLabelDetails().getDescription() == "Boolean"
        failOnIgnore.getKind() == CompletionItemKind.Constant
        failOnIgnore.getDetail() == "failOnIgnore: Boolean"
        def fusion = completions.find { it.getLabel() == "fusion" }
        fusion.getLabelDetails().getDescription() == "namespace"
        fusion.getKind() == CompletionItemKind.Module
        fusion.getDetail() == "(namespace) fusion"

        when:
        open(service, uri, '''\
            workflow {
                workflow.config
            }
            ''')
        completions = getCompletions(service, uri, new Position(1, 19))
        then:
        completions.size() == 1
        def configFiles = completions.find { it.getLabel() == "configFiles" }
        configFiles.getLabelDetails().getDescription() == "List<Path>"
        configFiles.getKind() == CompletionItemKind.Constant
        configFiles.getDetail() == "configFiles: List<Path>"

        when:
        open(service, uri, '''\
            workflow {
                log.
            }
            ''')
        completions = getCompletions(service, uri, new Position(1, 8))
        then:
        completions.size() == 3
        ["info", "error", "warn"].every { name ->
            def item = completions.find { it.getLabel() == name }
            item != null \
                && item.getLabelDetails().getDetail() == "(message: String)" \
                && item.getKind() == CompletionItemKind.Function \
                && item.getDetail() == "${name}(message: String)"
        }
    }

    def 'should complete the name of a process in a workflow body' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            process ALIGN {
                script:
                """
                echo align
                """
            }

            workflow {
                ALI
            }
            ''')
        def completions = getCompletions(service, uri, new Position(8, 7))
        then:
        completions.find { it.getLabel() == "ALIGN" }?.getKind() == CompletionItemKind.Function
    }

    def 'should complete a process directive in a process body' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            process FOO {
                cp
                script:
                """
                echo foo
                """
            }
            ''')
        def completions = getCompletions(service, uri, new Position(1, 6))
        then:
        completions.find { it.getLabel() == "cpus" } != null
    }

}

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
        completions[0].getLabel() == "failOnIgnore"
        completions[0].getLabelDetails().getDescription() == "Boolean"
        completions[0].getKind() == CompletionItemKind.Constant
        completions[0].getDetail() == "failOnIgnore: Boolean"
        completions[1].getLabel() == "fusion"
        completions[1].getLabelDetails().getDescription() == "namespace"
        completions[1].getKind() == CompletionItemKind.Module
        completions[1].getDetail() == "(namespace) fusion"

        when:
        open(service, uri, '''\
            workflow {
                workflow.config
            }
            ''')
        completions = getCompletions(service, uri, new Position(1, 19))
        then:
        completions.size() == 1
        completions[0].getLabel() == "configFiles"
        completions[0].getLabelDetails().getDescription() == "List<Path>"
        completions[0].getKind() == CompletionItemKind.Constant
        completions[0].getDetail() == "configFiles: List<Path>"

        when:
        open(service, uri, '''\
            workflow {
                log.
            }
            ''')
        completions = getCompletions(service, uri, new Position(1, 8))
        then:
        completions.size() == 3
        completions[0].getLabel() == "info"
        completions[0].getLabelDetails().getDetail() == "(message: String)"
        completions[0].getKind() == CompletionItemKind.Function
        completions[0].getDetail() == "info(message: String)"
        completions[1].getLabel() == "error"
        completions[1].getLabelDetails().getDetail() == "(message: String)"
        completions[1].getKind() == CompletionItemKind.Function
        completions[1].getDetail() == "error(message: String)"
        completions[2].getLabel() == "warn"
        completions[2].getLabelDetails().getDetail() == "(message: String)"
        completions[2].getKind() == CompletionItemKind.Function
        completions[2].getDetail() == "warn(message: String)"
    }

}

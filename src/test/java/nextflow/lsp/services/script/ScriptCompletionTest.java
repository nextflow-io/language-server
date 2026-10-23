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

package nextflow.lsp.services.script;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptCompletionTest {

    static List<CompletionItem> getCompletions(ScriptService service, String uri, Position position) {
        return service
            .completion(new CompletionParams(new TextDocumentIdentifier(uri), position), 100, false)
            .getLeft();
    }

    static CompletionItem findByLabel(List<CompletionItem> completions, String label) {
        return completions.stream()
            .filter(it -> label.equals(it.getLabel()))
            .findFirst().orElse(null);
    }

    @Test
    void getCompletionProposalsForPropertyExpression() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                workflow.f
            }
            """);
        var completions = getCompletions(service, uri, new Position(1, 14));

        assertThat(completions).hasSize(2);
        var failOnIgnore = findByLabel(completions, "failOnIgnore");
        assertThat(failOnIgnore.getLabelDetails().getDescription()).isEqualTo("Boolean");
        assertThat(failOnIgnore.getKind()).isEqualTo(CompletionItemKind.Constant);
        assertThat(failOnIgnore.getDetail()).isEqualTo("failOnIgnore: Boolean");
        var fusion = findByLabel(completions, "fusion");
        assertThat(fusion.getLabelDetails().getDescription()).isEqualTo("namespace");
        assertThat(fusion.getKind()).isEqualTo(CompletionItemKind.Module);
        assertThat(fusion.getDetail()).isEqualTo("(namespace) fusion");

        open(service, uri, """
            workflow {
                workflow.config
            }
            """);
        completions = getCompletions(service, uri, new Position(1, 19));

        assertThat(completions).hasSize(1);
        var configFiles = findByLabel(completions, "configFiles");
        assertThat(configFiles.getLabelDetails().getDescription()).isEqualTo("List<Path>");
        assertThat(configFiles.getKind()).isEqualTo(CompletionItemKind.Constant);
        assertThat(configFiles.getDetail()).isEqualTo("configFiles: List<Path>");

        open(service, uri, """
            workflow {
                log.
            }
            """);
        completions = getCompletions(service, uri, new Position(1, 8));

        assertThat(completions).hasSize(3);
        var logCompletions = completions;
        assertThat(List.of("info", "error", "warn")).allSatisfy(name -> {
            var item = findByLabel(logCompletions, name);
            assertThat(item).isNotNull();
            assertThat(item.getLabelDetails().getDetail()).isEqualTo("(message: String)");
            assertThat(item.getKind()).isEqualTo(CompletionItemKind.Function);
            assertThat(item.getDetail()).isEqualTo(name + "(message: String)");
        });
    }

    @Test
    void completeProcessNameInWorkflowBody() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
            process ALIGN {
                script:
                \"""
                echo align
                \"""
            }

            workflow {
                ALI
            }
            """);
        var completions = getCompletions(service, uri, new Position(8, 7));

        assertThat(findByLabel(completions, "ALIGN"))
            .extracting(CompletionItem::getKind)
            .isEqualTo(CompletionItemKind.Function);
    }

    @Test
    void completeProcessDirectiveInProcessBody() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
            process FOO {
                cp
                script:
                \"""
                echo foo
                \"""
            }
            """);
        var completions = getCompletions(service, uri, new Position(1, 6));

        assertThat(findByLabel(completions, "cpus")).isNotNull();
    }

    @Test
    void noCompletionsInsideComment() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                // foo.
            }
            """);
        var completions = getCompletions(service, uri, new Position(1, 10));

        assertThat(completions).isEmpty();

        open(service, uri, """
            // foo.
            workflow {
            }
            """);
        completions = getCompletions(service, uri, new Position(0, 7));

        assertThat(completions).isEmpty();
    }

}

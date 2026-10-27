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
import java.util.function.Function;
import java.util.stream.Collectors;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies code completion in config files.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ConfigCompletionTest {

    static Either<List<CompletionItem>, CompletionList> either(TestServer server, String uri, Position position, int maxItems) {
        server.configure(Map.of("nextflow.completion.maxItems", maxItems));
        return server.call(s -> s.completion(new CompletionParams(new TextDocumentIdentifier(uri), position)));
    }

    static List<CompletionItem> getCompletions(TestServer server, String uri, Position position) {
        var result = either(server, uri, position, 100);
        return result.isLeft() ? result.getLeft() : result.getRight().getItems();
    }

    static Map<String,CompletionItem> byLabel(List<CompletionItem> completions) {
        return completions.stream()
            .collect(Collectors.toMap(CompletionItem::getLabel, Function.identity(), (a, b) -> b));
    }

    static CompletionItem findByLabel(List<CompletionItem> completions, String label) {
        return completions.stream()
            .filter(it -> label.equals(it.getLabel()))
            .findFirst().orElse(null);
    }

    @Test
    void provideTopLevelCompletionsForPartialScopeName() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", "wor\n");
        var completions = getCompletions(server, uri, new Position(0, 3));
        var byLabel = byLabel(completions);

        // a scope is offered both as a bare name and as a block snippet
        assertThat(byLabel.get("process")).extracting(CompletionItem::getKind).isEqualTo(CompletionItemKind.Property);
        assertThat(byLabel.get("process {")).extracting(CompletionItem::getKind).isEqualTo(CompletionItemKind.Property);
        // a top-level option carries its type in the detail
        assertThat(byLabel.get("workDir")).extracting(CompletionItem::getDetail).asString().startsWith("workDir:");
    }

    @Test
    void provideOptionCompletionsForConfigScope() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", "process.\n");
        var completions = getCompletions(server, uri, new Position(0, 8));
        var cpus = findByLabel(completions, "cpus");

        assertThat(cpus).isNotNull();
        assertThat(cpus.getKind()).isEqualTo(CompletionItemKind.Property);
        assertThat(cpus.getDetail()).isEqualTo("cpus: Integer");
    }

    @Test
    void showEveryAcceptedTypeForOptionWithMultipleTypes() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", "process.\n");
        var completions = getCompletions(server, uri, new Position(0, 8));
        var byLabel = byLabel(completions);

        // the types are shown in sorted order, since `types()` is not ordered
        assertThat(byLabel.get("arch")).extracting(CompletionItem::getDetail).isEqualTo("arch: Map | String");
        assertThat(byLabel.get("accelerator")).extracting(CompletionItem::getDetail).isEqualTo("accelerator: Integer | Map");
    }

    @Test
    void provideOptionCompletionsInsideProfileIgnoringProfileScope() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", """
            profiles {
                standard {
                    process.
                }
            }
            """);
        var completions = getCompletions(server, uri, new Position(2, 16));

        // the "profiles.<name>" prefix is stripped, so process options are offered
        assertThat(findByLabel(completions, "cpus")).extracting(CompletionItem::getDetail).isEqualTo("cpus: Integer");
    }

    @Test
    void markCompletionListIncompleteWhenItemsExceedMaxItems() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", "process.\n");
        var result = either(server, uri, new Position(0, 8), 1);

        assertThat(result).matches(Either::isRight, "is a completion list");
        assertThat(result.getRight()).matches(CompletionList::isIncomplete, "is incomplete");
        assertThat(result.getRight().getItems()).hasSize(1);
    }

    @Test
    void noCompletionsForFileThatIsNotOpen() {
        var server = TestServer.start();

        assertThat(getCompletions(server, getUri("other.config"), new Position(0, 0))).isEmpty();
    }

}

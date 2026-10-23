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
package nextflow.lsp;

import java.util.List;

import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for document updates: edits are compiled, requests see every
 * earlier notification, and closed or unsaved files are handled.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class DocumentUpdateTest {

    static int codeLensCount(TestServer server, String path) {
        return server.call(s -> s.codeLens(new CodeLensParams(server.document(path)))).size();
    }

    static List<String> documentSymbolNames(TestServer server, String path) {
        return server.call(s -> s.documentSymbol(new DocumentSymbolParams(server.document(path)))).stream()
            .map(it -> it.getRight().getName())
            .toList();
    }

    @Test
    void recompileAfterFullContentChange() {
        try( var server = TestServer.start() ) {
            // a valid file is opened -> no diagnostics
            server.open("main.nf", """
                workflow {
                    println('Hello!')
                }
                """);
            server.settle();
            assertThat(server.diagnostics("main.nf")).isEmpty();

            // the file is changed to introduce a syntax error -> the error is reported
            server.change("main.nf", "workflow {\n");
            server.settle();
            assertThat(server.diagnostics("main.nf")).isNotEmpty();
        }
    }

    @Test
    void recompileAfterIncrementalChange() {
        try( var server = TestServer.start() ) {
            server.open("main.nf", "workflow HELLO {\n}\n");
            assertThat(documentSymbolNames(server, "main.nf")).containsExactly("workflow HELLO");

            // the workflow name is edited in place
            server.change("main.nf", new Range(new Position(0, 9), new Position(0, 14)), "WORLD");
            assertThat(documentSymbolNames(server, "main.nf")).containsExactly("workflow WORLD");
        }
    }

    @Test
    void answerRequestsFromTheTextOfEveryEarlierChange() {
        try( var server = TestServer.start() ) {
            server.open("main.nf", "workflow HELLO {\n}\n");
            server.change("main.nf", """
                workflow HELLO {
                }

                workflow {
                    HELLO()
                }
                """);
            // no settle: each request builds what it needs
            assertThat(codeLensCount(server, "main.nf")).isEqualTo(2);
            assertThat(documentSymbolNames(server, "main.nf")).containsExactly("workflow HELLO", "workflow <entry>");
            var hover = server.call(s -> s.hover(new HoverParams(server.document("main.nf"), new Position(4, 4))));
            assertThat(hover.getContents().getRight().getValue()).contains("workflow HELLO");
            var tokens = server.call(s -> s.semanticTokensFull(new SemanticTokensParams(server.document("main.nf"))));
            assertThat(tokens.getData()).isNotEmpty();

            server.change("main.nf", "workflow   HELLO {\n}\n");
            var edits = server.call(s -> s.formatting(new DocumentFormattingParams(server.document("main.nf"), new FormattingOptions(4, true))));
            assertThat(edits).singleElement().satisfies(edit -> assertThat(edit.getNewText()).isEqualTo("workflow HELLO {\n}\n"));
        }
    }

    @Test
    void dropAstOfClosedInMemoryFile() {
        try( var server = TestServer.start() ) {
            server.open("main.nf", "workflow {\n}\n");
            assertThat(codeLensCount(server, "main.nf")).isEqualTo(1);

            // the file is closed (it exists only in memory, not on disk)
            server.close("main.nf");
            assertThat(codeLensCount(server, "main.nf")).isEqualTo(0);
        }
    }

    @Test
    void keepUnsavedFileAfterIndexing() {
        try( var server = TestServer.start((o) -> o.initialized = false) ) {
            // the file is opened before the workspace is indexed, and is not on disk
            server.open("new.nf", "workflow HELLO {\n}\n");
            server.server().initialized(new InitializedParams());
            server.settle();
            assertThat(documentSymbolNames(server, "new.nf")).containsExactly("workflow HELLO");
        }
    }

    @Test
    void keepOpenFilesWhenThereIsNoWorkspaceRoot() {
        try( var server = TestServer.start((o) -> o.folders()) ) {
            server.open("main.nf", "workflow {\n}\n");
            server.settle();
            assertThat(codeLensCount(server, "main.nf")).isEqualTo(1);
            assertThat(codeLensCount(server, "main.nf")).isEqualTo(1);
        }
    }

}

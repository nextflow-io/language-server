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

import nextflow.lsp.engine.QueuedExecutor;
import nextflow.lsp.engine.TestEnvironment;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test the workspace setup of the language server for clients
 * that don't send a configuration (e.g. editors other than VS Code).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class NextflowLanguageServerTest {

    private static final String INVALID_SCRIPT = "workflow {\n";

    @Test
    void useRootUriIfThereAreNoWorkspaceFolders() {
        var server = TestServer.start((o) -> o.rootUri = true);
        server.write("main.nf", INVALID_SCRIPT);
        server.open("main.nf", INVALID_SCRIPT);
        server.settle();
        assertThat(server.diagnostics("main.nf")).isNotEmpty();
    }

    @Test
    void reportDiagnosticsForFilesOutsideOfWorkspaceFolders() {
        var server = TestServer.start((o) -> o.folders());
        server.write("main.nf", INVALID_SCRIPT);
        server.open("main.nf", INVALID_SCRIPT);
        server.settle();
        assertThat(server.diagnostics("main.nf")).isNotEmpty();
    }

    @Test
    void excludeWorkDirectoryByDefault() {
        var server = TestServer.start((o) -> o.initialized = false);
        server.write("main.nf", INVALID_SCRIPT);
        server.write("work/ab/cdef/main.nf", INVALID_SCRIPT);
        server.initialized();
        server.open("main.nf", INVALID_SCRIPT);
        server.settle();
        assertThat(server.diagnostics("main.nf")).isNotEmpty();
        assertThat(server.client().getAllDiagnostics()).doesNotContainKey(server.uri("work/ab/cdef/main.nf"));
    }

    @Test
    void serveOpenFilesInExcludedDirectories() {
        var server = TestServer.start();
        var text = "workflow HELLO {\n    def x = 1\n}\n";
        server.write("work/ab/main.nf", text);
        server.open("work/ab/main.nf", text);
        var document = server.document("work/ab/main.nf");
        var symbols = server.call((s) -> s.documentSymbol(new DocumentSymbolParams(document)));
        assertThat(symbols).extracting(symbol -> symbol.getRight().getName()).containsExactly("workflow HELLO");
        var hover = server.call((s) -> s.hover(new HoverParams(document, new Position(0, 10))));
        assertThat(hover).isNotNull();
        server.settle();
        assertThat(server.diagnostics("work/ab/main.nf")).extracting(Diagnostic::getMessage).containsExactly(TestUtils.UNUSED);

        // once closed, the file is excluded again
        server.close("work/ab/main.nf");
        server.settle();
        assertThat(server.diagnostics("work/ab/main.nf")).isEmpty();
        symbols = server.call((s) -> s.documentSymbol(new DocumentSymbolParams(document)));
        assertThat(symbols).isEmpty();
    }

    @Test
    void answerScriptOnlyRequestsOnConfigFilesWithoutWaitingForTheIndex() {
        var io = new QueuedExecutor();
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.settle = false;
        });
        var config = "process.cpus = 2\n";
        server.write("nextflow.config", config);
        server.open("nextflow.config", config);
        var document = server.document("nextflow.config");
        var position = new Position(0, 0);
        var references = server.send((s) -> s.references(new ReferenceParams(document, position, new ReferenceContext(true))));
        var rename = server.send((s) -> s.rename(new RenameParams(document, position, "x")));
        server.runPending();
        assertThat(references).isCompletedWithValue(List.of());
        assertThat(rename).isCompletedWithValue(null);
    }

    @Test
    void exitWithStatusZeroOnlyAfterShutdown() {
        var abrupt = TestServer.start();
        abrupt.server().exit();
        assertThat(abrupt.env().exits()).containsExactly(1);

        var server = TestServer.start();
        assertThat(server.call(NextflowLanguageServer::shutdown)).isNull();
        server.server().exit();
        assertThat(server.env().exits()).containsExactly(0);
    }

    @Test
    void rejectRequestsAfterShutdown() {
        var server = TestServer.start();
        server.open("main.nf", INVALID_SCRIPT);
        server.call(NextflowLanguageServer::shutdown);
        var hover = server.send(s -> s.hover(new HoverParams(server.document("main.nf"), new Position(0, 0))));
        server.runPending();
        assertThat(hover).isCompletedExceptionally();
        assertThatThrownBy(hover::join)
            .hasCauseInstanceOf(ResponseErrorException.class)
            .cause()
            .satisfies(e -> assertThat(((ResponseErrorException) e).getResponseError().getCode()).isEqualTo(ResponseErrorCode.InvalidRequest.getValue()));
    }

}

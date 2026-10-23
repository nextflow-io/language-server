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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void reportDiagnosticsWithoutConfiguration() {
        try( var server = TestServer.start((o) -> o.settle = false) ) {
            server.write("main.nf", INVALID_SCRIPT);
            server.open("main.nf", INVALID_SCRIPT);
            server.settle();
            assertThat(server.diagnostics("main.nf")).isNotEmpty();
        }
    }

    @Test
    void useRootUriIfThereAreNoWorkspaceFolders() {
        try( var server = TestServer.start((o) -> o.rootUri = true) ) {
            server.write("main.nf", INVALID_SCRIPT);
            server.open("main.nf", INVALID_SCRIPT);
            server.settle();
            assertThat(server.diagnostics("main.nf")).isNotEmpty();
        }
    }

    @Test
    void reportDiagnosticsForFilesOutsideOfWorkspaceFolders() {
        try( var server = TestServer.start((o) -> o.folders()) ) {
            server.write("main.nf", INVALID_SCRIPT);
            server.open("main.nf", INVALID_SCRIPT);
            server.settle();
            assertThat(server.diagnostics("main.nf")).isNotEmpty();
        }
    }

    @Test
    void excludeWorkDirectoryByDefault() {
        try( var server = TestServer.start((o) -> o.initialized = false) ) {
            server.write("main.nf", INVALID_SCRIPT);
            server.write("work/ab/cdef/main.nf", INVALID_SCRIPT);
            server.server().initialized(new InitializedParams());
            server.open("main.nf", INVALID_SCRIPT);
            server.settle();
            assertThat(server.diagnostics("main.nf")).isNotEmpty();
            assertThat(server.client().getAllDiagnostics()).doesNotContainKey(server.uri("work/ab/cdef/main.nf"));
        }
    }

    @Test
    void serveOpenFilesInExcludedDirectories() {
        try( var server = TestServer.start() ) {
            var text = "workflow HELLO {\n    def x = 1\n}\n";
            server.write("work/ab/main.nf", text);
            server.open("work/ab/main.nf", text);
            var document = server.document("work/ab/main.nf");
            var symbols = server.call((s) -> s.documentSymbol(new DocumentSymbolParams(document)));
            assertThat(symbols).extracting(symbol -> symbol.getRight().getName()).containsExactly("workflow HELLO");
            var hover = server.call((s) -> s.hover(new HoverParams(document, new Position(0, 10))));
            assertThat(hover).isNotNull();
            server.settle();
            assertThat(server.diagnostics("work/ab/main.nf")).extracting(Diagnostic::getMessage).containsExactly("Variable was declared but not used");

            // once closed, the file is excluded again
            server.close("work/ab/main.nf");
            server.settle();
            assertThat(server.diagnostics("work/ab/main.nf")).isEmpty();
            symbols = server.call((s) -> s.documentSymbol(new DocumentSymbolParams(document)));
            assertThat(symbols).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportProgressOnlyIfClientSupportsIt(boolean supported) {
        try( var server = TestServer.start((o) -> {
            o.progress = supported;
            o.initialized = false;
        }) ) {
            server.write("main.nf", INVALID_SCRIPT);
            server.server().initialized(new InitializedParams());
            server.settle();
            assertThat(server.client().getProgress().isEmpty()).isNotEqualTo(supported);
        }
    }

    @Test
    void endProgressOnlyAfterTheIndexIsComplete() {
        var publishedAtEnd = new ArrayList<Set<String>>();
        var published = new HashSet<String>();
        var client = new TestLanguageClient() {
            @Override
            public void publishDiagnostics(PublishDiagnosticsParams params) {
                super.publishDiagnostics(params);
                published.add(params.getUri());
            }

            @Override
            public void notifyProgress(ProgressParams params) {
                super.notifyProgress(params);
                if( params.getValue().getLeft() instanceof WorkDoneProgressEnd )
                    publishedAtEnd.add(Set.copyOf(published));
            }
        };
        try( var server = TestServer.start((o) -> {
            o.client = client;
            o.progress = true;
            o.maxSliceFiles = 2;
            o.initialized = false;
        }) ) {
            var files = new ArrayList<String>();
            for( int i = 0; i < 7; i++ ) {
                var path = "modules/mod" + i + ".nf";
                server.write(path, "workflow MOD" + i + " { def x = 1 }\n");
                files.add(server.uri(path));
            }
            server.server().initialized(new InitializedParams());
            server.settle();
            assertThat(client.getProgress()).first().isInstanceOf(WorkDoneProgressCreateParams.class);
            assertThat(publishedAtEnd).hasSize(1);
            assertThat(publishedAtEnd.get(0)).containsAll(files);
        }
    }

    @Test
    void exitWithStatusZeroOnlyAfterShutdown() {
        try( var server = TestServer.start() ) {
            server.server().exit();
            assertThat(server.env().exits()).containsExactly(1);
        }
        try( var server = TestServer.start() ) {
            assertThat(server.call(NextflowLanguageServer::shutdown)).isNull();
            server.server().exit();
            assertThat(server.env().exits()).containsExactly(0);
        }
    }

    @Test
    void rejectRequestsAfterShutdown() {
        try( var server = TestServer.start() ) {
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

}

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

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import nextflow.lsp.engine.DefaultEngineEnvironment;
import nextflow.lsp.engine.ForwardingFileAccess;
import nextflow.lsp.engine.Workspace;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test the language server through lsp4j over piped streams, with
 * the production engine and pools.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
@Isolated // real threads, so other tests would slow it down
@Timeout(120) // hang guard
class LanguageServerWiringTest {

    /**
     * File access that blocks reading one file until it is released.
     */
    static final class BlockingFileAccess extends ForwardingFileAccess {

        final CountDownLatch entered = new CountDownLatch(1);

        final CountDownLatch released = new CountDownLatch(1);

        private final Predicate<Path> blocking;

        BlockingFileAccess(Predicate<Path> blocking) {
            this.blocking = blocking;
        }

        @Override
        public String readText(Path path) throws IOException {
            if( blocking.test(path) ) {
                entered.countDown();
                try {
                    released.await();
                }
                catch( InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            return super.readText(path);
        }
    }

    /**
     * The reader thread should handle configuration and folder changes
     * and answer `$/cancelRequest` while the engine is blocked.
     */
    @Test
    void answerCancelRequestWhileTheEngineIsBlocked() throws Exception {
        var root = TestWorkspace.root();
        TestUtils.writeWorkspaceFile("slow.nf", "workflow SLOW {}\n");
        TestUtils.writeWorkspaceFile("other/main.nf", "workflow OTHER {}\n");
        var files = new BlockingFileAccess((path) -> path.endsWith("slow.nf"));
        var cancelled = new CompletableFuture<ResponseMessage>();

        var toServer = Pipe.open();
        var toClient = Pipe.open();
        var server = new NextflowLanguageServer(new DefaultEngineEnvironment(files), Workspace.factory());
        var serverLauncher = Launcher.createLauncher(
            server, LanguageClient.class,
            Channels.newInputStream(toServer.source()), Channels.newOutputStream(toClient.sink()));
        var clientLauncher = new Launcher.Builder<LanguageServer>()
            .setLocalService(new TestLanguageClient())
            .setRemoteInterface(LanguageServer.class)
            .setInput(Channels.newInputStream(toClient.source()))
            .setOutput(Channels.newOutputStream(toServer.sink()))
            .wrapMessages((consumer) -> (message) -> {
                if( message instanceof ResponseMessage response
                        && response.getError() != null
                        && response.getError().getCode() == ResponseErrorCode.RequestCancelled.getValue() )
                    cancelled.complete(response);
                consumer.consume(message);
            })
            .create();
        server.connect(serverLauncher.getRemoteProxy());
        serverLauncher.startListening();
        clientLauncher.startListening();
        var remote = clientLauncher.getRemoteProxy();

        try {
            var init = new InitializeParams();
            init.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(), "root")));
            remote.initialize(init).get(30, TimeUnit.SECONDS);
            remote.initialized(new InitializedParams());
            var workspace = remote.getWorkspaceService();
            var documents = remote.getTextDocumentService();

            // the index build blocks the engine while it reads slow.nf
            assertThat(files.entered.await(30, TimeUnit.SECONDS)).isTrue();

            // configuration and folder changes are handled without waiting for the engine
            workspace.didChangeConfiguration(TestServer.configuration(Map.of("nextflow.files.exclude", List.of("tests"))));
            var other = new WorkspaceFolder(root.resolve("other").toUri().toString(), "other");
            workspace.didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(new WorkspaceFoldersChangeEvent(List.of(other), List.of())));

            var uri = root.resolve("main.nf").toUri().toString();
            documents.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "nextflow", 1, "workflow HELLO {}\n")));
            var symbols = documents.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)));
            symbols.cancel(true);
            documents.didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, 2),
                List.of(new TextDocumentContentChangeEvent("workflow WORLD {}\n"))));

            // the reader thread handles the cancellation while the engine is still blocked
            cancelled.get(30, TimeUnit.SECONDS);

            // once the build is released, later requests see the change
            files.released.countDown();
            var result = documents.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri))).get(30, TimeUnit.SECONDS);
            assertThat(result).extracting(s -> s.getRight().getName()).containsExactly("workflow WORLD");
        }
        finally {
            files.released.countDown();
            remote.shutdown().get(30, TimeUnit.SECONDS);
        }
    }

}

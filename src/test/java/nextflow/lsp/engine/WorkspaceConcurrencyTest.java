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
package nextflow.lsp.engine;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests with real threads: reading on a real pool, and a stress test
 * of the production engine with concurrent cancellation.
 *
 * Timeouts are only hang guards.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
@Isolated // real threads, so other tests would slow it down
class WorkspaceConcurrencyTest {

    static final int MODULES = 40;

    static final List<String> VARIANTS = List.of(
        "workflow MOD%d { println 'ok' }\n",
        "workflow MOD%d { def x = 1 }\n",
        "workflow MOD%d {\n",
        "include { MOD%d_HELPER } from './helper.nf'\nworkflow MOD%<d { MOD%<d_HELPER() }\n",
        "include { HELPER } from './helper.nf'\nworkflow MOD%d { HELPER() }\n",
        // no longer exports the name that main.nf includes
        "workflow RENAMED%d {}\n");

    static void writeWorkspace(TestServer f) {
        f.write("modules/helper.nf", "workflow HELPER { def unused = 1 }\n");
        var main = new StringBuilder();
        for( int i = 0; i < MODULES; i++ ) {
            f.write("modules/mod" + i + ".nf", VARIANTS.get(i % VARIANTS.size()).formatted(i));
            main.append("include { MOD" + i + " } from './modules/mod" + i + ".nf'\n");
        }
        main.append("workflow {\n");
        for( int i = 0; i < MODULES; i++ )
            main.append("    MOD" + i + "()\n");
        main.append("}\n");
        f.write("main.nf", main.toString());
    }

    static Map<String, List<Diagnostic>> nonEmpty(Map<String, List<Diagnostic>> diagnostics) {
        var result = new HashMap<String, List<Diagnostic>>();
        diagnostics.forEach((uri, list) -> {
            if( !list.isEmpty() )
                result.put(uri, List.copyOf(list));
        });
        return result;
    }

    // parsing must stay on the engine thread, because nf-lang writes to
    // shared ClassHelper nodes (Builder.parse asserts the engine thread)
    @Test
    @Timeout(120)
    void readOnARealPoolAndParseOnTheEngineThread() {
        Map<String, List<Diagnostic>> direct;
        try( var f = TestServer.start((o) -> o.settle = false) ) {
            writeWorkspace(f);
            f.settle();
            direct = nonEmpty(f.client().getAllDiagnostics());
        }
        var pool = new ForkJoinPool(4);
        try( var f = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withReadPool(pool);
            o.maxSliceFiles = 16;
            o.settle = false;
        }) ) {
            f.settle();
            assertThat(nonEmpty(f.client().getAllDiagnostics())).isEqualTo(direct).isNotEmpty();
            assertThat(f.client().getLogMessages()).noneMatch((m) -> m.getMessage().contains("Internal error"));
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(120)
    void stressTheProductionEngine() throws Exception {
        Map<String, String> openDocuments;
        Map<String, List<Diagnostic>> published;
        var unexpected = Collections.synchronizedList(new ArrayList<String>());
        var client = new TestLanguageClient() {
            @Override
            public void logMessage(org.eclipse.lsp4j.MessageParams message) {
                super.logMessage(message);
                if( message.getMessage().contains("Unexpected") || message.getMessage().contains("Internal error") )
                    unexpected.add(message.getMessage());
            }
        };
        try( var f = TestServer.start((o) -> o.settle = false) ) {
            writeWorkspace(f);
        }
        var env = EngineEnvironment.system();
        var engine = new Engine(env, client, Duration.ofMillis(5), Workspace.factory(8));
        try {
            var params = new InitializeParams();
            params.setCapabilities(new ClientCapabilities());
            params.setWorkspaceFolders(List.of(new WorkspaceFolder(TestWorkspace.root().toUri().toString(), "root")));
            engine.submit(new Event.Init(params));
            engine.submit(new Event.Initialized(new InitializedParams()));

            var random = new Random(42);
            var futures = Collections.synchronizedList(new ArrayList<CompletableFuture<?>>());
            var done = new AtomicBoolean();
            var canceller = new Thread(() -> {
                var cancelRandom = new Random(7);
                while( !done.get() ) {
                    CompletableFuture<?> target = null;
                    synchronized (futures) {
                        if( !futures.isEmpty() )
                            target = futures.get(cancelRandom.nextInt(futures.size()));
                    }
                    if( target != null && cancelRandom.nextInt(10) == 0 )
                        target.cancel(true);
                    Thread.yield();
                }
            });
            canceller.start();

            openDocuments = new HashMap<>();
            var versions = new HashMap<String, Integer>();
            for( int step = 0; step < 400; step++ ) {
                // let the engine catch up now and then, so that later
                // messages interleave with builds instead of coalescing
                if( step % 20 == 19 ) {
                    var mainUri = TestWorkspace.root().resolve("main.nf").toUri().toString();
                    var paced = engine.request(new RequestSpec("pace", mainUri, null, Need.FILE, false), null, (context) -> null);
                    paced.get(60, TimeUnit.SECONDS);
                }
                var module = "modules/mod" + random.nextInt(MODULES) + ".nf";
                var uri = TestWorkspace.root().resolve(module).toUri().toString();
                var index = Integer.parseInt(module.replaceAll("\\D", ""));
                var text = VARIANTS.get(random.nextInt(VARIANTS.size())).formatted(index);
                switch( random.nextInt(6) ) {
                    case 0 -> {
                        if( !openDocuments.containsKey(module) ) {
                            versions.put(module, 1);
                            openDocuments.put(module, text);
                            engine.submit(new Event.DidOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "nextflow", 1, text))));
                        }
                    }
                    case 1 -> {
                        if( openDocuments.containsKey(module) ) {
                            var version = versions.merge(module, 1, Integer::sum);
                            openDocuments.put(module, text);
                            engine.submit(new Event.DidChange(new DidChangeTextDocumentParams(
                                new VersionedTextDocumentIdentifier(uri, version),
                                List.of(new TextDocumentContentChangeEvent(text)))));
                        }
                    }
                    case 2 -> {
                        if( openDocuments.remove(module) != null )
                            engine.submit(new Event.DidClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri))));
                    }
                    case 3 -> futures.add(engine.request(new RequestSpec("file", uri, null, Need.FILE, false), -1,
                        (context) -> context.project().scriptView().hasAST(URI.create(uri)) ? 1 : 0));
                    case 4 -> futures.add(engine.request(new RequestSpec("project", uri, null, Need.PROJECT, random.nextBoolean()), -1,
                        (context) -> context.project().scriptView().getUris().size()));
                    default -> futures.add(engine.request(new RequestSpec("all", null, null, Need.ALL, false), -1,
                        (context) -> context.allProjects().size()));
                }
            }
            done.set(true);
            canceller.join();

            // every project is indexed and fresh when this request runs
            var barrier = engine.request(new RequestSpec("barrier", null, null, Need.ALL, false), null,
                (context) -> nonEmpty(client.getAllDiagnostics()));
            published = barrier.get(60, TimeUnit.SECONDS);

            for( var future : futures ) {
                assertThat(future).isDone();
                try {
                    future.join();
                }
                catch( CancellationException e ) {
                    // cancelled by the canceller
                }
                catch( CompletionException e ) {
                    assertThat(e.getCause()).isInstanceOf(ResponseErrorException.class);
                    var code = ((ResponseErrorException) e.getCause()).getResponseError().getCode();
                    assertThat(code).isEqualTo(ResponseErrorCode.ContentModified.getValue());
                }
            }
            assertThat(unexpected).isEmpty();
        }
        finally {
            engine.shutdown().get(30, TimeUnit.SECONDS);
            env.engineExecutor().shutdown();
        }

        // the final diagnostics equal those of a fresh workspace
        try( var fresh = TestServer.start((o) -> o.settle = false) ) {
            openDocuments.forEach(fresh::open);
            fresh.settle();
            assertThat(published).isEqualTo(nonEmpty(fresh.client().getAllDiagnostics()));
        }
    }

}

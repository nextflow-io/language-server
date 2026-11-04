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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.UNUSED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies the workspace scan, indexing progress and background
 * scheduling.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceIndexTest {

    static List<URI> builtScripts(RequestContext context) {
        return List.copyOf(context.project().scriptView().getUris());
    }

    static void writeModules(TestServer server, int count) {
        for( int i = 0; i < count; i++ )
            server.write("modules/mod" + i + ".nf", "workflow MOD" + i + " { def x = 1 }\n");
    }

    /**
     * Get the kind of each progress message, in order.
     */
    static List<String> progressKinds(TestLanguageClient client) {
        var result = new ArrayList<String>();
        synchronized (client.getProgress()) {
            for( var item : client.getProgress() ) {
                if( item instanceof WorkDoneProgressCreateParams ) {
                    result.add("create");
                    continue;
                }
                var value = ((ProgressParams) item).getValue().getLeft();
                result.add(
                    value instanceof WorkDoneProgressBegin ? "begin" :
                    value instanceof WorkDoneProgressReport ? "report" :
                    value instanceof WorkDoneProgressEnd ? "end" : "?");
            }
        }
        return result;
    }

    private static void runUntilIndexingHasParsed(TestServer server) {
        var executor = server.env().manualExecutor();
        while( !(server.workspace().isIndexing() && totalParses(server) > 0) )
            assertThat(executor.runNext()).as("indexing parsed some files before going idle").isTrue();
    }

    private static int totalParses(TestServer server) {
        return server.parses().values().stream().mapToInt(Integer::intValue).sum();
    }

    private static List<String> progressTokens(TestLanguageClient client) {
        var tokens = new ArrayList<String>();
        synchronized (client.getProgress()) {
            for( var item : client.getProgress() ) {
                if( item instanceof WorkDoneProgressCreateParams params )
                    tokens.add(params.getToken().getLeft());
            }
        }
        return tokens;
    }

    /**
     * File access whose walk omits some files, as if the listing was
     * taken before they were created.
     */
    static final class LaggingWalk extends ForwardingFileAccess {

        private final Predicate<Path> omitted;

        LaggingWalk(Predicate<Path> omitted) {
            this.omitted = omitted;
        }

        @Override
        public List<Path> walk(Path root, Predicate<Path> excluded, Predicate<Path> matcher) throws IOException {
            var result = new ArrayList<>(super.walk(root, excluded, matcher));
            result.removeIf(omitted);
            return result;
        }
    }

    /**
     * Indexing progress is reported to clients that support it.
     */
    @Nested
    class Progress {

        @Test
        void endProgressOnlyWhenEveryProjectIsIndexed() {
            var publishedAtEnd = new ArrayList<String>();
            var client = new TestLanguageClient();
            client.onProgressEnd(() -> {
                synchronized (client.getPublishLog()) {
                    for( var publish : client.getPublishLog() ) {
                        if( !publish.getDiagnostics().isEmpty() )
                            publishedAtEnd.add(publish.getUri());
                    }
                }
            });
            var server = TestServer.start((o) -> {
                o.client = client;
                o.progress = true;
                o.maxSliceFiles = 3;
                o.folders("a", "b");
                o.initialized = false;
                o.settle = false;
            });
            var files = new ArrayList<String>();
            for( int i = 0; i < 10; i++ ) {
                server.write("a/mod" + i + ".nf", "workflow A" + i + " { def x = 1 }\n");
                server.write("b/mod" + i + ".nf", "workflow B" + i + " { def x = 1 }\n");
                files.add(server.uri("a/mod" + i + ".nf"));
                files.add(server.uri("b/mod" + i + ".nf"));
            }
            server.initialized();
            server.settle();
            assertThat(progressKinds(client)).first().isEqualTo("create");
            assertThat(progressKinds(client)).element(1).isEqualTo("begin");
            assertThat(progressKinds(client)).contains("report");
            assertThat(progressKinds(client)).last().isEqualTo("end");
            assertThat(progressKinds(client)).filteredOn("end"::equals).hasSize(1);
            assertThat(publishedAtEnd).containsExactlyInAnyOrderElementsOf(files);
        }

        @Test
        void beginProgressOnlyAfterTheTokenIsCreated() {
            var client = new TestLanguageClient();
            client.setHoldProgressCreate(true);
            var io = new QueuedExecutor();
            var server = TestServer.start((o) -> {
                o.client = client;
                o.environment = new TestEnvironment().withIoExecutor(io);
                o.progress = true;
                o.initialized = false;
                o.settle = false;
            });
            writeModules(server, 3);
            server.initialized();
            server.runPending();
            assertThat(progressKinds(client)).containsExactly("create");
            client.completeProgressCreate();
            server.runPending();
            assertThat(progressKinds(client)).containsExactly("create", "begin");
            server.settle(io);
            assertThat(progressKinds(client)).startsWith("create", "begin").endsWith("end");

            // a new run gets a new token
            server.write("other/mod.nf", "workflow OTHER {}\n");
            server.addFolder("other");
            server.runPending();
            client.completeProgressCreate();
            server.settle(io);
            assertThat(progressTokens(client)).hasSize(2).doesNotHaveDuplicates();
        }

        @Test
        void reportTheNumberOfFilesOnlyOnceItIsKnown() {
            var client = new TestLanguageClient();
            var io = new QueuedExecutor();
            var server = TestServer.start((o) -> {
                o.client = client;
                o.environment = new TestEnvironment().withIoExecutor(io);
                o.progress = true;
                o.settle = false;
            });
            writeModules(server, 3);
            server.runPending();
            server.settle(io);
            var messages = new ArrayList<String>();
            synchronized (client.getProgress()) {
                for( var item : client.getProgress() ) {
                    if( item instanceof ProgressParams params ) {
                        var value = params.getValue().getLeft();
                        if( value instanceof WorkDoneProgressBegin begin )
                            messages.add("begin " + begin.getMessage());
                        else if( value instanceof WorkDoneProgressReport report )
                            messages.add("report " + report.getMessage());
                        else
                            messages.add("end");
                    }
                }
            }
            assertThat(messages).first().isEqualTo("begin null");
            assertThat(messages).endsWith("report 3 / 3 files", "end");
            assertThat(messages).noneMatch(m -> m.contains("/ 0"));
        }

        @Test
        void ignoreTheTokenOfAPreviousRun() {
            var client = new TestLanguageClient();
            client.setHoldProgressCreate(true);
            var io = new QueuedExecutor();
            var server = TestServer.start((o) -> {
                o.client = client;
                o.environment = new TestEnvironment().withIoExecutor(io);
                o.progress = true;
                o.initialized = false;
                o.settle = false;
            });
            writeModules(server, 1);
            server.initialized();
            server.settle(io);
            server.addFolder("other");
            server.runPending();
            assertThat(progressKinds(client)).containsExactly("create", "create");
            var tokens = progressTokens(client);

            // the first run ended before its token was created
            client.completeProgressCreate(tokens.get(0));
            server.runPending();
            assertThat(progressKinds(client)).containsExactly("create", "create");
            client.completeProgressCreate(tokens.get(1));
            server.runPending();
            assertThat(progressKinds(client)).containsExactly("create", "create", "begin");
            server.settle(io);
        }

        @Test
        void requestTheTokenOnlyOncePerRunWhenTheClientRefusesIt() {
            var client = new TestLanguageClient() {
                @Override
                public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
                    getProgress().add(params);
                    return CompletableFuture.failedFuture(new IllegalStateException("refused"));
                }
            };
            var server = TestServer.start((o) -> {
                o.client = client;
                o.progress = true;
                o.maxSliceFiles = 2;
                o.initialized = false;
                o.settle = false;
            });
            writeModules(server, 20);
            server.initialized();
            server.settle();
            assertThat(progressKinds(client)).containsExactly("create");

            // a new run requests a new token
            server.write("other/mod.nf", "workflow OTHER {}\n");
            server.addFolder("other");
            server.settle();
            assertThat(progressKinds(client)).containsExactly("create", "create");
        }

        @Test
        void doNotReportProgressWithoutClientSupport() {
            var server = TestServer.start((o) -> o.settle = false);
            writeModules(server, 3);
            server.settle();
            assertThat(server.client().getProgress()).isEmpty();
        }

    }

    /**
     * The workspace scan finds the files of each folder, and files are
     * loaded on demand before it completes.
     */
    @Nested
    class Scan {

        @Test
        void keepOpenFilesThatAreNotOnDisk() {
            var server = TestServer.start((o) -> {
                o.initialized = false;
                o.settle = false;
            });
            server.write("main.nf", "workflow {}\n");
            server.open("new.nf", "workflow NEW { def x = 1 }\n");
            server.initialized();
            server.settle();
            assertThat(server.messages("new.nf")).containsExactly(UNUSED);
            var scripts = server.call(Need.PROJECT, "main.nf", List.<URI>of(), WorkspaceIndexTest::builtScripts);
            assertThat(scripts).contains(URI.create(server.uri("new.nf")));
            var symbols = server.call((s) -> s.documentSymbol(new DocumentSymbolParams(server.document("new.nf"))));
            assertThat(symbols).extracting((symbol) -> symbol.getRight().getName()).containsExactly("workflow NEW");
        }

        @Test
        void keepFilesCreatedDuringTheWalk() {
            var io = new QueuedExecutor();
            var server = TestServer.start((o) -> {
                o.environment = new TestEnvironment()
                    .withIoExecutor(io)
                    .withFiles(new LaggingWalk((path) -> path.getFileName().toString().startsWith("late")));
                o.watchers = true;
                o.settle = false;
            });
            server.write("main.nf", "workflow { def x = 1 }\n");
            server.runPending();
            // created while the walk is in flight
            server.writeAndNotify("late.nf", "workflow LATE { def y = 1 }\n");
            // loaded on demand while the walk is in flight
            server.write("late-request.nf", "workflow LATE_REQUEST { def z = 1 }\n");
            server.call(Need.FILE, "late-request.nf", null, (context) -> null);
            server.settle(io);
            assertThat(server.messages("main.nf")).containsExactly(UNUSED);
            assertThat(server.messages("late.nf")).containsExactly(UNUSED);
            assertThat(server.messages("late-request.nf")).containsExactly(UNUSED);
        }

        @Test
        void keepFilesOpenedAndClosedDuringTheWalk() {
            var io = new QueuedExecutor();
            var server = TestServer.start((o) -> {
                o.environment = new TestEnvironment()
                    .withIoExecutor(io)
                    .withFiles(new LaggingWalk((path) -> path.getFileName().toString().startsWith("late")));
                o.watchers = true;
                o.settle = false;
            });
            server.write("main.nf", "workflow { def x = 1 }\n");
            server.runPending();
            // created and opened while the walk is in flight, with the
            // watcher event arriving after the open
            server.write("late.nf", "workflow LATE { def y = 1 }\n");
            server.open("late.nf", "workflow LATE { def y = 1 }\n");
            server.notify("late.nf", FileChangeType.Created);
            server.close("late.nf");
            server.settle(io);
            assertThat(server.messages("late.nf")).containsExactly(UNUSED);
        }

        @Test
        void reportWalkThatThrowsRuntimeExceptionAsFailedWalk() {
            var env = new TestEnvironment().withFiles(new ForwardingFileAccess() {
                @Override
                public List<Path> walk(Path root, Predicate<Path> excluded, Predicate<Path> matcher) {
                    throw new UncheckedIOException(new IOException("stale file handle"));
                }
            });
            var server = TestServer.start((o) -> {
                o.environment = env;
                o.settle = false;
            });
            server.write("main.nf", "workflow {}\n");
            // the project request completes instead of waiting for the walk forever
            assertThat(server.call(Need.PROJECT, "main.nf", "empty", (context) -> "done")).isEqualTo("done");
            assertThat(server.workspace().isIndexing()).as("indexing").isFalse();
            assertThat(server.client().getLogMessages())
                .anyMatch(m -> m.getMessage().contains("Failed to scan workspace folder"));
        }

        @Test
        void loadFileOnDemandBeforeTheScan() {
            var server = TestServer.start((o) -> {
                o.initialized = false;
            });
            server.write("main.nf", """
                include { FOO } from './mod.nf'
                workflow {
                    FOO()
                }
                """);
            server.write("mod.nf", "workflow FOO {}\n");
            var scripts = server.call(Need.FILE, "main.nf", List.<URI>of(), WorkspaceIndexTest::builtScripts);
            assertThat(scripts).containsExactly(URI.create(server.uri("main.nf")), URI.create(server.uri("mod.nf")));
        }

        @Test
        void buildEveryConfigForProjectConfigRequests() {
            var server = TestServer.start((o) -> o.initialized = false);
            server.write("nextflow.config", "includeConfig 'conf/base.config'\n");
            server.write("conf/base.config", "process.cpus = 2\n");
            server.write("conf/other.config", "process.memory = '1 GB'\n");
            server.write("main.nf", "workflow {}\n");
            server.open("main.nf", "workflow {}\n");
            // before the scan, the main config and its includes are loaded on demand
            var configs = server.call(Need.PROJECT_CONFIGS, "main.nf", List.<URI>of(), (context) ->
                List.copyOf(context.project().configView().getUris()));
            assertThat(configs).containsExactly(URI.create(server.uri("conf/base.config")), URI.create(server.uri("nextflow.config")));

            // other configs are known once the scan has found them
            server.initialized();
            server.runPending();
            configs = server.call(Need.PROJECT_CONFIGS, "main.nf", List.<URI>of(), (context) ->
                List.copyOf(context.project().configView().getUris()));
            assertThat(configs).hasSize(3);
        }

        @Test
        void returnEmptyValueForUnmanagedFiles() {
            var server = TestServer.start();
            server.write("work/ab/main.nf", "workflow {}\n");
            server.write("notes.txt", "hello\n");
            assertThat(server.call(Need.FILE, "work/ab/main.nf", "empty", (context) -> "ran")).isEqualTo("empty");
            assertThat(server.call(Need.FILE, "notes.txt", "empty", (context) -> "ran")).isEqualTo("empty");
            var spec = new RequestSpec("test", "untitled:Untitled-1", null, Need.FILE, false);
            assertThat(server.harness().call(spec, "empty", (context) -> "ran")).isEqualTo("empty");
            assertThat(server.client().getAllDiagnostics()).isEmpty();
        }

    }

    /**
     * Indexing and background builds run in slices that yield to requests.
     */
    @Nested
    class Scheduling {

        @Test
        void buildOpenFilesFirst() {
            var server = TestServer.start((o) -> {
                o.maxSliceFiles = 2;
                o.initialized = false;
                o.settle = false;
            });
            writeModules(server, 10);
            server.open("modules/mod7.nf", "workflow MOD7 { def x = 1 }\n");
            server.initialized();
            server.settle();
            // the first slice holds the open file
            var log = server.client().getPublishLog();
            assertThat(log).hasSize(10);
            assertThat(log.subList(0, 2)).anyMatch(p -> p.getUri().equals(server.uri("modules/mod7.nf")));
        }

        @Test
        void doNotPauseIndexingForTheDebounce() {
            var server = TestServer.start((o) -> {
                o.maxSliceFiles = 5;
                o.initialized = false;
                o.settle = false;
            });
            writeModules(server, 50);
            server.open("main.nf", "workflow {}\n");
            server.initialized();
            // edit once indexing has built some files
            runUntilIndexingHasParsed(server);
            server.change("main.nf", "workflow { def x = 1 }\n");
            var now = server.env().fakeClock().instant();
            server.runPending();
            assertThat(server.env().fakeClock().instant()).isEqualTo(now);
            assertThat(server.workspace().isIndexing()).as("indexing").isFalse();
            assertThat(server.engine().isIdle()).as("idle").isTrue();
            assertThat(server.messages("modules/mod49.nf")).containsExactly(UNUSED);
            assertThat(server.messages("main.nf")).containsExactly(UNUSED);
        }

        @Test
        void debounceBackgroundBuildsAfterIndexing() {
            var server = TestServer.start();
            server.open("main.nf", "workflow {}\n");
            server.settle();
            var publishes = server.client().getPublishLog().size();

            server.change("main.nf", "workflow { def x = 1 }\n");
            server.runPending();
            server.advance(Engine.DEFAULT_DEBOUNCE.minusMillis(1));
            assertThat(server.client().getPublishLog()).hasSize(publishes);
            server.advance(Duration.ofMillis(1));
            assertThat(server.messages("main.nf")).containsExactly(UNUSED);
        }

        @Test
        void requestBuildsWithoutWaitingForTheDebounce() {
            var server = TestServer.start();
            server.open("main.nf", "workflow {}\n");
            server.settle();
            server.change("main.nf", "workflow { def x = 1 }\n");
            var warnings = server.call(Need.FILE, "main.nf", 0, (context) -> context.project().scriptView().getWarnings(URI.create(server.uri("main.nf"))).size());
            assertThat(warnings).isEqualTo(1);
            assertThat(server.messages("main.nf")).containsExactly(UNUSED);
        }

        @Test
        void yieldToRequestsBetweenSlices() {
            var server = TestServer.start((o) -> {
                o.maxSliceFiles = 10;
                o.initialized = false;
                o.settle = false;
            });
            writeModules(server, 200);
            server.write("main.nf", "workflow {}\n");
            server.initialized();
            runUntilIndexingHasParsed(server);
            var parsesAtAnswer = server.call(Need.FILE, "main.nf", -1, (context) -> {
                assertThat(server.workspace().isIndexing()).as("indexing").isTrue();
                return totalParses(server);
            });
            // at most two slices
            assertThat(parsesAtAnswer).isLessThanOrEqualTo(20);
            server.settle();
            assertThat(server.workspace().isIndexing()).as("indexing").isFalse();
        }

    }

}

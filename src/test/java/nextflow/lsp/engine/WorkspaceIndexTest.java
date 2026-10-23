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
import java.util.List;
import java.util.TreeSet;

import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the workspace scan, indexing progress and background
 * scheduling.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceIndexTest {

    static final String UNUSED = "Variable was declared but not used";

    static List<URI> builtScripts(RequestContext context) {
        return WorkspaceFixture.project(context).builtEntries(FileKind.SCRIPT).stream().map(FileEntry::uri).toList();
    }

    static void writeModules(WorkspaceFixture f, int count) {
        for( int i = 0; i < count; i++ )
            f.write("modules/mod" + i + ".nf", "workflow MOD" + i + " { def x = 1 }\n");
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

    @Test
    void crossFileRequestWaitsForTheFullIndex() {
        var io = new QueuedExecutor();
        try( var f = WorkspaceFixture.start((o) -> {
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.progress = true;
            o.settle = false;
        }) ) {
            f.write("modules/greet/main.nf", "workflow GREET {}\n");
            f.write("main.nf", """
                include { GREET } from './modules/greet/main.nf'
                workflow {
                    GREET()
                }
                """);
            f.runPending();
            f.open("modules/greet/main.nf", "workflow GREET {}\n");
            var progressAtAnswer = new ArrayList<String>();
            var references = f.send(Need.PROJECT, "modules/greet/main.nf", List.<URI>of(), (context) -> {
                progressAtAnswer.addAll(progressKinds(f.client()));
                return builtScripts(context);
            });
            f.runPending();
            assertThat(references).isNotDone();

            f.settle(io);
            assertThat(references.join()).containsExactly(
                URI.create(f.uri("main.nf")),
                URI.create(f.uri("modules/greet/main.nf")));
            assertThat(progressAtAnswer).last().isEqualTo("end");
        }
    }

    @Test
    void endProgressOnlyWhenEveryProjectIsIndexed() {
        var builtAtEnd = new ArrayList<String>();
        var fixture = new WorkspaceFixture[1];
        var client = new TestLanguageClient() {
            @Override
            public void notifyProgress(ProgressParams params) {
                super.notifyProgress(params);
                if( params.getValue().getLeft() instanceof WorkDoneProgressEnd ) {
                    for( var project : fixture[0].workspace().projects() )
                        builtAtEnd.add(project.name() + " " + project.builtCount() + "/" + project.size() + " " + project.index());
                }
            }
        };
        try( var f = WorkspaceFixture.start((o) -> {
            o.client = client;
            o.progress = true;
            o.maxSliceFiles = 3;
            o.folders("a", "b");
            o.initialized = false;
            o.settle = false;
        }) ) {
            fixture[0] = f;
            for( int i = 0; i < 10; i++ ) {
                f.write("a/mod" + i + ".nf", "workflow A" + i + " {}\n");
                f.write("b/mod" + i + ".nf", "workflow B" + i + " {}\n");
            }
            f.initialized();
            f.settle();
            assertThat(progressKinds(client)).first().isEqualTo("create");
            assertThat(progressKinds(client)).element(1).isEqualTo("begin");
            assertThat(progressKinds(client)).contains("report");
            assertThat(progressKinds(client)).last().isEqualTo("end");
            assertThat(progressKinds(client)).filteredOn("end"::equals).hasSize(1);
            assertThat(builtAtEnd).containsExactly("a 10/10 READY", "b 10/10 READY", " 0/0 READY");
        }
    }

    @Test
    void beginProgressOnlyAfterTheTokenIsCreated() {
        var client = new TestLanguageClient();
        client.setHoldProgressCreate(true);
        var io = new QueuedExecutor();
        try( var f = WorkspaceFixture.start((o) -> {
            o.client = client;
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.progress = true;
            o.initialized = false;
            o.settle = false;
        }) ) {
            writeModules(f, 3);
            f.initialized();
            f.runPending();
            assertThat(progressKinds(client)).containsExactly("create");
            client.completeProgressCreate();
            f.runPending();
            assertThat(progressKinds(client)).containsExactly("create", "begin");
            f.settle(io);
            assertThat(progressKinds(client)).startsWith("create", "begin").endsWith("end");

            // a new run gets a new token
            f.write("other/mod.nf", "workflow OTHER {}\n");
            f.addFolder("other");
            f.runPending();
            client.completeProgressCreate();
            f.settle(io);
            var tokens = new TreeSet<String>();
            for( var item : client.getProgress() ) {
                if( item instanceof WorkDoneProgressCreateParams params )
                    tokens.add(params.getToken().getLeft());
            }
            assertThat(tokens).containsExactly("nextflow/index/1", "nextflow/index/2");
        }
    }

    @Test
    void doNotReportProgressWithoutClientSupport() {
        try( var f = WorkspaceFixture.start((o) -> o.settle = false) ) {
            writeModules(f, 3);
            f.settle();
            assertThat(f.client().getProgress()).isEmpty();
        }
    }

    @Test
    void buildOpenFilesFirst() {
        try( var f = WorkspaceFixture.start((o) -> {
            o.maxSliceFiles = 2;
            o.initialized = false;
            o.settle = false;
        }) ) {
            writeModules(f, 10);
            f.open("modules/mod7.nf", "workflow MOD7 { def x = 1 }\n");
            f.initialized();
            f.settle();
            // the first slice holds the open file, and publishes in URI order
            var log = f.client().getPublishLog();
            assertThat(log).hasSize(10);
            assertThat(log.subList(0, 2)).anyMatch(p -> p.getUri().equals(f.uri("modules/mod7.nf")));
            assertThat(log.get(2).getUri()).isEqualTo(f.uri("modules/mod1.nf"));
        }
    }

    @Test
    void keepOpenFilesThatAreNotOnDisk() {
        try( var f = WorkspaceFixture.start((o) -> {
            o.initialized = false;
            o.settle = false;
        }) ) {
            f.write("main.nf", "workflow {}\n");
            f.open("new.nf", "workflow NEW { def x = 1 }\n");
            f.initialized();
            f.settle();
            assertThat(f.messages("new.nf")).containsExactly(UNUSED);
            var scripts = f.call(Need.PROJECT, "main.nf", List.<URI>of(), WorkspaceIndexTest::builtScripts);
            assertThat(scripts).contains(URI.create(f.uri("new.nf")));
        }
    }

    @Test
    void doNotPauseIndexingForTheDebounce() {
        try( var f = WorkspaceFixture.start((o) -> {
            o.maxSliceFiles = 5;
            o.initialized = false;
            o.settle = false;
        }) ) {
            writeModules(f, 50);
            f.open("main.nf", "workflow {}\n");
            f.initialized();
            var executor = f.env().manualExecutor();
            // run the walk and a couple of slices, then edit
            for( int i = 0; i < 6; i++ )
                executor.runNext();
            assertThat(f.workspace().isIndexing()).isTrue();
            f.change("main.nf", "workflow { def x = 1 }\n");
            var now = f.env().fakeClock().instant();
            f.runPending();
            assertThat(f.env().fakeClock().instant()).isEqualTo(now);
            assertThat(f.workspace().isIndexing()).isFalse();
            assertThat(f.engine().isIdle()).isTrue();
            assertThat(f.messages("modules/mod49.nf")).containsExactly(UNUSED);
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void debounceBackgroundBuildsAfterIndexing() {
        try( var f = WorkspaceFixture.start() ) {
            f.open("main.nf", "workflow {}\n");
            f.settle();
            var publishes = f.client().getPublishLog().size();

            f.change("main.nf", "workflow { def x = 1 }\n");
            f.runPending();
            f.advance(Duration.ofMillis(400));
            assertThat(f.client().getPublishLog()).hasSize(publishes);
            f.advance(Duration.ofMillis(100));
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void requestBuildsWithoutWaitingForTheDebounce() {
        try( var f = WorkspaceFixture.start() ) {
            f.open("main.nf", "workflow {}\n");
            f.settle();
            f.change("main.nf", "workflow { def x = 1 }\n");
            var warnings = f.call(Need.FILE, "main.nf", 0, (context) -> WorkspaceFixture.requestEntry(context).warnings().size());
            assertThat(warnings).isEqualTo(1);
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void yieldToRequestsBetweenSlices() {
        try( var f = WorkspaceFixture.start((o) -> {
            o.maxSliceFiles = 10;
            o.initialized = false;
            o.settle = false;
        }) ) {
            writeModules(f, 200);
            f.write("main.nf", "workflow {}\n");
            f.initialized();
            // the walk runs inline, so its result is the next task
            f.env().manualExecutor().runNext();
            f.env().manualExecutor().runNext();
            var buildsAtAnswer = f.call(Need.FILE, "main.nf", -1L, (context) -> {
                assertThat(f.workspace().isIndexing()).isTrue();
                return f.workspace().buildCount();
            });
            assertThat(buildsAtAnswer).isLessThan(3);
            f.settle();
            assertThat(f.workspace().isIndexing()).isFalse();
        }
    }

    @Test
    void loadFileOnDemandBeforeTheScan() {
        try( var f = WorkspaceFixture.start((o) -> {
            o.initialized = false;
        }) ) {
            f.write("main.nf", """
                include { FOO } from './mod.nf'
                workflow {
                    FOO()
                }
                """);
            f.write("mod.nf", "workflow FOO {}\n");
            var scripts = f.call(Need.FILE, "main.nf", List.<URI>of(), WorkspaceIndexTest::builtScripts);
            assertThat(scripts).containsExactly(URI.create(f.uri("main.nf")), URI.create(f.uri("mod.nf")));
        }
    }

    @Test
    void buildEveryConfigForProjectConfigRequests() {
        try( var f = WorkspaceFixture.start((o) -> o.initialized = false) ) {
            f.write("nextflow.config", "includeConfig 'conf/base.config'\n");
            f.write("conf/base.config", "process.cpus = 2\n");
            f.write("conf/other.config", "process.memory = '1 GB'\n");
            f.write("main.nf", "workflow {}\n");
            f.open("main.nf", "workflow {}\n");
            var configs = f.call(Need.PROJECT_CONFIGS, "main.nf", List.<URI>of(), (context) ->
                WorkspaceFixture.project(context).builtEntries(FileKind.CONFIG).stream().map(FileEntry::uri).toList());
            assertThat(configs).isEmpty();

            // configs are known once the scan has found them
            f.initialized();
            f.runPending();
            configs = f.call(Need.PROJECT_CONFIGS, "main.nf", List.<URI>of(), (context) ->
                WorkspaceFixture.project(context).builtEntries(FileKind.CONFIG).stream().map(FileEntry::uri).toList());
            assertThat(configs).hasSize(3);
        }
    }

    @Test
    void returnEmptyValueForUnmanagedFiles() {
        try( var f = WorkspaceFixture.start() ) {
            f.write("work/ab/main.nf", "workflow {}\n");
            f.write("notes.txt", "hello\n");
            assertThat(f.call(Need.FILE, "work/ab/main.nf", "empty", (context) -> "ran")).isEqualTo("empty");
            assertThat(f.call(Need.FILE, "notes.txt", "empty", (context) -> "ran")).isEqualTo("empty");
            var spec = new RequestSpec("test", "untitled:Untitled-1", null, Need.FILE, false);
            assertThat(f.harness().call(spec, "empty", (context) -> "ran")).isEqualTo("empty");
            assertThat(f.client().getAllDiagnostics()).isEmpty();
        }
    }

}

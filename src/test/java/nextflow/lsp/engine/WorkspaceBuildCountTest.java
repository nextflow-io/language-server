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
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import nextflow.lsp.TestWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that count the parses caused by requests and
 * edits, so that redundant work is caught without measuring time.
 *
 * The project has two modules, a subworkflow that includes both,
 * and an entry script that includes the subworkflow.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceBuildCountTest {

    static final String MOD_A = "workflow A { def x = 1 }\n";

    static final String MOD_B = "workflow B {}\n";

    static final List<String> FILES = List.of("mod_a.nf", "mod_b.nf", "sub.nf", "main.nf", "nextflow.config");

    static WorkspaceFixture start() {
        var f = WorkspaceFixture.start((o) -> o.settle = false);
        f.write("mod_a.nf", MOD_A);
        f.write("mod_b.nf", MOD_B);
        f.write("sub.nf", """
            include { A } from './mod_a.nf'
            include { B } from './mod_b.nf'
            workflow SUB { A() ; B() }
            """);
        f.write("main.nf", """
            include { SUB } from './sub.nf'
            workflow { SUB() }
            """);
        f.write("nextflow.config", "process.cpus = 2\n");
        f.settle();
        return f;
    }

    static void requestEverything(WorkspaceFixture f, String path) {
        for( var need : List.of(Need.FILE, Need.PROJECT_CONFIGS, Need.PROJECT) )
            f.call(need, path, null, (context) -> null);
        f.call(Need.ALL, null, null, (context) -> null);
    }

    @Test
    void parseEveryFileOnceWhileIndexing() {
        try( var f = start() ) {
            assertThat(f.parses()).containsOnlyKeys(FILES).allSatisfy((path, count) -> assertThat(count).as(path).isEqualTo(1));
        }
    }

    @Test
    void parseNothingForRequestsOnAFreshProject() {
        try( var f = start() ) {
            var before = f.parses();
            for( int i = 0; i < 3; i++ ) {
                for( var path : FILES )
                    requestEverything(f, path);
            }
            f.settle();
            assertThat(f.parsesSince(before)).isEmpty();
        }
    }

    @Test
    void parseAnEditedModuleOnceAndItsDependentsOnceForProjectRequests() {
        try( var f = start() ) {
            var before = f.parses();
            f.open("mod_a.nf", MOD_A);
            f.call(Need.FILE, "mod_a.nf", null, (context) -> null);
            assertThat(f.parsesSince(before)).isEmpty();

            f.change("mod_a.nf", "workflow A { def y = 1 }\n");
            for( int i = 0; i < 3; i++ )
                f.call(Need.FILE, "mod_a.nf", null, (context) -> null);
            assertThat(f.parsesSince(before)).isEqualTo(Map.of("mod_a.nf", 1));

            for( int i = 0; i < 3; i++ )
                f.call(Need.PROJECT, "mod_a.nf", null, (context) -> null);
            f.settle();
            assertThat(f.parsesSince(before)).isEqualTo(Map.of("mod_a.nf", 1, "sub.nf", 1, "main.nf", 1));
            assertThat(f.includeTarget("sub.nf")).isEqualTo(URI.create(f.uri("mod_a.nf")));
        }
    }

    @Test
    void stopBuildingBetweenSlicesWhenRequestIsCancelled() {
        var cancelOnRead = new AtomicReference<CompletableFuture<?>>();
        var files = new FileAccess() {
            @Override
            public String readText(Path path) throws IOException {
                var request = cancelOnRead.getAndSet(null);
                if( request != null )
                    request.cancel(true);
                return FileAccess.system().readText(path);
            }

            @Override
            public boolean exists(Path path) {
                return FileAccess.system().exists(path);
            }

            @Override
            public List<Path> walk(Path root, Predicate<Path> excluded, Predicate<Path> matcher) throws IOException {
                return FileAccess.system().walk(root, excluded, matcher);
            }
        };
        try( var f = WorkspaceFixture.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.maxSliceFiles = 1;
            o.settle = false;
        }) ) {
            var main = new StringBuilder();
            for( int i = 0; i < 5; i++ ) {
                f.write("mod" + i + ".nf", "workflow M" + i + " {}\n");
                main.append("include { M" + i + " } from './mod" + i + ".nf'\n");
            }
            main.append("workflow { M0() }\n");
            f.write("main.nf", main.toString());
            f.settle();
            var before = f.parses();

            for( int i = 0; i < 5; i++ )
                f.writeAndNotify("mod" + i + ".nf", "workflow M" + i + " { def x = 1 }\n");
            var request = f.send(Need.FILE, "main.nf", "empty", (context) -> "done");
            cancelOnRead.set(request);
            f.runPending();

            // the slice in progress is committed, and the rest is left stale
            assertThat(request).isCancelled();
            assertThat(f.parsesSince(before)).hasSize(1);

            f.settle();
            assertThat(f.parsesSince(before))
                .hasSize(6)
                .allSatisfy((path, count) -> assertThat(count).as(path).isEqualTo(1));
            assertThat(f.messages("main.nf")).isEmpty();
            assertThat(f.messages("mod4.nf")).containsExactly(WorkspaceEventsTest.UNUSED);
        }
    }

}

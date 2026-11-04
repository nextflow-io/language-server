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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.InitializedParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static nextflow.lsp.TestUtils.UNUSED;
import static nextflow.lsp.TestUtils.thrownOnAnotherThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies how workspace folders route files to projects: nested
 * folders, folder changes, ownership of shared dependencies, the
 * default project and shutdown. Configuration changes and watched
 * file events are specified by WorkspaceConfigurationTest and
 * WorkspaceWatchedFilesTest.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceEventsTest {

    static final String WITH_WARNING = "workflow { def x = 1 }\n";

    @ParameterizedTest
    @ValueSource(strings = { "apply", "route", "runSlice" })
    void rejectWorkspaceAccessFromOtherThreads(String method) throws Exception {
        var server = TestServer.start();
        var workspace = server.workspace();
        var spec = new RequestSpec("test", server.uri("main.nf"), null, Need.FILE, false);
        Runnable access = switch( method ) {
            case "apply" -> () -> workspace.apply(new Event.Initialized(new InitializedParams()));
            case "route" -> () -> workspace.route(spec);
            default -> () -> workspace.runSlice();
        };
        assertThat(thrownOnAnotherThread(access)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routeToTheInnermostFolderBySegment() {
        var server = TestServer.start((o) -> o.folders("a", "a/inner", "a/foo"));
        var workspace = server.workspace();
        assertThat(workspace.projectFor(server.path("a/main.nf")).name).isEqualTo("a");
        assertThat(workspace.projectFor(server.path("a/inner/main.nf")).name).isEqualTo("a/inner");
        assertThat(workspace.projectFor(server.path("a/foo/main.nf")).name).isEqualTo("a/foo");
        assertThat(workspace.projectFor(server.path("a/foobar/main.nf")).name).isEqualTo("a");
        assertThat(workspace.projectFor(server.path("b/main.nf"))).matches(Project::isDefault, "is the default project");
    }

    @Test
    void buildFilesOfNestedFoldersInTheInnermostFolderAndDependenciesInBoth() {
        var server = TestServer.start((o) -> {
            o.folders("", "sub");
            o.settle = false;
        });
        server.write("sub/a.nf", "workflow A { def x = 1 }\n");
        server.write("sub/b.nf", WITH_WARNING);
        server.write("main.nf", """
            include { A } from './sub/a.nf'
            workflow {
                A()
            }
            """);
        server.settle();
        // the outer folder loads only what its own files include
        assertThat(server.parses()).isEqualTo(Map.of("main.nf", 1, "sub/a.nf", 2, "sub/b.nf", 1));
        assertThat(server.publishes("sub/a.nf")).hasSize(1);
        assertThat(server.messages("sub/b.nf")).containsExactly(UNUSED);
        assertThat(server.messages("main.nf")).isEmpty();
    }

    @Test
    void moveFilesBetweenNestedFoldersWhenTheInnerFolderChanges() {
        var server = TestServer.start((o) -> o.settle = false);
        server.write("sub/a.nf", WITH_WARNING);
        server.write("sub/b.nf", WITH_WARNING);
        server.open("sub/b.nf", WITH_WARNING);
        server.settle();
        assertThat(server.parses()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));

        // each file is held by one folder at a time, and is built once
        // by the folder it moves to
        var before = server.parses();
        server.addFolder("sub");
        server.settle();
        assertThat(server.entries()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(server.messages("sub/a.nf")).containsExactly(UNUSED);
        assertThat(server.messages("sub/b.nf")).containsExactly(UNUSED);

        before = server.parses();
        server.removeFolder("sub");
        server.settle();
        assertThat(server.entries()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(server.messages("sub/a.nf")).containsExactly(UNUSED);
        assertThat(server.messages("sub/b.nf")).containsExactly(UNUSED);
        assertThat(server.publishes("sub/a.nf")).noneMatch(List::isEmpty);

        // the outer folder serves the files again
        server.writeAndNotify("sub/a.nf", "workflow {\n");
        server.settle();
        assertThat(server.messages("sub/a.nf")).hasSize(1).doesNotContain(UNUSED);
    }

    @Test
    void moveOpenDocumentToAnAddedFolder() {
        var server = TestServer.start((o) -> o.folders());
        server.open("a/main.nf", WITH_WARNING);
        server.settle();
        assertThat(server.messages("a/main.nf")).containsExactly(UNUSED);

        server.write("a/main.nf", WITH_WARNING);
        var before = server.parses();
        server.addFolder("a");
        server.settle();
        var path = server.path("a/main.nf");
        assertThat(server.workspace().projectFor(path).name).isEqualTo("a");
        assertThat(server.entries()).isEqualTo(Map.of("a/main.nf", 1));
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("a/main.nf", 1));
        assertThat(server.publishes("a/main.nf")).noneMatch(List::isEmpty);
        assertThat(server.messages("a/main.nf")).containsExactly(UNUSED);
    }

    @Test
    void moveExcludedOpenDocumentToAnAddedFolder() {
        var server = TestServer.start((o) -> o.folders());
        server.open("a/work/x.nf", WITH_WARNING);
        server.settle();
        assertThat(server.messages("a/work/x.nf")).containsExactly(UNUSED);

        var before = server.parses();
        server.addFolder("a");
        server.settle();
        assertThat(server.entries()).isEqualTo(Map.of("a/work/x.nf", 1));
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("a/work/x.nf", 1));
        server.change("a/work/x.nf", "workflow {\n");
        server.settle();
        assertThat(server.messages("a/work/x.nf")).hasSize(1).doesNotContain(UNUSED);
    }

    @Test
    void publishOpenExcludedFileOnlyWhileItIsOpen() {
        var server = TestServer.start((o) -> o.settle = false);
        server.write("work/mod.nf", "workflow FOO { def x = 1 }\n");
        server.write("main.nf", """
            include { FOO } from './work/mod.nf'
            workflow {
                FOO()
            }
            """);
        server.settle();
        // an included excluded file is loaded but not published
        assertThat(server.includeTarget("main.nf")).isEqualTo(server.path("work/mod.nf").toUri());
        assertThat(server.publishes("work/mod.nf")).isEmpty();

        server.open("work/mod.nf", "workflow FOO { def x = 1 }\n");
        server.settle();
        assertThat(server.messages("work/mod.nf")).containsExactly(UNUSED);
        assertThat(server.call(Need.FILE, "work/mod.nf", "empty", (context) -> "ran")).isEqualTo("ran");

        server.close("work/mod.nf");
        server.settle();
        assertThat(server.messages("work/mod.nf")).isEmpty();
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.includeTarget("main.nf")).isEqualTo(server.path("work/mod.nf").toUri());
    }

    @Test
    void clearDiagnosticsOfRemovedFolder() {
        var server = TestServer.start((o) -> {
            o.folders("a", "b");
            o.settle = false;
        });
        server.write("a/x.nf", WITH_WARNING);
        server.write("a/y.nf", "workflow {\n");
        server.write("a/nextflow.config", "process {\n");
        server.write("b/z.nf", WITH_WARNING);
        server.open("a/open.nf", WITH_WARNING);
        server.settle();
        assertThat(server.messages("a/x.nf")).containsExactly(UNUSED);
        assertThat(server.messages("a/y.nf")).isNotEmpty();
        assertThat(server.messages("a/nextflow.config")).isNotEmpty();

        server.removeFolder("a");
        server.settle();
        assertThat(server.messages("a/x.nf")).isEmpty();
        assertThat(server.messages("a/y.nf")).isEmpty();
        assertThat(server.messages("a/nextflow.config")).isEmpty();
        assertThat(server.publishes("a/x.nf")).hasSize(2);
        // the open document moves to the default project without flicker
        assertThat(server.publishes("a/open.nf")).noneMatch(List::isEmpty);
        assertThat(server.messages("a/open.nf")).containsExactly(UNUSED);
        assertThat(server.messages("b/z.nf")).containsExactly(UNUSED);
        assertThat(server.workspace().projectFor(server.path("a/x.nf"))).matches(Project::isDefault, "is the default project");
        assertThat(server.engine().isIdle()).as("idle").isTrue();
    }

    @Test
    void publishSharedDependencyOnlyFromItsOwner() {
        var server = TestServer.start((o) -> {
            o.folders("a", "b");
            o.settle = false;
        });
        server.write("b/x.nf", "workflow X { def x = 1 }\n");
        server.write("a/main.nf", """
            include { X } from '../b/x.nf'
            workflow {
                X()
            }
            """);
        server.settle();
        assertThat(server.publishes("b/x.nf")).hasSize(1);
        assertThat(server.messages("b/x.nf")).containsExactly(UNUSED);
        // both folders load it
        assertThat(server.parses()).isEqualTo(Map.of("a/main.nf", 1, "b/x.nf", 2));

        // the dependency is still published after its owner goes away
        server.removeFolder("b");
        server.settle();
        assertThat(server.messages("b/x.nf")).containsExactly(UNUSED);
        assertThat(server.publishes("b/x.nf")).noneMatch(List::isEmpty);
    }

    @Test
    void publishNothingAfterShutdown() {
        var io = new QueuedExecutor();
        var server = TestServer.start((o) -> {
            o.folders("a");
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.settle = false;
        });
        server.runPending();
        // background work runs without delay while the folder is indexing
        server.open("outside.nf", WITH_WARNING);
        var shutdown = server.engine().shutdown();
        server.runPending();
        assertThat(shutdown).isDone();
        assertThat(server.client().getPublishLog()).isEmpty();
    }

    @Test
    void dropClosedFileFromTheDefaultProject() {
        var server = TestServer.start((o) -> o.folders());
        server.write("outside/main.nf", WITH_WARNING);
        server.open("outside/main.nf", WITH_WARNING);
        server.settle();
        assertThat(server.messages("outside/main.nf")).containsExactly(UNUSED);

        server.close("outside/main.nf");
        server.settle();
        assertThat(server.messages("outside/main.nf")).isEmpty();
        assertThat(server.entries()).isEmpty();
    }

    @Test
    void keepClosedFileInTheDefaultProjectWhileItIsIncluded() {
        var server = TestServer.start((o) -> o.folders());
        server.write("outside/mod.nf", "workflow FOO { def x = 1 }\n");
        server.open("outside/mod.nf", "workflow FOO { def x = 1 }\n");
        server.open("outside/main.nf", """
            include { FOO } from './mod.nf'
            workflow {
                FOO()
            }
            """);
        server.settle();
        server.close("outside/mod.nf");
        server.settle();
        assertThat(server.messages("outside/mod.nf")).containsExactly(UNUSED);
        assertThat(server.messages("outside/main.nf")).isEmpty();

        // once nothing includes it, it is dropped
        server.close("outside/main.nf");
        server.settle();
        assertThat(server.messages("outside/mod.nf")).isEmpty();
        assertThat(server.entries()).isEmpty();
    }

    /**
     * A dependency that the project doesn't own is dropped once nothing
     * includes it, whether it is outside the folder, in a nested
     * folder or excluded, cyclic or not, and open or not.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        proj   | proj/main.nf | ../shared/a.nf | false | true  | true
        ',sub' | main.nf      | ./sub/a.nf     | false | true  | false
        proj   | proj/main.nf | ../shared/a.nf | true  | false | true
        ''     | main.nf      | ./vendor/a.nf  | true  | false | false
        """)
    void dropDependenciesWhenNoLongerIncluded(String folders, String main, String include, boolean cyclic, boolean open, boolean outside) {
        var server = TestServer.start((o) -> {
            o.folders(folders.split(",", -1));
            o.settle = false;
        });
        var dep = Path.of(main).resolveSibling(include).normalize().toString();
        var dir = Path.of(dep).getParent().toString();
        // the last case's dependencies are excluded from its folder
        if( dir.equals("vendor") )
            server.configure(Map.of("nextflow.files.exclude", List.of("vendor")));
        if( cyclic ) {
            server.write(dep, """
                include { B } from './b.nf'
                workflow A { def x = 1 }
                """);
            server.write(dir + "/b.nf", """
                include { A } from './a.nf'
                workflow B {}
                """);
        }
        else {
            server.write(dep, "workflow A { def x = 1 }\n");
        }
        server.write(main, """
            include { A } from '%s'
            workflow {
                A()
            }
            """.formatted(include));
        if( open )
            server.open(dep, "workflow A { def x = 1 }\n");
        server.settle();
        var deps = cyclic ? List.of(main, dep, dir + "/b.nf") : List.of(main, dep);
        assertThat(scriptPaths(server, main)).containsExactlyInAnyOrderElementsOf(deps);
        if( outside )
            assertThat(server.messages(dep)).contains(UNUSED);

        server.writeAndNotify(main, "workflow {}\n");
        server.settle();
        if( open ) {
            server.close(dep);
            server.settle();
        }
        assertThat(scriptPaths(server, main)).containsExactly(main);
        if( outside )
            assertThat(server.messages(dep)).isEmpty();
    }

    private static List<String> scriptPaths(TestServer server, String path) {
        var root = server.path("");
        return server.call(Need.FILE, path, List.<String>of(), (context) ->
            context.project().scriptView().getUris().stream()
                .map(uri -> root.relativize(Path.of(uri)).toString())
                .sorted()
                .toList());
    }

}

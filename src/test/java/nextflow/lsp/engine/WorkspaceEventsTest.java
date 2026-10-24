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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.ast.ProjectView;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WatchKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for workspace events: folders, routing, ownership,
 * configuration changes and the default project.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceEventsTest {

    static final String UNUSED = "Variable was declared but not used";

    static final String WITH_WARNING = "workflow { def x = 1 }\n";

    @Test
    void rejectWorkspaceAccessFromOtherThreads() throws Exception {
        try( var f = TestServer.start() ) {
            var workspace = f.workspace();
            var spec = new RequestSpec("test", f.uri("main.nf"), null, Need.FILE, false);
            var errors = new ArrayList<Throwable>();
            var thread = new Thread(() -> {
                for( Runnable access : List.<Runnable>of(
                    () -> workspace.apply(new Event.Initialized(new InitializedParams())),
                    () -> workspace.route(spec),
                    () -> workspace.runSlice()
                ) ) {
                    try {
                        access.run();
                    }
                    catch( Throwable e ) {
                        errors.add(e);
                    }
                }
            });
            thread.start();
            thread.join();
            assertThat(errors).hasSize(3).allMatch(e -> e instanceof IllegalStateException);
        }
    }

    @Test
    void routeToTheInnermostFolderBySegment() {
        try( var f = TestServer.start((o) -> o.folders("a", "a/inner", "a/foo")) ) {
            var workspace = f.workspace();
            assertThat(workspace.projectFor(f.path("a/main.nf")).name()).isEqualTo("a");
            assertThat(workspace.projectFor(f.path("a/inner/main.nf")).name()).isEqualTo("a/inner");
            assertThat(workspace.projectFor(f.path("a/foo/main.nf")).name()).isEqualTo("a/foo");
            assertThat(workspace.projectFor(f.path("a/foobar/main.nf")).name()).isEqualTo("a");
            assertThat(workspace.projectFor(f.path("b/main.nf")).isDefault()).isTrue();
        }
    }

    @Test
    void buildFilesOfNestedFoldersOnlyInTheInnermostFolder() {
        try( var f = TestServer.start((o) -> {
            o.folders("", "sub");
            o.settle = false;
        }) ) {
            f.write("sub/a.nf", "workflow A { def x = 1 }\n");
            f.write("sub/b.nf", WITH_WARNING);
            f.write("main.nf", """
                include { A } from './sub/a.nf'
                workflow {
                    A()
                }
                """);
            f.settle();
            // the outer folder loads only what its own files include
            assertThat(f.parses()).isEqualTo(Map.of("main.nf", 1, "sub/a.nf", 2, "sub/b.nf", 1));
            assertThat(f.publishes("sub/a.nf")).hasSize(1);
            assertThat(f.messages("sub/b.nf")).containsExactly(UNUSED);
            assertThat(f.messages("main.nf")).isEmpty();
        }
    }

    @Test
    void moveFilesBetweenNestedFoldersWhenTheInnerFolderChanges() {
        try( var f = TestServer.start((o) -> o.settle = false) ) {
            f.write("sub/a.nf", WITH_WARNING);
            f.write("sub/b.nf", WITH_WARNING);
            f.open("sub/b.nf", WITH_WARNING);
            f.settle();
            assertThat(f.parses()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));

            // each file is held by one folder at a time
            f.addFolder("sub");
            f.settle();
            assertThat(f.parses()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
            assertThat(f.messages("sub/a.nf")).containsExactly(UNUSED);
            assertThat(f.messages("sub/b.nf")).containsExactly(UNUSED);

            f.removeFolder("sub");
            f.settle();
            assertThat(f.parses()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
            assertThat(f.messages("sub/a.nf")).containsExactly(UNUSED);
            assertThat(f.messages("sub/b.nf")).containsExactly(UNUSED);
            assertThat(f.publishes("sub/a.nf")).noneMatch(List::isEmpty);

            // the outer folder serves the files again
            f.writeAndNotify("sub/a.nf", "workflow {\n");
            f.settle();
            assertThat(f.messages("sub/a.nf")).hasSize(1).doesNotContain(UNUSED);
        }
    }

    @Test
    void moveOpenDocumentToAnAddedFolder() {
        try( var f = TestServer.start((o) -> o.folders()) ) {
            f.open("a/main.nf", WITH_WARNING);
            f.settle();
            assertThat(f.messages("a/main.nf")).containsExactly(UNUSED);

            f.write("a/main.nf", WITH_WARNING);
            f.addFolder("a");
            f.settle();
            var path = f.path("a/main.nf");
            assertThat(f.workspace().projectFor(path).name()).isEqualTo("a");
            assertThat(f.parses()).isEqualTo(Map.of("a/main.nf", 1));
            assertThat(f.publishes("a/main.nf")).noneMatch(List::isEmpty);
            assertThat(f.messages("a/main.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void moveExcludedOpenDocumentToAnAddedFolder() {
        try( var f = TestServer.start((o) -> o.folders()) ) {
            f.open("a/work/x.nf", WITH_WARNING);
            f.settle();
            assertThat(f.messages("a/work/x.nf")).containsExactly(UNUSED);

            f.addFolder("a");
            f.settle();
            assertThat(f.parses()).isEqualTo(Map.of("a/work/x.nf", 1));
            f.change("a/work/x.nf", "workflow {\n");
            f.settle();
            assertThat(f.messages("a/work/x.nf")).hasSize(1).doesNotContain(UNUSED);
        }
    }

    @Test
    void publishOpenExcludedFileOnlyWhileItIsOpen() {
        try( var f = TestServer.start((o) -> o.settle = false) ) {
            f.write("work/mod.nf", "workflow FOO { def x = 1 }\n");
            f.write("main.nf", """
                include { FOO } from './work/mod.nf'
                workflow {
                    FOO()
                }
                """);
            f.settle();
            // an included excluded file is loaded but not published
            assertThat(f.includeTarget("main.nf")).isEqualTo(f.path("work/mod.nf").toUri());
            assertThat(f.publishes("work/mod.nf")).isEmpty();

            f.open("work/mod.nf", "workflow FOO { def x = 1 }\n");
            f.settle();
            assertThat(f.messages("work/mod.nf")).containsExactly(UNUSED);
            assertThat(f.call(Need.FILE, "work/mod.nf", "empty", (context) -> "ran")).isEqualTo("ran");

            f.close("work/mod.nf");
            f.settle();
            assertThat(f.messages("work/mod.nf")).isEmpty();
            assertThat(f.messages("main.nf")).isEmpty();
            assertThat(f.includeTarget("main.nf")).isEqualTo(f.path("work/mod.nf").toUri());
        }
    }

    @Test
    void clearDiagnosticsOfRemovedFolder() {
        try( var f = TestServer.start((o) -> {
            o.folders("a", "b");
            o.settle = false;
        }) ) {
            f.write("a/x.nf", WITH_WARNING);
            f.write("a/y.nf", WITH_WARNING);
            f.write("b/z.nf", WITH_WARNING);
            f.open("a/open.nf", WITH_WARNING);
            f.settle();
            assertThat(f.messages("a/x.nf")).containsExactly(UNUSED);

            f.removeFolder("a");
            f.settle();
            assertThat(f.messages("a/x.nf")).isEmpty();
            assertThat(f.messages("a/y.nf")).isEmpty();
            assertThat(f.publishes("a/x.nf")).hasSize(2);
            // the open document moves to the default project without flicker
            assertThat(f.publishes("a/open.nf")).noneMatch(List::isEmpty);
            assertThat(f.messages("a/open.nf")).containsExactly(UNUSED);
            assertThat(f.messages("b/z.nf")).containsExactly(UNUSED);
            assertThat(f.workspace().projects()).extracting(Project::name).containsExactly("b", "");
            assertThat(f.engine().isIdle()).isTrue();
        }
    }

    @Test
    void publishSharedDependencyOnlyFromItsOwner() {
        try( var f = TestServer.start((o) -> {
            o.folders("a", "b");
            o.settle = false;
        }) ) {
            f.write("b/x.nf", "workflow X { def x = 1 }\n");
            f.write("a/main.nf", """
                include { X } from '../b/x.nf'
                workflow {
                    X()
                }
                """);
            f.settle();
            assertThat(f.publishes("b/x.nf")).hasSize(1);
            assertThat(f.messages("b/x.nf")).containsExactly(UNUSED);
            // both folders load it
            assertThat(f.parses()).isEqualTo(Map.of("a/main.nf", 1, "b/x.nf", 2));

            // the dependency is still published after its owner goes away
            f.removeFolder("b");
            f.settle();
            assertThat(f.messages("b/x.nf")).containsExactly(UNUSED);
            assertThat(f.publishes("b/x.nf")).noneMatch(List::isEmpty);
        }
    }

    @Test
    void republishWithoutRebuildingWhenErrorReportingModeChanges() {
        try( var f = TestServer.start((o) -> o.settle = false) ) {
            f.write("main.nf", WITH_WARNING);
            f.write("bad.nf", "workflow {\n");
            f.settle();
            var before = f.parses();
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);

            f.configure(Map.of("nextflow.errorReportingMode", "errors"));
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();
            assertThat(f.messages("bad.nf")).isNotEmpty();

            f.configure(Map.of("nextflow.errorReportingMode", "warnings"));
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);
            assertThat(f.parsesSince(before)).isEmpty();
        }
    }

    @Test
    void doNotFlickerDiagnosticsOnConfigurationChange() {
        try( var f = TestServer.start((o) -> o.settle = false) ) {
            f.write("main.nf", WITH_WARNING);
            f.write("nextflow.config", "foo.bar = 1\n");
            f.settle();
            f.client().resetDiagnostics();

            f.configure(Map.of(
                "nextflow.files.exclude", List.of(".git", "work", "other"),
                "nextflow.formatting.harshilAlignment", true));
            f.settle();
            f.configure(Map.of("nextflow.files.exclude", List.of(".git", "work")));
            f.settle();
            assertThat(f.client().getPublishLog()).isEmpty();
            assertThat(f.workspace().configuration().harshilAlignment()).isTrue();
        }
    }

    @Test
    void applyExcludeChangesIncrementally() {
        try( var f = TestServer.start((o) -> o.settle = false) ) {
            f.write("main.nf", WITH_WARNING);
            f.write("sub/x.nf", WITH_WARNING);
            f.write("tests/y.nf", WITH_WARNING);
            f.configure(Map.of("nextflow.files.exclude", List.of("tests")));
            f.settle();
            assertThat(f.messages("sub/x.nf")).containsExactly(UNUSED);
            assertThat(f.publishes("tests/y.nf")).isEmpty();

            f.configure(Map.of("nextflow.files.exclude", List.of("sub")));
            f.settle();
            assertThat(f.publishes("sub/x.nf")).containsExactly(f.diagnostics("main.nf"), List.of());
            assertThat(f.messages("tests/y.nf")).containsExactly(UNUSED);
            assertThat(f.publishes("main.nf")).hasSize(1);
            assertThat(f.call(Need.FILE, "sub/x.nf", "empty", (context) -> "ran")).isEqualTo("empty");
        }
    }

    @Test
    void ignoreTheResultOfASupersededWalk() {
        var io = new QueuedExecutor();
        try( var f = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.initialized = false;
            o.settle = false;
        }) ) {
            f.write("main.nf", WITH_WARNING);
            f.write("tests/y.nf", WITH_WARNING);
            f.configure(Map.of("nextflow.files.exclude", List.of("tests")));
            f.initialized();
            f.runPending();
            f.configure(Map.of("nextflow.files.exclude", List.of(".git", "work")));
            f.runPending();
            assertThat(io.size()).isEqualTo(2);

            // the first walk, which excludes tests/, completes last
            io.runLast();
            f.runPending();
            f.settle(io);
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);
            assertThat(f.messages("tests/y.nf")).containsExactly(UNUSED);
            assertThat(f.publishes("tests/y.nf")).hasSize(1);
        }
    }

    @Test
    void publishNothingAfterShutdown() {
        var io = new QueuedExecutor();
        try( var f = TestServer.start((o) -> {
            o.folders("a");
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.settle = false;
        }) ) {
            f.runPending();
            // background work runs without delay while the folder is indexing
            f.open("outside.nf", WITH_WARNING);
            var shutdown = f.engine().shutdown();
            f.runPending();
            assertThat(shutdown).isDone();
            assertThat(f.client().getPublishLog()).isEmpty();
        }
    }

    @Test
    void ignoreInvalidSettingsButApplyTheOthers() {
        try( var f = TestServer.start() ) {
            f.configure(Map.of(
                "nextflow.errorReportingMode", "bogus",
                "nextflow.completion.maxItems", "many",
                "nextflow.dag.direction", "LR"));
            f.settle();
            var configuration = f.workspace().configuration();
            assertThat(configuration.errorReportingMode()).isEqualTo(nextflow.lsp.services.ErrorReportingMode.WARNINGS);
            assertThat(configuration.maxCompletionItems()).isEqualTo(100);
            assertThat(configuration.dagDirection()).isEqualTo("LR");
            assertThat(f.client().getLogMessages())
                .filteredOn(m -> m.getType() == MessageType.Warning)
                .extracting(m -> m.getMessage())
                .anyMatch(m -> m.contains("nextflow.errorReportingMode"))
                .anyMatch(m -> m.contains("nextflow.completion.maxItems"));
        }
    }

    @Test
    void ignoreInvalidRegistryUrlButApplyTheOtherSettings() {
        try( var f = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry(HttpPluginRegistryClient::new);
            o.settle = false;
        }) ) {
            f.write("main.nf", WITH_WARNING);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);

            var registryUrl = f.workspace().configuration().pluginRegistryUrl();
            f.configure(Map.of(
                "nextflow.pluginRegistryUrl", "http://bad url/",
                "nextflow.errorReportingMode", "off"));
            f.settle();
            assertThat(f.workspace().configuration().pluginRegistryUrl()).isEqualTo(registryUrl);
            assertThat(f.messages("main.nf")).isEmpty();
            assertThat(f.client().getLogMessages())
                .filteredOn(m -> m.getType() == MessageType.Warning)
                .extracting(m -> m.getMessage())
                .anyMatch(m -> m.contains("nextflow.pluginRegistryUrl"));
        }
    }

    @Test
    void dropClosedFileFromTheDefaultProject() {
        try( var f = TestServer.start((o) -> o.folders()) ) {
            f.write("outside/main.nf", WITH_WARNING);
            f.open("outside/main.nf", WITH_WARNING);
            f.settle();
            assertThat(f.messages("outside/main.nf")).containsExactly(UNUSED);

            f.close("outside/main.nf");
            f.settle();
            assertThat(f.messages("outside/main.nf")).isEmpty();
            assertThat(f.parses()).isEmpty();
        }
    }

    @Test
    void keepClosedFileInTheDefaultProjectWhileItIsIncluded() {
        try( var f = TestServer.start((o) -> o.folders()) ) {
            f.write("outside/mod.nf", "workflow FOO { def x = 1 }\n");
            f.open("outside/mod.nf", "workflow FOO { def x = 1 }\n");
            f.open("outside/main.nf", """
                include { FOO } from './mod.nf'
                workflow {
                    FOO()
                }
                """);
            f.settle();
            f.close("outside/mod.nf");
            f.settle();
            assertThat(f.messages("outside/mod.nf")).containsExactly(UNUSED);
            assertThat(f.messages("outside/main.nf")).isEmpty();

            // once nothing includes it, it is dropped
            f.close("outside/main.nf");
            f.settle();
            assertThat(f.messages("outside/mod.nf")).isEmpty();
            assertThat(f.parses()).isEmpty();
        }
    }

    @Test
    void pickUpNewFilesFromWatcherEvents() {
        try( var f = TestServer.start((o) -> o.watchers = true) ) {
            assertThat(f.client().getRegistrations()).singleElement()
                .satisfies(r -> assertThat(r.getRegistrations().get(0).getMethod()).isEqualTo("workspace/didChangeWatchedFiles"));

            f.writeAndNotify("new.nf", WITH_WARNING);
            f.settle();
            assertThat(f.messages("new.nf")).containsExactly(UNUSED);

            f.delete("new.nf");
            f.settle();
            assertThat(f.messages("new.nf")).isEmpty();
            assertThat(f.parses()).isEmpty();
        }
    }

    @Test
    void deleteEveryFileOfADeletedDirectory() throws IOException {
        try( var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        }) ) {
            f.write("modules/a.nf", "workflow A { def x = 1 }\n");
            f.write("modules/sub/b.nf", WITH_WARNING);
            f.write("main.nf", """
                include { A } from './modules/a.nf'
                workflow {
                    A()
                }
                """);
            f.settle();
            assertThat(f.messages("modules/a.nf")).containsExactly(UNUSED);

            // the client reports only the directory
            try( var paths = Files.walk(f.path("modules")) ) {
                for( var path : paths.sorted(Comparator.reverseOrder()).toList() )
                    Files.delete(path);
            }
            f.notify("modules", FileChangeType.Deleted);
            f.settle();
            assertThat(f.messages("modules/a.nf")).isEmpty();
            assertThat(f.messages("modules/sub/b.nf")).isEmpty();
            assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
            assertThat(f.parses()).containsOnlyKeys("main.nf");
        }
    }

    /**
     * Send a watcher event only if a registered watcher matches it,
     * like a client does. Globs are matched against the path relative
     * to the workspace root.
     */
    private static void notifyIfWatched(TestServer f, String path, FileChangeType type) {
        var kind = switch( type ) {
            case Created -> WatchKind.Create;
            case Changed -> WatchKind.Change;
            case Deleted -> WatchKind.Delete;
        };
        var watched = f.client().getRegistrations().stream()
            .flatMap(params -> params.getRegistrations().stream())
            .flatMap(registration -> ((DidChangeWatchedFilesRegistrationOptions) registration.getRegisterOptions()).getWatchers().stream())
            .anyMatch(watcher -> {
                var watcherKind = watcher.getKind() != null ? watcher.getKind() : 7;
                var matcher = FileSystems.getDefault().getPathMatcher("glob:" + watcher.getGlobPattern().getLeft());
                return (watcherKind & kind) != 0 && matcher.matches(Path.of(path));
            });
        if( watched )
            f.notify(path, type);
    }

    @Test
    void discoverTheFilesOfAMovedDirectory() throws IOException {
        try( var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        }) ) {
            f.write("modules/local/a.nf", "workflow A { def x = 1 }\n");
            f.write("main.nf", """
                include { A } from './modules/local/a.nf'
                workflow {
                    A()
                }
                """);
            f.settle();
            assertThat(f.messages("modules/local/a.nf")).containsExactly(UNUSED);

            // the client reports only the directories, and not the files under them
            Files.move(f.path("modules/local"), f.path("modules/shared"));
            notifyIfWatched(f, "modules/local", FileChangeType.Deleted);
            notifyIfWatched(f, "modules/shared", FileChangeType.Created);
            f.settle();
            assertThat(f.messages("modules/local/a.nf")).isEmpty();
            assertThat(f.messages("modules/shared/a.nf")).containsExactly(UNUSED);
            assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        }
    }

    @Test
    void discoverTheFilesOfACreatedDirectory() {
        try( var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        }) ) {
            f.write("main.nf", """
                include { A } from './modules/a.nf'
                workflow {
                    A()
                }
                """);
            f.settle();
            assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

            // the client reports only the directory
            f.write("modules/a.nf", "workflow A { def x = 1 }\n");
            f.write("modules/sub/b.nf", WITH_WARNING);
            f.write("modules/work/c.nf", WITH_WARNING);
            f.notify("modules", FileChangeType.Created);
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();
            assertThat(f.messages("modules/a.nf")).containsExactly(UNUSED);
            assertThat(f.messages("modules/sub/b.nf")).containsExactly(UNUSED);
            assertThat(f.publishes("modules/work/c.nf")).isEmpty();
        }
    }

    @Test
    void resolveADirectoryIncludeWhenItsMainScriptIsCreated() {
        try( var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        }) ) {
            f.write("main.nf", "include { FOO } from './modules/foo'\nworkflow { FOO() }\n");
            f.settle();
            assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

            f.writeAndNotify("modules/foo/main.nf", "workflow FOO {}\n");
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();
            assertThat(f.includeTarget("main.nf")).hasToString(f.uri("modules/foo/main.nf"));
        }
    }

    @Test
    void resolveAnIncludeToADirectoryCreatedNextToTheScript() {
        try( var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        }) ) {
            f.write("foo.nf", "workflow FOO {}\n");
            f.write("main.nf", "include { FOO } from './foo'\nworkflow { FOO() }\n");
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();

            // the directory takes precedence over foo.nf
            f.writeAndNotify("foo/main.nf", "workflow BAR {}\n");
            f.settle();
            assertThat(f.messages("main.nf")).anyMatch(m -> m.contains("'FOO' is not defined"));
            assertThat(WorkspaceBuildTest.nonEmpty(f.client().getAllDiagnostics()))
                .isEqualTo(WorkspaceBuildTest.freshDiagnostics(Map.of()))
                .isNotEmpty();
        }
    }

    @Test
    void resolveARemoteModuleWhenItIsInstalled() {
        try( var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        }) ) {
            f.write("main.nf", "include { FOO } from 'nf-core/foo'\nworkflow { FOO() }\n");
            f.settle();
            assertThat(f.messages("main.nf")).isNotEmpty();

            f.writeAndNotify("modules/nf-core/foo/main.nf", "workflow FOO {}\n");
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();
        }
    }

    @Test
    void doNotRegisterWatchersWithoutClientSupport() {
        try( var f = TestServer.start() ) {
            assertThat(f.client().getRegistrations()).isEmpty();
        }
    }

    @Test
    void rejectProjectAccessOutsideTheEngineThread() throws Exception {
        try( var f = TestServer.start() ) {
            var view = f.call(Need.FILE, "main.nf", (ProjectView) null, RequestContext::project);
            var error = new AtomicReference<Throwable>();
            var thread = new Thread(() -> {
                try {
                    view.name();
                }
                catch( Throwable e ) {
                    error.set(e);
                }
            });
            thread.start();
            thread.join();
            assertThat(error.get()).isInstanceOf(IllegalStateException.class);
        }
    }

}

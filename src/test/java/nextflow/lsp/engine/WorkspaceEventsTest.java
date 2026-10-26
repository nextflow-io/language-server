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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.services.ErrorReportingMode;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WatchKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static nextflow.lsp.TestUtils.UNUSED;
import static nextflow.lsp.TestUtils.thrownOnAnotherThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for workspace events: folders, routing, ownership,
 * configuration changes and the default project.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceEventsTest {

    static final String WITH_WARNING = "workflow { def x = 1 }\n";

    @Test
    void rejectWorkspaceAccessFromOtherThreads() throws Exception {
        var f = TestServer.start();
        var workspace = f.workspace();
        var spec = new RequestSpec("test", f.uri("main.nf"), null, Need.FILE, false);
        for( Runnable access : List.<Runnable>of(
            () -> workspace.apply(new Event.Initialized(new InitializedParams())),
            () -> workspace.route(spec),
            () -> workspace.runSlice()
        ) )
            assertThat(thrownOnAnotherThread(access)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routeToTheInnermostFolderBySegment() {
        var f = TestServer.start((o) -> o.folders("a", "a/inner", "a/foo"));
        var workspace = f.workspace();
        assertThat(workspace.projectFor(f.path("a/main.nf")).name).isEqualTo("a");
        assertThat(workspace.projectFor(f.path("a/inner/main.nf")).name).isEqualTo("a/inner");
        assertThat(workspace.projectFor(f.path("a/foo/main.nf")).name).isEqualTo("a/foo");
        assertThat(workspace.projectFor(f.path("a/foobar/main.nf")).name).isEqualTo("a");
        assertThat(workspace.projectFor(f.path("b/main.nf")).isDefault()).isTrue();
    }

    @Test
    void buildFilesOfNestedFoldersInTheInnermostFolderAndDependenciesInBoth() {
        var f = TestServer.start((o) -> {
            o.folders("", "sub");
            o.settle = false;
        });
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

    @Test
    void moveFilesBetweenNestedFoldersWhenTheInnerFolderChanges() {
        var f = TestServer.start((o) -> o.settle = false);
        f.write("sub/a.nf", WITH_WARNING);
        f.write("sub/b.nf", WITH_WARNING);
        f.open("sub/b.nf", WITH_WARNING);
        f.settle();
        assertThat(f.parses()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));

        // each file is held by one folder at a time, and is built once
        // by the folder it moves to
        var before = f.parses();
        f.addFolder("sub");
        f.settle();
        assertThat(f.entries()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(f.parsesSince(before)).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(f.messages("sub/a.nf")).containsExactly(UNUSED);
        assertThat(f.messages("sub/b.nf")).containsExactly(UNUSED);

        before = f.parses();
        f.removeFolder("sub");
        f.settle();
        assertThat(f.entries()).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(f.parsesSince(before)).isEqualTo(Map.of("sub/a.nf", 1, "sub/b.nf", 1));
        assertThat(f.messages("sub/a.nf")).containsExactly(UNUSED);
        assertThat(f.messages("sub/b.nf")).containsExactly(UNUSED);
        assertThat(f.publishes("sub/a.nf")).noneMatch(List::isEmpty);

        // the outer folder serves the files again
        f.writeAndNotify("sub/a.nf", "workflow {\n");
        f.settle();
        assertThat(f.messages("sub/a.nf")).hasSize(1).doesNotContain(UNUSED);
    }

    @Test
    void moveOpenDocumentToAnAddedFolder() {
        var f = TestServer.start((o) -> o.folders());
        f.open("a/main.nf", WITH_WARNING);
        f.settle();
        assertThat(f.messages("a/main.nf")).containsExactly(UNUSED);

        f.write("a/main.nf", WITH_WARNING);
        var before = f.parses();
        f.addFolder("a");
        f.settle();
        var path = f.path("a/main.nf");
        assertThat(f.workspace().projectFor(path).name).isEqualTo("a");
        assertThat(f.entries()).isEqualTo(Map.of("a/main.nf", 1));
        assertThat(f.parsesSince(before)).isEqualTo(Map.of("a/main.nf", 1));
        assertThat(f.publishes("a/main.nf")).noneMatch(List::isEmpty);
        assertThat(f.messages("a/main.nf")).containsExactly(UNUSED);
    }

    @Test
    void moveExcludedOpenDocumentToAnAddedFolder() {
        var f = TestServer.start((o) -> o.folders());
        f.open("a/work/x.nf", WITH_WARNING);
        f.settle();
        assertThat(f.messages("a/work/x.nf")).containsExactly(UNUSED);

        var before = f.parses();
        f.addFolder("a");
        f.settle();
        assertThat(f.entries()).isEqualTo(Map.of("a/work/x.nf", 1));
        assertThat(f.parsesSince(before)).isEqualTo(Map.of("a/work/x.nf", 1));
        f.change("a/work/x.nf", "workflow {\n");
        f.settle();
        assertThat(f.messages("a/work/x.nf")).hasSize(1).doesNotContain(UNUSED);
    }

    @Test
    void publishOpenExcludedFileOnlyWhileItIsOpen() {
        var f = TestServer.start((o) -> o.settle = false);
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

    @Test
    void clearDiagnosticsOfRemovedFolder() {
        var f = TestServer.start((o) -> {
            o.folders("a", "b");
            o.settle = false;
        });
        f.write("a/x.nf", WITH_WARNING);
        f.write("a/y.nf", "workflow {\n");
        f.write("a/nextflow.config", "process {\n");
        f.write("b/z.nf", WITH_WARNING);
        f.open("a/open.nf", WITH_WARNING);
        f.settle();
        assertThat(f.messages("a/x.nf")).containsExactly(UNUSED);
        assertThat(f.messages("a/y.nf")).isNotEmpty();
        assertThat(f.messages("a/nextflow.config")).isNotEmpty();

        f.removeFolder("a");
        f.settle();
        assertThat(f.messages("a/x.nf")).isEmpty();
        assertThat(f.messages("a/y.nf")).isEmpty();
        assertThat(f.messages("a/nextflow.config")).isEmpty();
        assertThat(f.publishes("a/x.nf")).hasSize(2);
        // the open document moves to the default project without flicker
        assertThat(f.publishes("a/open.nf")).noneMatch(List::isEmpty);
        assertThat(f.messages("a/open.nf")).containsExactly(UNUSED);
        assertThat(f.messages("b/z.nf")).containsExactly(UNUSED);
        assertThat(f.workspace().projectFor(f.path("a/x.nf")).isDefault()).isTrue();
        assertThat(f.engine().isIdle()).isTrue();
    }

    @Test
    void publishSharedDependencyOnlyFromItsOwner() {
        var f = TestServer.start((o) -> {
            o.folders("a", "b");
            o.settle = false;
        });
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

    @Test
    void republishWithoutRebuildingWhenErrorReportingModeChanges() {
        var f = TestServer.start((o) -> o.settle = false);
        f.write("main.nf", WITH_WARNING);
        f.write("bad.nf", "workflow {\n");
        f.settle();
        var before = f.parses();
        assertThat(f.diagnostics("main.nf")).singleElement()
            .satisfies(d -> assertThat(d.getMessage()).isEqualTo(UNUSED))
            .satisfies(d -> assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning));
        assertThat(f.diagnostics("bad.nf")).isNotEmpty()
            .allMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);

        f.configure(Map.of("nextflow.errorReportingMode", "errors"));
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
        assertThat(f.messages("bad.nf")).isNotEmpty();

        f.configure(Map.of("nextflow.errorReportingMode", "off"));
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
        assertThat(f.messages("bad.nf")).isEmpty();

        f.configure(Map.of("nextflow.errorReportingMode", "warnings"));
        f.settle();
        assertThat(f.messages("main.nf")).containsExactly(UNUSED);
        assertThat(f.messages("bad.nf")).isNotEmpty();
        assertThat(f.parsesSince(before)).isEmpty();
    }

    @Test
    void doNotFlickerDiagnosticsOnConfigurationChange() {
        var f = TestServer.start((o) -> o.settle = false);
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

    @Test
    void applyExcludeChangesIncrementally() {
        var f = TestServer.start((o) -> o.settle = false);
        f.write("main.nf", WITH_WARNING);
        f.write("sub/x.nf", WITH_WARNING);
        f.write("tests/y.nf", WITH_WARNING);
        f.configure(Map.of("nextflow.files.exclude", List.of("tests")));
        f.settle();
        assertThat(f.messages("sub/x.nf")).containsExactly(UNUSED);
        assertThat(f.publishes("tests/y.nf")).isEmpty();

        f.configure(Map.of("nextflow.files.exclude", List.of("sub")));
        f.settle();
        assertThat(f.publishes("sub/x.nf")).satisfiesExactly(
            (first) -> assertThat(first).extracting(Diagnostic::getMessage).containsExactly(UNUSED),
            (last) -> assertThat(last).isEmpty());
        assertThat(f.messages("tests/y.nf")).containsExactly(UNUSED);
        assertThat(f.publishes("main.nf")).hasSize(1);
        assertThat(f.call(Need.FILE, "sub/x.nf", "empty", (context) -> "ran")).isEqualTo("empty");
    }

    @Test
    void ignoreTheResultOfASupersededWalk() {
        var io = new QueuedExecutor();
        var f = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.initialized = false;
            o.settle = false;
        });
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

    @Test
    void publishNothingAfterShutdown() {
        var io = new QueuedExecutor();
        var f = TestServer.start((o) -> {
            o.folders("a");
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.settle = false;
        });
        f.runPending();
        // background work runs without delay while the folder is indexing
        f.open("outside.nf", WITH_WARNING);
        var shutdown = f.engine().shutdown();
        f.runPending();
        assertThat(shutdown).isDone();
        assertThat(f.client().getPublishLog()).isEmpty();
    }

    @Test
    void ignoreInvalidSettingsButApplyTheOthers() {
        var f = TestServer.start();
        f.configure(Map.of(
            "nextflow.errorReportingMode", "bogus",
            "nextflow.completion.maxItems", "many",
            "nextflow.dag.direction", "LR"));
        f.settle();
        var configuration = f.workspace().configuration();
        assertThat(configuration.errorReportingMode()).isEqualTo(ErrorReportingMode.WARNINGS);
        assertThat(configuration.maxCompletionItems()).isEqualTo(100);
        assertThat(configuration.dagDirection()).isEqualTo("LR");
        assertThat(f.client().getLogMessages())
            .filteredOn(m -> m.getType() == MessageType.Warning)
            .extracting(m -> m.getMessage())
            .anyMatch(m -> m.contains("nextflow.errorReportingMode"))
            .anyMatch(m -> m.contains("nextflow.completion.maxItems"));
    }

    @Test
    void ignoreInvalidRegistryUrlButApplyTheOtherSettings() {
        var f = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry(HttpPluginRegistryClient::new);
            o.settle = false;
        });
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

    @Test
    void dropClosedFileFromTheDefaultProject() {
        var f = TestServer.start((o) -> o.folders());
        f.write("outside/main.nf", WITH_WARNING);
        f.open("outside/main.nf", WITH_WARNING);
        f.settle();
        assertThat(f.messages("outside/main.nf")).containsExactly(UNUSED);

        f.close("outside/main.nf");
        f.settle();
        assertThat(f.messages("outside/main.nf")).isEmpty();
        assertThat(f.entries()).isEmpty();
    }

    @Test
    void keepClosedFileInTheDefaultProjectWhileItIsIncluded() {
        var f = TestServer.start((o) -> o.folders());
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
        assertThat(f.entries()).isEmpty();
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
        var f = TestServer.start((o) -> {
            o.folders(folders.split(",", -1));
            o.settle = false;
        });
        var dep = Path.of(main).resolveSibling(include).normalize().toString();
        var dir = Path.of(dep).getParent().toString();
        // the last case's dependencies are excluded from its folder
        if( dir.equals("vendor") )
            f.configure(Map.of("nextflow.files.exclude", List.of("vendor")));
        if( cyclic ) {
            f.write(dep, """
                include { B } from './b.nf'
                workflow A { def x = 1 }
                """);
            f.write(dir + "/b.nf", """
                include { A } from './a.nf'
                workflow B {}
                """);
        }
        else {
            f.write(dep, "workflow A { def x = 1 }\n");
        }
        f.write(main, """
            include { A } from '%s'
            workflow {
                A()
            }
            """.formatted(include));
        if( open )
            f.open(dep, "workflow A { def x = 1 }\n");
        f.settle();
        var deps = cyclic ? List.of(main, dep, dir + "/b.nf") : List.of(main, dep);
        assertThat(scriptPaths(f, main)).containsExactlyInAnyOrderElementsOf(deps);
        if( outside )
            assertThat(f.messages(dep)).contains(UNUSED);

        f.writeAndNotify(main, "workflow {}\n");
        f.settle();
        if( open ) {
            f.close(dep);
            f.settle();
        }
        assertThat(scriptPaths(f, main)).containsExactly(main);
        if( outside )
            assertThat(f.messages(dep)).isEmpty();
    }

    private static List<String> scriptPaths(TestServer f, String path) {
        var root = f.path("");
        return f.call(Need.FILE, path, List.<String>of(), (context) ->
            context.project().scriptView().getUris().stream()
                .map(uri -> root.relativize(Path.of(uri)).toString())
                .sorted()
                .toList());
    }

    @Test
    void pickUpNewFilesFromWatcherEvents() {
        var f = TestServer.start((o) -> o.watchers = true);
        assertThat(f.client().getRegistrations()).singleElement()
            .satisfies(r -> assertThat(r.getRegistrations().get(0).getMethod()).isEqualTo("workspace/didChangeWatchedFiles"));

        f.writeAndNotify("new.nf", WITH_WARNING);
        f.settle();
        assertThat(f.messages("new.nf")).containsExactly(UNUSED);

        f.delete("new.nf");
        f.settle();
        assertThat(f.messages("new.nf")).isEmpty();
        assertThat(f.entries()).isEmpty();
    }

    @Test
    void deleteEveryFileOfADeletedDirectory() throws IOException {
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
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
        assertThat(f.entries()).containsOnlyKeys("main.nf");
    }

    private static final Map<String, Object> EXCLUDE_VENDOR = Map.of("nextflow.files.exclude", List.of("vendor"));

    private static final String INCLUDE_VENDOR = """
        include { V } from './vendor/v.nf'
        workflow {
            V()
        }
        """;

    private static Map<String, List<Diagnostic>> freshDiagnostics(Map<String, Object> settings) {
        var fresh = TestServer.start((o) -> {
            o.settings = settings;
            o.settle = false;
        });
        fresh.settle();
        return fresh.client().getNonEmptyDiagnostics();
    }

    @Test
    void invalidateTheIncludersOfADeletedExcludedDirectory() throws IOException {
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settings = EXCLUDE_VENDOR;
            o.settle = false;
        });
        f.write("vendor/v.nf", "workflow V {}\n");
        f.write("main.nf", INCLUDE_VENDOR);
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();

        // the client reports only the directory
        Files.delete(f.path("vendor/v.nf"));
        Files.delete(f.path("vendor"));
        f.notify("vendor", FileChangeType.Deleted);
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        assertThat(f.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(EXCLUDE_VENDOR));
    }

    @Test
    void invalidateTheIncludersOfAFileCreatedInAnExcludedDirectory() {
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settings = EXCLUDE_VENDOR;
            o.settle = false;
        });
        f.write("main.nf", INCLUDE_VENDOR);
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        f.write("vendor/v.nf", "workflow V {}\n");
        notifyIfWatched(f, "vendor", FileChangeType.Created);
        notifyIfWatched(f, "vendor/v.nf", FileChangeType.Created);
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
        assertThat(f.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(EXCLUDE_VENDOR));
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
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
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

    @Test
    void discoverTheFilesOfACreatedDirectory() {
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        f.write("main.nf", """
            include { A } from './modules/a.nf'
            workflow {
                A()
            }
            """);
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        // the client reports each file, and not the directory
        f.write("modules/a.nf", "workflow A { def x = 1 }\n");
        f.write("modules/sub/b.nf", WITH_WARNING);
        f.write("modules/work/c.nf", WITH_WARNING);
        for( var path : List.of("modules", "modules/a.nf", "modules/sub", "modules/sub/b.nf", "modules/work", "modules/work/c.nf") )
            notifyIfWatched(f, path, FileChangeType.Created);
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
        assertThat(f.messages("modules/a.nf")).containsExactly(UNUSED);
        assertThat(f.messages("modules/sub/b.nf")).containsExactly(UNUSED);
        assertThat(f.publishes("modules/work/c.nf")).isEmpty();
    }

    @Test
    void resolveADirectoryIncludeWhenItsMainScriptIsCreated() {
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        f.write("main.nf", "include { FOO } from './modules/foo'\nworkflow { FOO() }\n");
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        f.writeAndNotify("modules/foo/main.nf", "workflow FOO {}\n");
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
        assertThat(f.includeTarget("main.nf")).hasToString(f.uri("modules/foo/main.nf"));
    }

    @Test
    void resolveAnIncludeToADirectoryCreatedNextToTheScript() {
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        f.write("foo.nf", "workflow FOO {}\n");
        f.write("main.nf", "include { FOO } from './foo'\nworkflow { FOO() }\n");
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();

        // the directory takes precedence over foo.nf
        f.writeAndNotify("foo/main.nf", "workflow BAR {}\n");
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.contains("'FOO' is not defined"));
        assertThat(f.client().getNonEmptyDiagnostics())
            .isEqualTo(WorkspaceBuildTest.freshDiagnostics(Map.of()))
            .isNotEmpty();
    }

    @Test
    void resolveARemoteModuleWhenItIsInstalled() {
        var f = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        f.write("main.nf", "include { FOO } from 'nf-core/foo'\nworkflow { FOO() }\n");
        f.settle();
        assertThat(f.messages("main.nf")).isNotEmpty();

        f.writeAndNotify("modules/nf-core/foo/main.nf", "workflow FOO {}\n");
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
    }

    @Test
    void doNotRegisterWatchersWithoutClientSupport() {
        var f = TestServer.start();
        assertThat(f.client().getRegistrations()).isEmpty();
    }

}

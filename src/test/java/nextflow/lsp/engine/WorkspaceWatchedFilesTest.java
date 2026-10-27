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
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.WatchKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.UNUSED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies how the workspace handles watched file events: created,
 * deleted and moved files and directories, and include targets that
 * appear on disk.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceWatchedFilesTest {

    static final String WITH_WARNING = "workflow { def x = 1 }\n";

    @Test
    void pickUpNewFilesFromWatcherEvents() {
        var server = TestServer.start((o) -> o.watchers = true);
        assertThat(server.client().getRegistrations()).singleElement()
            .satisfies(r -> assertThat(r.getRegistrations().get(0).getMethod()).isEqualTo("workspace/didChangeWatchedFiles"));

        server.writeAndNotify("new.nf", WITH_WARNING);
        server.settle();
        assertThat(server.messages("new.nf")).containsExactly(UNUSED);

        server.delete("new.nf");
        server.settle();
        assertThat(server.messages("new.nf")).isEmpty();
        assertThat(server.entries()).isEmpty();
    }

    @Test
    void deleteEveryFileOfADeletedDirectory() throws IOException {
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        server.write("modules/a.nf", "workflow A { def x = 1 }\n");
        server.write("modules/sub/b.nf", WITH_WARNING);
        server.write("main.nf", """
            include { A } from './modules/a.nf'
            workflow {
                A()
            }
            """);
        server.settle();
        assertThat(server.messages("modules/a.nf")).containsExactly(UNUSED);

        // the client reports only the directory
        try( var paths = Files.walk(server.path("modules")) ) {
            for( var path : paths.sorted(Comparator.reverseOrder()).toList() )
                Files.delete(path);
        }
        server.notify("modules", FileChangeType.Deleted);
        server.settle();
        assertThat(server.messages("modules/a.nf")).isEmpty();
        assertThat(server.messages("modules/sub/b.nf")).isEmpty();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        assertThat(server.entries()).containsOnlyKeys("main.nf");
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
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settings = EXCLUDE_VENDOR;
            o.settle = false;
        });
        server.write("vendor/v.nf", "workflow V {}\n");
        server.write("main.nf", INCLUDE_VENDOR);
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();

        // the client reports only the directory
        Files.delete(server.path("vendor/v.nf"));
        Files.delete(server.path("vendor"));
        server.notify("vendor", FileChangeType.Deleted);
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        assertThat(server.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(EXCLUDE_VENDOR));
    }

    @Test
    void invalidateTheIncludersOfAFileCreatedInAnExcludedDirectory() {
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settings = EXCLUDE_VENDOR;
            o.settle = false;
        });
        server.write("main.nf", INCLUDE_VENDOR);
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        server.write("vendor/v.nf", "workflow V {}\n");
        notifyIfWatched(server, "vendor", FileChangeType.Created);
        notifyIfWatched(server, "vendor/v.nf", FileChangeType.Created);
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(EXCLUDE_VENDOR));
    }

    /**
     * Send a watcher event only if a registered watcher matches it,
     * like a client does. Globs are matched against the path relative
     * to the workspace root.
     */
    private static void notifyIfWatched(TestServer server, String path, FileChangeType type) {
        var kind = switch( type ) {
            case Created -> WatchKind.Create;
            case Changed -> WatchKind.Change;
            case Deleted -> WatchKind.Delete;
        };
        var watched = server.client().getRegistrations().stream()
            .flatMap(params -> params.getRegistrations().stream())
            .flatMap(registration -> ((DidChangeWatchedFilesRegistrationOptions) registration.getRegisterOptions()).getWatchers().stream())
            .anyMatch(watcher -> {
                var watcherKind = watcher.getKind() != null ? watcher.getKind() : 7;
                var matcher = FileSystems.getDefault().getPathMatcher("glob:" + watcher.getGlobPattern().getLeft());
                return (watcherKind & kind) != 0 && matcher.matches(Path.of(path));
            });
        if( watched )
            server.notify(path, type);
    }

    @Test
    void discoverTheFilesOfAMovedDirectory() throws IOException {
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        server.write("modules/local/a.nf", "workflow A { def x = 1 }\n");
        server.write("main.nf", """
            include { A } from './modules/local/a.nf'
            workflow {
                A()
            }
            """);
        server.settle();
        assertThat(server.messages("modules/local/a.nf")).containsExactly(UNUSED);

        // the client reports only the directories, and not the files under them
        Files.move(server.path("modules/local"), server.path("modules/shared"));
        notifyIfWatched(server, "modules/local", FileChangeType.Deleted);
        notifyIfWatched(server, "modules/shared", FileChangeType.Created);
        server.settle();
        assertThat(server.messages("modules/local/a.nf")).isEmpty();
        assertThat(server.messages("modules/shared/a.nf")).containsExactly(UNUSED);
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
    }

    @Test
    void discoverTheFilesOfACreatedDirectory() {
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        server.write("main.nf", """
            include { A } from './modules/a.nf'
            workflow {
                A()
            }
            """);
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        // the client reports each file, and not the directory
        server.write("modules/a.nf", "workflow A { def x = 1 }\n");
        server.write("modules/sub/b.nf", WITH_WARNING);
        server.write("modules/work/c.nf", WITH_WARNING);
        for( var path : List.of("modules", "modules/a.nf", "modules/sub", "modules/sub/b.nf", "modules/work", "modules/work/c.nf") )
            notifyIfWatched(server, path, FileChangeType.Created);
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.messages("modules/a.nf")).containsExactly(UNUSED);
        assertThat(server.messages("modules/sub/b.nf")).containsExactly(UNUSED);
        assertThat(server.publishes("modules/work/c.nf")).isEmpty();
    }

    @Test
    void resolveADirectoryIncludeWhenItsMainScriptIsCreated() {
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        server.write("main.nf", "include { FOO } from './modules/foo'\nworkflow { FOO() }\n");
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        server.writeAndNotify("modules/foo/main.nf", "workflow FOO {}\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.includeTarget("main.nf")).hasToString(server.uri("modules/foo/main.nf"));
    }

    @Test
    void resolveAnIncludeToADirectoryCreatedNextToTheScript() {
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        server.write("foo.nf", "workflow FOO {}\n");
        server.write("main.nf", "include { FOO } from './foo'\nworkflow { FOO() }\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();

        // the directory takes precedence over foo.nf
        server.writeAndNotify("foo/main.nf", "workflow BAR {}\n");
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.contains("'FOO' is not defined"));
        assertThat(server.client().getNonEmptyDiagnostics())
            .isEqualTo(WorkspaceBuildTest.freshDiagnostics(Map.of()))
            .isNotEmpty();
    }

    @Test
    void resolveARemoteModuleWhenItIsInstalled() {
        var server = TestServer.start((o) -> {
            o.watchers = true;
            o.settle = false;
        });
        server.write("main.nf", "include { FOO } from 'nf-core/foo'\nworkflow { FOO() }\n");
        server.settle();
        assertThat(server.messages("main.nf")).isNotEmpty();

        server.writeAndNotify("modules/nf-core/foo/main.nf", "workflow FOO {}\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
    }

    @Test
    void doNotRegisterWatchersWithoutClientSupport() {
        var server = TestServer.start();
        assertThat(server.client().getRegistrations()).isEmpty();
    }

}

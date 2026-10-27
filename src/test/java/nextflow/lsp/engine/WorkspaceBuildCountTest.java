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

import nextflow.lsp.NextflowLanguageServer;
import nextflow.lsp.TestServer;
import nextflow.lsp.TestUtils;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
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

    static TestServer start() {
        var server = TestServer.start((o) -> o.settle = false);
        server.write("mod_a.nf", MOD_A);
        server.write("mod_b.nf", MOD_B);
        server.write("sub.nf", """
            include { A } from './mod_a.nf'
            include { B } from './mod_b.nf'
            workflow SUB { A() ; B() }
            """);
        server.write("main.nf", """
            include { SUB } from './sub.nf'
            workflow { SUB() }
            """);
        server.write("nextflow.config", "process.cpus = 2\n");
        server.settle();
        return server;
    }

    static void requestEverything(TestServer server, String path) {
        for( var need : List.of(Need.FILE, Need.PROJECT_CONFIGS, Need.PROJECT) )
            server.call(need, path, null, (context) -> null);
        server.call(Need.ALL, null, null, (context) -> null);
    }

    @Test
    void parseEveryFileOnceWhileIndexing() {
        var server = start();
        assertThat(server.parses()).containsOnlyKeys(FILES).allSatisfy((path, count) -> assertThat(count).as(path).isEqualTo(1));
    }

    @Test
    void parseNothingForRequestsOnAFreshProject() {
        var server = start();
        var before = server.parses();
        for( int i = 0; i < 3; i++ ) {
            for( var path : FILES )
                requestEverything(server, path);
        }
        server.settle();
        assertThat(server.parsesSince(before)).isEmpty();
    }

    @Test
    void parseAnEditedModuleOnceAndItsDependentsOnceForProjectRequests() {
        var server = start();
        var before = server.parses();
        server.open("mod_a.nf", MOD_A);
        server.call(Need.FILE, "mod_a.nf", null, (context) -> null);
        assertThat(server.parsesSince(before)).isEmpty();

        server.change("mod_a.nf", "workflow A { def y = 1 }\n");
        for( int i = 0; i < 3; i++ )
            server.call(Need.FILE, "mod_a.nf", null, (context) -> null);
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("mod_a.nf", 1));

        for( int i = 0; i < 3; i++ )
            server.call(Need.PROJECT, "mod_a.nf", null, (context) -> null);
        server.settle();
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("mod_a.nf", 1, "sub.nf", 1, "main.nf", 1));
        assertThat(server.includeTarget("sub.nf")).isEqualTo(URI.create(server.uri("mod_a.nf")));
    }

    @Test
    void parseOnlyTheRequestFileForCodeActionOnWidelyIncludedModule() {
        var server = TestServer.start((o) -> o.settle = false);
        server.write("modules/align.nf", "process ALIGN {\n    script:\n    'echo align'\n}\n");
        var sub = """
            include { ALIGN } from '../modules/align.nf'

            workflow SUB {
                main:
                ALIGN()
            }
            """;
        server.write("subworkflows/sub.nf", sub);
        for( int i = 0; i < 50; i++ )
            server.write("main" + i + ".nf", "include { SUB } from './subworkflows/sub.nf'\nworkflow { SUB() }\n");
        server.settle();
        server.open("subworkflows/sub.nf", sub);
        var before = server.parses();
        server.change("subworkflows/sub.nf", sub + "\n");

        var position = new Position(4, 4);
        var params = new CodeActionParams(new TextDocumentIdentifier(server.uri("subworkflows/sub.nf")), new Range(position, position), new CodeActionContext(List.of()));
        var actions = server.call((NextflowLanguageServer s) -> s.codeAction(params));
        assertThat(actions).isNotEmpty();
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("subworkflows/sub.nf", 1));
    }

    @Test
    void stopBuildingBetweenSlicesWhenRequestIsCancelled() {
        var cancelOnRead = new AtomicReference<CompletableFuture<?>>();
        var files = new ForwardingFileAccess() {
            @Override
            public String readText(Path path) throws IOException {
                var request = cancelOnRead.getAndSet(null);
                if( request != null )
                    request.cancel(true);
                return super.readText(path);
            }
        };
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.maxSliceFiles = 1;
            o.settle = false;
        });
        var main = new StringBuilder();
        for( int i = 0; i < 5; i++ ) {
            server.write("mod" + i + ".nf", "workflow M" + i + " {}\n");
            main.append("include { M" + i + " } from './mod" + i + ".nf'\n");
        }
        main.append("workflow { M0() }\n");
        server.write("main.nf", main.toString());
        server.settle();
        var before = server.parses();

        for( int i = 0; i < 5; i++ )
            server.writeAndNotify("mod" + i + ".nf", "workflow M" + i + " { def x = 1 }\n");
        var request = server.send(Need.FILE, "main.nf", "empty", (context) -> "done");
        cancelOnRead.set(request);
        server.runPending();

        // the slice in progress is committed, and the rest is left stale
        assertThat(request).isCancelled();
        assertThat(server.parsesSince(before)).hasSize(1);

        server.settle();
        assertThat(server.parsesSince(before))
            .hasSize(6)
            .allSatisfy((path, count) -> assertThat(count).as(path).isEqualTo(1));
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.messages("mod4.nf")).containsExactly(TestUtils.UNUSED);
    }

}

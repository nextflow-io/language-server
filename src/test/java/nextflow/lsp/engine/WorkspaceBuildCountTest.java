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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import nextflow.lsp.TestWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that count the builds and parses caused by requests and
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

    /**
     * Get the number of parses of each file so far.
     */
    static Map<String, Integer> parses(WorkspaceFixture f) {
        var project = f.workspace().projects().get(0);
        var result = new TreeMap<String, Integer>();
        for( var path : FILES ) {
            var entry = project.entry(URI.create(f.uri(path)));
            result.put(path, entry != null ? entry.parses : 0);
        }
        return result;
    }

    /**
     * Get the number of parses of each file since the given counts,
     * omitting the files that were not parsed.
     */
    static Map<String, Integer> parsesSince(WorkspaceFixture f, Map<String, Integer> before) {
        var result = new TreeMap<String, Integer>();
        parses(f).forEach((path, count) -> {
            var delta = count - before.getOrDefault(path, 0);
            if( delta != 0 )
                result.put(path, delta);
        });
        return result;
    }

    static void requestEverything(WorkspaceFixture f, String path) {
        for( var need : List.of(Need.NONE, Need.FILE, Need.FILE_AND_DEPENDENTS, Need.PROJECT_CONFIGS, Need.PROJECT) )
            f.call(need, path, null, (context) -> null);
        f.call(Need.ALL, null, null, (context) -> null);
    }

    static URI includeTarget(WorkspaceFixture f, String path) {
        return f.call(Need.FILE, path, null, (context) -> {
            var scripts = context.project().scriptView();
            var include = scripts.getIncludeNodes(URI.create(f.uri(path))).get(0);
            return scripts.getURI(include.entries.get(0).getTarget());
        });
    }

    @Test
    void parseEveryFileOnceWhileIndexing() {
        try( var f = start() ) {
            assertThat(parses(f)).allSatisfy((path, count) -> assertThat(count).as(path).isEqualTo(1));
        }
    }

    @Test
    void buildNothingForRequestsOnAFreshProject() {
        try( var f = start() ) {
            var builds = f.workspace().buildCount();
            var before = parses(f);
            for( int i = 0; i < 3; i++ ) {
                for( var path : FILES )
                    requestEverything(f, path);
            }
            f.settle();
            assertThat(f.workspace().buildCount()).isEqualTo(builds);
            assertThat(parsesSince(f, before)).isEmpty();
        }
    }

    @Test
    void parseAnEditedModuleOnceAndItsDependentsOnceForProjectRequests() {
        try( var f = start() ) {
            var before = parses(f);
            f.open("mod_a.nf", MOD_A);
            f.call(Need.FILE, "mod_a.nf", null, (context) -> null);
            assertThat(parsesSince(f, before)).isEmpty();

            f.change("mod_a.nf", "workflow A { def y = 1 }\n");
            for( int i = 0; i < 3; i++ )
                f.call(Need.FILE, "mod_a.nf", null, (context) -> null);
            assertThat(parsesSince(f, before)).isEqualTo(Map.of("mod_a.nf", 1));

            for( int i = 0; i < 3; i++ ) {
                f.call(Need.PROJECT, "mod_a.nf", null, (context) -> null);
                f.call(Need.FILE_AND_DEPENDENTS, "mod_a.nf", null, (context) -> null);
            }
            f.settle();
            assertThat(parsesSince(f, before)).isEqualTo(Map.of("mod_a.nf", 1, "sub.nf", 1, "main.nf", 1));
            assertThat(includeTarget(f, "sub.nf")).isEqualTo(URI.create(f.uri("mod_a.nf")));
        }
    }

}

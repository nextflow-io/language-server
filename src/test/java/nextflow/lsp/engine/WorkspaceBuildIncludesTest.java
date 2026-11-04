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
import java.nio.file.Files;
import java.util.Map;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.UNUSED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies how the build resolves script and config includes as
 * their targets are created, opened, closed and deleted.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceBuildIncludesTest {

    static TestServer start() {
        return TestServer.start((o) -> o.settle = false);
    }

    @Test
    void reportMissingIncludeUntilTheTargetIsCreated() {
        var server = start();
        server.write("main.nf", """
            include { FOO } from './mod.nf'
            workflow {
                FOO()
            }
            """);
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        server.writeAndNotify("mod.nf", "workflow FOO {}\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();

        server.delete("mod.nf");
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
    }

    @Test
    void resolveIncludeOfAnOpenFileThatIsNotOnDiskUntilItIsClosed() {
        var server = start();
        server.write("main.nf", """
            include { FOO } from './mod.nf'
            workflow {
                FOO()
            }
            """);
        server.open("mod.nf", "workflow FOO {}\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();

        // closing the unsaved file removes it
        server.close("mod.nf");
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
    }

    @Test
    void resolveMissingIncludeWhenTheTargetIsOpened() {
        var server = start();
        server.write("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        // not on disk, and no watcher event
        server.open("mod.nf", "workflow FOO {}\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
    }

    @Test
    void reportWarningsOfEachFileInAnIncludeCycle() {
        var server = start();
        server.write("a.nf", """
            include { B } from './b.nf'
            workflow A {
                def x = 1
            }
            """);
        server.write("b.nf", """
            include { A } from './a.nf'
            workflow B {
                A()
            }
            """);
        server.settle();
        assertThat(server.messages("a.nf")).containsExactly(UNUSED);

        server.open("b.nf", """
            include { A } from './a.nf'
            workflow B {
                A()
                def y = 2
            }
            """);
        server.settle();
        assertThat(server.messages("a.nf")).containsExactly(UNUSED);
        assertThat(server.messages("b.nf")).containsExactly(UNUSED);
    }

    @Test
    void checkConfigIncludesAgainWhenTheTargetIsCreated() {
        var server = start();
        server.write("nextflow.config", "includeConfig 'conf/base.config'\n");
        server.settle();
        assertThat(server.messages("nextflow.config")).singleElement().asString().startsWith("Invalid include source");

        server.writeAndNotify("conf/base.config", "process.cpus = 2\n");
        server.settle();
        assertThat(server.messages("nextflow.config")).isEmpty();
    }

    @Test
    void clearConfigIncludeErrorWhenAnOpenTargetIsSaved() {
        var server = start();
        server.write("nextflow.config", "includeConfig 'conf/base.config'\n");
        server.settle();
        assertThat(server.messages("nextflow.config")).singleElement().asString().startsWith("Invalid include source");

        server.open("conf/base.config", "process.cpus = 2\n");
        server.settle();

        // the watcher event is ignored because the document is open
        server.writeAndNotify("conf/base.config", "process.cpus = 2\n");
        server.settle();
        assertThat(server.messages("nextflow.config")).isEmpty();
        assertThat(server.client().getNonEmptyDiagnostics())
            .isEqualTo(WorkspaceBuildTest.freshDiagnostics(Map.of("conf/base.config", "process.cpus = 2\n")));
    }

    @Test
    void keepConfigIncludeValidWhenAnOpenTargetIsDeleted() {
        var server = start();
        server.write("nextflow.config", "includeConfig 'conf/base.config'\n");
        server.write("conf/base.config", "process.cpus = 2\n");
        server.open("conf/base.config", "process.cpus = 2\n");
        server.settle();
        assertThat(server.messages("nextflow.config")).isEmpty();

        server.delete("conf/base.config");
        server.settle();
        assertThat(server.messages("nextflow.config")).isEmpty();
        assertThat(server.client().getNonEmptyDiagnostics())
            .isEqualTo(WorkspaceBuildTest.freshDiagnostics(Map.of("conf/base.config", "process.cpus = 2\n")));
    }

    @Test
    void rebuildAFileWithUnchangedTextWhenAnotherInputChanged() {
        var server = start();
        server.write("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        server.settle();
        assertThat(server.messages("main.nf")).isNotEmpty();
        server.open("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        // the missing target is created while main.nf is stale
        server.writeAndNotify("mod.nf", "workflow FOO {}\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
    }

    @Test
    void rebuildAFileOpenedWithUnchangedTextWhenAnIncludeTargetAppeared() {
        var server = start();
        var main = "include { FOO } from './mod.nf'\nworkflow { FOO() }\n";
        server.write("main.nf", main);
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        // no watcher event
        server.write("mod.nf", "workflow FOO {}\n");
        server.open("main.nf", main);
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.client().getNonEmptyDiagnostics()).isEqualTo(WorkspaceBuildTest.freshDiagnostics(Map.of("main.nf", main)));
    }

    @Test
    void rebuildAFileClosedWithUnchangedTextWhenAnIncludeTargetDisappeared() throws IOException {
        var server = start();
        var main = "include { FOO } from './mod.nf'\nworkflow { FOO() }\n";
        server.write("main.nf", main);
        server.write("mod.nf", "workflow FOO {}\n");
        server.open("main.nf", main);
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();

        // no watcher event
        Files.delete(server.path("mod.nf"));
        server.close("main.nf");
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        assertThat(server.client().getNonEmptyDiagnostics()).isEqualTo(WorkspaceBuildTest.freshDiagnostics(Map.of()));
    }

}

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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the build pipeline: invalidation, re-parse on rebuild,
 * external inputs and failure containment.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceBuildTest {

    static final String UNUSED = "Variable was declared but not used";

    static WorkspaceFixture start() {
        return WorkspaceFixture.start((o) -> o.settle = false);
    }

    /**
     * Get the non-empty diagnostics of a fresh workspace on the current
     * files on disk plus the given open documents.
     */
    static Map<String, List<Diagnostic>> freshDiagnostics(Map<String, String> open) {
        try( var fresh = WorkspaceFixture.start((o) -> o.settle = false) ) {
            open.forEach(fresh::open);
            fresh.settle();
            return nonEmpty(fresh.client().getAllDiagnostics());
        }
    }

    static Map<String, List<Diagnostic>> nonEmpty(Map<String, List<Diagnostic>> diagnostics) {
        var result = new HashMap<String, List<Diagnostic>>();
        diagnostics.forEach((uri, list) -> {
            if( !list.isEmpty() )
                result.put(uri, list);
        });
        return result;
    }

    @Test
    void publishDiagnosticsForEveryFileInTheWorkspace() {
        try( var f = start() ) {
            f.write("mod.nf", """
                workflow FOO {
                    def y = 2
                }
                """);
            f.write("main.nf", """
                include { FOO } from './mod.nf'
                workflow {
                    FOO()
                }
                """);
            f.write("nextflow.config", "foo.bar = 1\n");
            f.settle();
            assertThat(f.messages("mod.nf")).containsExactly(UNUSED);
            assertThat(f.messages("main.nf")).isEmpty();
            assertThat(f.messages("nextflow.config")).containsExactly("Unrecognized config option 'foo.bar'");
        }
    }

    @Test
    void invalidateTransitiveDependents() {
        var producer = """
            nextflow.enable.types = true

            process PRODUCER {
                input:
                record(id: String)

                output:
                record(id: id, data: file('data.txt'))

                script:
                \"""
                echo hello > data.txt
                \"""
            }
            """;
        try( var f = start() ) {
            f.write("c.nf", producer);
            f.write("b.nf", """
                nextflow.enable.types = true

                include { PRODUCER } from './c.nf'

                workflow WF {
                    take:
                    ch_in: Channel<Record>

                    main:
                    ch_out = PRODUCER(ch_in)

                    emit:
                    ch_out
                }
                """);
            f.write("a.nf", """
                nextflow.enable.types = true

                include { WF } from './b.nf'

                process CONSUMER {
                    input:
                    record(id: String, data: Path)

                    output:
                    record(id: id)

                    script:
                    \"""
                    cat ${data}
                    \"""
                }

                workflow {
                    CONSUMER(WF(channel.of(record(id: 'sample1'))))
                }
                """);
            f.settle();
            assertThat(f.messages("a.nf")).isEmpty();

            // a.nf only depends on c.nf through b.nf
            f.open("c.nf", producer.replace("record(id: id, data: file('data.txt'))", "record(id: id)"));
            f.settle();
            assertThat(f.messages("a.nf")).singleElement().asString().contains("is not compatible with process input");

            f.change("c.nf", producer);
            f.settle();
            assertThat(f.messages("a.nf")).isEmpty();
        }
    }

    @Test
    void doNotDuplicateWarningsInDependentsWhenEditingAnIncludedModule() {
        try( var f = start() ) {
            f.write("main.nf", """
                include { FOO } from './mod.nf'
                workflow {
                    def x = 1
                    FOO()
                }
                """);
            var mod = """
                workflow FOO {
                    println 'hello'
                }
                """;
            f.write("mod.nf", mod);
            f.open("mod.nf", mod);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);

            for( int i = 0; i < 3; i++ ) {
                f.change("mod.nf", mod.replace("hello", "hello " + i));
                f.settle();
                assertThat(f.messages("main.nf")).containsExactly(UNUSED);
            }

            var finalMod = mod.replace("hello", "hello 2");
            assertThat(nonEmpty(f.client().getAllDiagnostics()))
                .isEqualTo(freshDiagnostics(Map.of("mod.nf", finalMod)));
        }
    }

    @Test
    void reportPluginErrorInFileWithLocalInclude() {
        try( var f = start() ) {
            f.write("mod.nf", "workflow FOO {}\n");
            f.write("main.nf", """
                include { FOO } from './mod.nf'
                include { bar } from 'plugin/nf-missing'

                workflow {
                    FOO()
                }
                """);
            f.settle();
            assertThat(f.messages("main.nf"))
                .containsExactly("Plugin 'nf-missing' does not exist or is not specified in the configuration file");

            // rebuilding the file must not drop or duplicate the error
            f.open("mod.nf", "workflow FOO { println 'hi' }\n");
            f.settle();
            assertThat(f.messages("main.nf"))
                .containsExactly("Plugin 'nf-missing' does not exist or is not specified in the configuration file");
        }
    }

    @Test
    void reportMissingIncludeUntilTheTargetIsCreated() {
        try( var f = start() ) {
            f.write("main.nf", """
                include { FOO } from './mod.nf'
                workflow {
                    FOO()
                }
                """);
            f.settle();
            assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

            f.writeAndNotify("mod.nf", "workflow FOO {}\n");
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();

            f.delete("mod.nf");
            f.settle();
            assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        }
    }

    @Test
    void includeOfAnOpenFileThatIsNotOnDisk() {
        try( var f = start() ) {
            f.write("main.nf", """
                include { FOO } from './mod.nf'
                workflow {
                    FOO()
                }
                """);
            f.open("mod.nf", "workflow FOO {}\n");
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();

            // closing the unsaved file removes it
            f.close("mod.nf");
            f.settle();
            assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        }
    }

    @Test
    void handleIncludeCycles() {
        try( var f = start() ) {
            f.write("a.nf", """
                include { B } from './b.nf'
                workflow A {
                    def x = 1
                }
                """);
            f.write("b.nf", """
                include { A } from './a.nf'
                workflow B {
                    A()
                }
                """);
            f.settle();
            assertThat(f.messages("a.nf")).containsExactly(UNUSED);

            f.open("b.nf", """
                include { A } from './a.nf'
                workflow B {
                    A()
                    def y = 2
                }
                """);
            f.settle();
            assertThat(f.messages("a.nf")).containsExactly(UNUSED);
            assertThat(f.messages("b.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void checkConfigIncludesAgainWhenTheTargetIsCreated() {
        try( var f = start() ) {
            f.write("nextflow.config", "includeConfig 'conf/base.config'\n");
            f.settle();
            assertThat(f.messages("nextflow.config")).singleElement().asString().startsWith("Invalid include source");

            f.writeAndNotify("conf/base.config", "process.cpus = 2\n");
            f.settle();
            assertThat(f.messages("nextflow.config")).isEmpty();
        }
    }

    @Test
    void rebuildEveryScriptWhenALibJarChanges() {
        try( var f = start() ) {
            f.write("main.nf", "workflow { def x = 1 }\n");
            f.write("other.nf", "workflow OTHER {}\n");
            f.write("nextflow.config", "process.cpus = 2\n");
            f.settle();
            var compiler = f.workspace().projects().get(0).scriptCompiler();
            var built = f.workspace().builtFileCount();

            f.notify("lib/tools.jar", org.eclipse.lsp4j.FileChangeType.Created);
            f.settle();
            assertThat(f.workspace().builtFileCount() - built).isEqualTo(2);
            assertThat(f.workspace().projects().get(0).scriptCompiler()).isNotSameAs(compiler);
            assertThat(f.messages("main.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void rebuildScriptWhenLibClassIsAdded() {
        try( var f = start() ) {
            f.write("main.nf", """
                workflow {
                    println(Greeter.greet('world'))
                }
                """);
            f.settle();
            assertThat(f.messages("main.nf")).singleElement().asString().contains("Greeter");

            f.writeAndNotify("lib/Greeter.groovy", """
                class Greeter {
                    static String greet(String name) {
                        return "Hello, ${name}!"
                    }
                }
                """);
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();
        }
    }

    @Test
    void rebuildScriptWhenParameterSchemaChanges() {
        var schema = """
            {
                "$defs": {
                    "input_options": {
                        "properties": {
                            %s
                        }
                    }
                }
            }
            """;
        try( var f = start() ) {
            f.write("nextflow_schema.json", schema.formatted("\"bar\": { \"type\": \"string\" }"));
            f.write("main.nf", """
                workflow {
                    println(params.foo)
                }
                """);
            f.settle();
            assertThat(f.messages("main.nf")).containsExactly("Unrecognized parameter `foo`");

            f.writeAndNotify("nextflow_schema.json", schema.formatted("\"foo\": { \"type\": \"string\" }"));
            f.settle();
            assertThat(f.messages("main.nf")).isEmpty();
        }
    }

    @Test
    void containReadFailureToOneFileAndRetryOnNextEvent() {
        var failing = new boolean[] { true };
        var files = new FailingFileAccess((path) -> failing[0] && path.endsWith("bad.nf"), false);
        try( var f = WorkspaceFixture.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.settle = false;
        }) ) {
            f.write("bad.nf", "workflow BAD { def x = 1 }\n");
            f.write("good.nf", "workflow GOOD { def y = 1 }\n");
            f.settle();
            assertThat(f.messages("good.nf")).containsExactly(UNUSED);
            assertThat(f.messages("bad.nf")).isEmpty();

            failing[0] = false;
            f.notify("bad.nf", org.eclipse.lsp4j.FileChangeType.Changed);
            f.settle();
            assertThat(f.messages("bad.nf")).containsExactly(UNUSED);
            assertThat(f.messages("good.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void containInternalErrorToOneFile() {
        var files = new FailingFileAccess((path) -> path.endsWith("bad.nf"), true);
        try( var f = WorkspaceFixture.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.settle = false;
        }) ) {
            f.write("bad.nf", "workflow BAD {}\n");
            f.write("good.nf", """
                include { BAD } from './bad.nf'
                workflow {
                    def y = 1
                    BAD()
                }
                """);
            f.settle();
            assertThat(f.messages("bad.nf")).containsExactly(DiagnosticsLedger.INTERNAL_ERROR_MESSAGE);
            assertThat(f.messages("good.nf")).contains(UNUSED);
            assertThat(f.client().getLogMessages()).anyMatch(m -> m.getMessage().contains("Internal error while parsing"));
        }
    }

    @Test
    void containUnresolvableRemoteModule() {
        try( var f = start() ) {
            f.write("main.nf", """
                include { X } from 'nf-core/missing'
                workflow {
                    def y = 1
                }
                """);
            f.write("other.nf", "workflow OTHER { def z = 1 }\n");
            f.settle();
            assertThat(f.diagnostics("main.nf")).isNotEmpty();
            assertThat(f.messages("other.nf")).containsExactly(UNUSED);
        }
    }

    @Test
    void doNothingWhenNothingIsStale() {
        try( var f = start() ) {
            f.write("main.nf", "workflow { def x = 1 }\n");
            f.settle();
            var builds = f.workspace().buildCount();
            var result = f.call(Need.FILE, "main.nf", null, (context) -> WorkspaceFixture.requestEntry(context).unit() != null);
            assertThat(result).isTrue();
            assertThat(f.workspace().buildCount()).isEqualTo(builds);
        }
    }

    /**
     * File access that fails to read some files.
     */
    static final class FailingFileAccess implements FileAccess {

        private final Predicate<Path> failing;

        private final boolean unexpected;

        FailingFileAccess(Predicate<Path> failing, boolean unexpected) {
            this.failing = failing;
            this.unexpected = unexpected;
        }

        @Override
        public String readText(Path path) throws IOException {
            if( failing.test(path) ) {
                if( unexpected )
                    throw new IllegalStateException("injected failure");
                throw new IOException("injected failure");
            }
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
    }

}

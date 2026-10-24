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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.Phases;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.FileChangeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.UNUSED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the build pipeline: invalidation, re-parse on rebuild,
 * external inputs and failure containment.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceBuildTest {

    static TestServer start() {
        return TestServer.start((o) -> o.settle = false);
    }

    /**
     * Get the non-empty diagnostics of a fresh workspace on the current
     * files on disk plus the given open documents.
     */
    static Map<String, List<Diagnostic>> freshDiagnostics(Map<String, String> open) {
        var fresh = TestServer.start((o) -> o.settle = false);
        open.forEach(fresh::open);
        fresh.settle();
        return fresh.client().getNonEmptyDiagnostics();
    }

    @Test
    void publishDiagnosticsForEveryFileInTheWorkspace() {
        var f = start();
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
        var f = start();
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

    /**
     * #108: editing an included module doesn't duplicate the warnings
     * of the files that include it.
     */
    @Test
    void doNotDuplicateWarningsInDependentsWhenEditingAnIncludedModule() {
        var f = start();
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
        assertThat(f.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(Map.of("mod.nf", finalMod)));
    }

    @Test
    void reportPluginErrorInFileWithLocalInclude() {
        var f = start();
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

    @Test
    void reportMissingIncludeUntilTheTargetIsCreated() {
        var f = start();
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

    @Test
    void includeOfAnOpenFileThatIsNotOnDisk() {
        var f = start();
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

    @Test
    void handleIncludeCycles() {
        var f = start();
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

    @Test
    void checkConfigIncludesAgainWhenTheTargetIsCreated() {
        var f = start();
        f.write("nextflow.config", "includeConfig 'conf/base.config'\n");
        f.settle();
        assertThat(f.messages("nextflow.config")).singleElement().asString().startsWith("Invalid include source");

        f.writeAndNotify("conf/base.config", "process.cpus = 2\n");
        f.settle();
        assertThat(f.messages("nextflow.config")).isEmpty();
    }

    @Test
    void clearConfigIncludeErrorWhenAnOpenTargetIsSaved() {
        var f = start();
        f.write("nextflow.config", "includeConfig 'conf/base.config'\n");
        f.settle();
        assertThat(f.messages("nextflow.config")).singleElement().asString().startsWith("Invalid include source");

        f.open("conf/base.config", "process.cpus = 2\n");
        f.settle();

        // the watcher event is ignored because the document is open
        f.writeAndNotify("conf/base.config", "process.cpus = 2\n");
        f.settle();
        assertThat(f.messages("nextflow.config")).isEmpty();
        assertThat(f.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(Map.of("conf/base.config", "process.cpus = 2\n")));
    }

    @Test
    void keepConfigIncludeValidWhenAnOpenTargetIsDeleted() {
        var f = start();
        f.write("nextflow.config", "includeConfig 'conf/base.config'\n");
        f.write("conf/base.config", "process.cpus = 2\n");
        f.open("conf/base.config", "process.cpus = 2\n");
        f.settle();
        assertThat(f.messages("nextflow.config")).isEmpty();

        f.delete("conf/base.config");
        f.settle();
        assertThat(f.messages("nextflow.config")).isEmpty();
        assertThat(f.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(Map.of("conf/base.config", "process.cpus = 2\n")));
    }

    @Test
    void rebuildEveryScriptWhenALibJarChanges() throws IOException {
        var f = start();
        f.write("main.nf", """
            workflow {
                def x = Greeter.greet('world')
            }
            """);
        f.write("other.nf", "workflow OTHER {}\n");
        f.write("nextflow.config", "process.cpus = 2\n");
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.contains("Greeter"));
        var before = f.parses();

        writeJar(f.path("lib/tools.jar"), """
            class Greeter {
                static String greet(String name) {
                    return "Hello, ${name}!"
                }
            }
            """);
        f.notify("lib/tools.jar", FileChangeType.Created);
        f.settle();
        assertThat(f.parsesSince(before)).isEqualTo(Map.of("main.nf", 1, "other.nf", 1));
        assertThat(f.messages("main.nf")).containsExactly(UNUSED);
    }

    @Test
    void rebuildScriptWhenLibClassIsAdded() {
        var f = start();
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
        var f = start();
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

    @Test
    void rebuildOnlyTheScriptsNextToAChangedParameterSchema() {
        var f = start();
        f.write("main.nf", "workflow { println(params.foo) }\n");
        f.write("sub/main.nf", "workflow { println(params.foo) }\n");
        f.write("sub/nextflow.config", "process.cpus = 2\n");
        f.settle();
        var before = f.parses();

        f.writeAndNotify("sub/nextflow_schema.json", "{}\n");
        f.settle();
        assertThat(f.parsesSince(before)).isEqualTo(Map.of("sub/main.nf", 1));
    }

    @Test
    void containReadFailureToOneFileAndRetryOnNextEvent() {
        var failing = new boolean[] { true };
        var files = new FailingFileAccess((path) -> failing[0] && path.endsWith("bad.nf"), false);
        var f = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.settle = false;
        });
        f.write("bad.nf", "workflow BAD { def x = 1 }\n");
        f.write("good.nf", "workflow GOOD { def y = 1 }\n");
        f.settle();
        assertThat(f.messages("good.nf")).containsExactly(UNUSED);
        assertThat(f.messages("bad.nf")).isEmpty();
        assertThat(f.client().getLogMessages()).anyMatch(m -> m.getMessage().startsWith("Failed to read " + f.path("bad.nf")));

        failing[0] = false;
        f.notify("bad.nf", FileChangeType.Changed);
        f.settle();
        assertThat(f.messages("bad.nf")).containsExactly(UNUSED);
        assertThat(f.messages("good.nf")).containsExactly(UNUSED);
    }

    @Test
    void containInternalErrorToOneFile() {
        var files = new FailingFileAccess((path) -> path.endsWith("bad.nf"), true);
        var f = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.settle = false;
        });
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

    @Test
    void containUnresolvableRemoteModule() {
        var f = start();
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

    @Test
    void rebuildDependentsOfAModuleThatARequestBuilt() {
        var f = start();
        f.write("mod.nf", "workflow FOO {}\n");
        f.write("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();

        f.open("mod.nf", "workflow BAR {}\n");
        // builds only the module, so the dependent is stale only by stamp
        f.call(Need.FILE, "mod.nf", null, (context) -> null);
        f.settle();
        assertThat(f.messages("main.nf")).isNotEmpty();
    }

    @Test
    void rebuildDependentsOfAModuleThatARequestRemoved() {
        var f = start();
        f.write("mod.nf", "workflow FOO {}\n");
        f.write("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();

        f.delete("mod.nf");
        f.call(Need.FILE, "mod.nf", null, (context) -> null);
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
    }

    @Test
    void rebuildAnEditedFileWhenItIsNewlyIncluded() {
        var f = start();
        f.write("mod.nf", "workflow FOO {}\n");
        f.write("main.nf", "workflow {}\n");
        f.settle();

        f.open("mod.nf", "workflow BAR {}\n");
        f.open("main.nf", "include { BAR } from './mod.nf'\nworkflow { BAR() }\n");
        var before = f.parses();
        var errors = f.call(Need.FILE, "main.nf", List.<String>of(), (context) ->
            context.project().scriptView().getErrors(URI.create(f.uri("main.nf"))).stream().map(e -> e.getOriginalMessage()).toList());
        assertThat(errors).isEmpty();
        // main.nf is never analysed against the previous build of mod.nf
        assertThat(f.parsesSince(before)).isEqualTo(Map.of("main.nf", 1, "mod.nf", 1));
        assertThat(f.publishes("main.nf")).allMatch(List::isEmpty);
    }

    @Test
    void resolveMissingIncludeWhenTheTargetIsOpened() {
        var f = start();
        f.write("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        // not on disk, and no watcher event
        f.open("mod.nf", "workflow FOO {}\n");
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
    }

    @Test
    void publishTheDocumentVersionOfTheBuild() {
        var f = start();
        f.open("main.nf", "workflow { def x = 1 }\n");
        f.change("main.nf", "workflow { def y = 1 }\n");
        f.settle();
        var uri = f.uri("main.nf");
        assertThat(f.client().getPublishLog()).filteredOn(p -> p.getUri().equals(uri)).last()
            .satisfies(p -> assertThat(p.getVersion()).isEqualTo(2));
    }

    @Test
    void keepTheBuildOfAModuleOpenedOrClosedWithUnchangedText() {
        var f = start();
        var module = "workflow FOO { def x = 1 }\n";
        f.write("mod.nf", module);
        for( int i = 0; i < 5; i++ )
            f.write("main" + i + ".nf", "include { FOO } from './mod.nf'\nworkflow W" + i + " { FOO() }\n");
        f.settle();
        var before = f.parses();

        f.open("mod.nf", module);
        f.settle();
        f.close("mod.nf");
        f.settle();
        assertThat(f.parsesSince(before)).isEmpty();
        assertThat(f.messages("mod.nf")).containsExactly(UNUSED);
        assertThat(f.client().getPublishLog()).last().satisfies(p -> assertThat(p.getVersion()).isNull());

        // the dependents still refer to the indexed module
        assertThat(f.includeTarget("main0.nf")).isEqualTo(URI.create(f.uri("mod.nf")));

        // a real change still rebuilds the dependents
        f.open("mod.nf", "workflow BAR {}\n");
        f.settle();
        assertThat(f.parsesSince(before)).isEqualTo(Map.of(
            "mod.nf", 1, "main0.nf", 1, "main1.nf", 1, "main2.nf", 1, "main3.nf", 1, "main4.nf", 1));
        assertThat(f.messages("main0.nf")).isNotEmpty();
    }

    @Test
    void rebuildAFileWithUnchangedTextWhenAnotherInputChanged() {
        var f = start();
        f.write("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        f.settle();
        assertThat(f.messages("main.nf")).isNotEmpty();
        f.open("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        // the missing target is created while main.nf is stale
        f.writeAndNotify("mod.nf", "workflow FOO {}\n");
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
    }

    @Test
    void rebuildAFileOpenedWithUnchangedTextWhenAnIncludeTargetAppeared() {
        var f = start();
        var main = "include { FOO } from './mod.nf'\nworkflow { FOO() }\n";
        f.write("main.nf", main);
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));

        // no watcher event
        f.write("mod.nf", "workflow FOO {}\n");
        f.open("main.nf", main);
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();
        assertThat(f.client().getNonEmptyDiagnostics()).isEqualTo(freshDiagnostics(Map.of("main.nf", main)));
    }

    @Test
    void rebuildAFileClosedWithUnchangedTextWhenAnIncludeTargetDisappeared() throws IOException {
        var f = start();
        var main = "include { FOO } from './mod.nf'\nworkflow { FOO() }\n";
        f.write("main.nf", main);
        f.write("mod.nf", "workflow FOO {}\n");
        f.open("main.nf", main);
        f.settle();
        assertThat(f.messages("main.nf")).isEmpty();

        // no watcher event
        Files.delete(f.path("mod.nf"));
        f.close("main.nf");
        f.settle();
        assertThat(f.messages("main.nf")).anyMatch(m -> m.startsWith("Invalid include source"));
        assertThat(f.client().getNonEmptyDiagnostics()).isEqualTo(freshDiagnostics(Map.of()));
    }

    /**
     * Compile Groovy source into a jar file.
     */
    static void writeJar(Path path, String source) throws IOException {
        var unit = new CompilationUnit();
        unit.addSource("Source.groovy", source);
        unit.compile(Phases.CLASS_GENERATION);
        Files.createDirectories(path.getParent());
        try( var jar = new JarOutputStream(Files.newOutputStream(path)) ) {
            for( var c : unit.getClasses() ) {
                jar.putNextEntry(new JarEntry(c.getName().replace('.', '/') + ".class"));
                jar.write(c.getBytes());
                jar.closeEntry();
            }
        }
    }

    /**
     * File access that fails to read some files.
     */
    static final class FailingFileAccess extends ForwardingFileAccess {

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
            return super.readText(path);
        }
    }

}

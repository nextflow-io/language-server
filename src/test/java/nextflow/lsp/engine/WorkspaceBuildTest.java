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
 * Specifies the build pipeline: invalidation, rebuilds, external
 * inputs (lib directory and parameter schema) and failure containment.
 * Include resolution is specified by WorkspaceBuildIncludesTest.
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
        var server = start();
        server.write("mod.nf", """
            workflow FOO {
                def y = 2
            }
            """);
        server.write("main.nf", """
            include { FOO } from './mod.nf'
            workflow {
                FOO()
            }
            """);
        server.write("nextflow.config", "foo.bar = 1\n");
        server.settle();
        assertThat(server.messages("mod.nf")).containsExactly(UNUSED);
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.messages("nextflow.config")).containsExactly("Unrecognized config option 'foo.bar'");
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
        var server = start();
        server.write("c.nf", producer);
        server.write("b.nf", """
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
        server.write("a.nf", """
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
        server.settle();
        assertThat(server.messages("a.nf")).isEmpty();

        // a.nf only depends on c.nf through b.nf
        server.open("c.nf", producer.replace("record(id: id, data: file('data.txt'))", "record(id: id)"));
        server.settle();
        assertThat(server.messages("a.nf")).singleElement().asString().contains("is not compatible with process input");

        server.change("c.nf", producer);
        server.settle();
        assertThat(server.messages("a.nf")).isEmpty();
    }

    /**
     * #108: editing an included module doesn't duplicate the warnings
     * of the files that include it.
     */
    @Test
    void doNotDuplicateWarningsInDependentsWhenEditingAnIncludedModule() {
        var server = start();
        server.write("main.nf", """
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
        server.write("mod.nf", mod);
        server.open("mod.nf", mod);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(UNUSED);

        for( int i = 0; i < 3; i++ ) {
            server.change("mod.nf", mod.replace("hello", "hello " + i));
            server.settle();
            assertThat(server.messages("main.nf")).containsExactly(UNUSED);
        }

        var finalMod = mod.replace("hello", "hello 2");
        assertThat(server.client().getNonEmptyDiagnostics())
            .isEqualTo(freshDiagnostics(Map.of("mod.nf", finalMod)));
    }

    @Test
    void reportPluginErrorInFileWithLocalInclude() {
        var server = start();
        server.write("mod.nf", "workflow FOO {}\n");
        server.write("main.nf", """
            include { FOO } from './mod.nf'
            include { bar } from 'plugin/nf-missing'

            workflow {
                FOO()
            }
            """);
        server.settle();
        assertThat(server.messages("main.nf"))
            .containsExactly("Plugin 'nf-missing' does not exist or is not specified in the configuration file");

        // rebuilding the file must not drop or duplicate the error
        server.open("mod.nf", "workflow FOO { println 'hi' }\n");
        server.settle();
        assertThat(server.messages("main.nf"))
            .containsExactly("Plugin 'nf-missing' does not exist or is not specified in the configuration file");
    }

    @Test
    void rebuildEveryScriptWhenALibJarChanges() throws IOException {
        var server = start();
        server.write("main.nf", """
            workflow {
                def x = Greeter.greet('world')
            }
            """);
        server.write("other.nf", "workflow OTHER {}\n");
        server.write("nextflow.config", "process.cpus = 2\n");
        server.settle();
        assertThat(server.messages("main.nf")).anyMatch(m -> m.contains("Greeter"));
        var before = server.parses();

        writeJar(server.path("lib/tools.jar"), """
            class Greeter {
                static String greet(String name) {
                    return "Hello, ${name}!"
                }
            }
            """);
        server.notify("lib/tools.jar", FileChangeType.Created);
        server.settle();
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("main.nf", 1, "other.nf", 1));
        assertThat(server.messages("main.nf")).containsExactly(UNUSED);
    }

    @Test
    void rebuildScriptWhenLibClassIsAdded() {
        var server = start();
        server.write("main.nf", """
            workflow {
                println(Greeter.greet('world'))
            }
            """);
        server.settle();
        assertThat(server.messages("main.nf")).singleElement().asString().contains("Greeter");

        server.writeAndNotify("lib/Greeter.groovy", """
            class Greeter {
                static String greet(String name) {
                    return "Hello, ${name}!"
                }
            }
            """);
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
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
        var server = start();
        server.write("nextflow_schema.json", schema.formatted("\"bar\": { \"type\": \"string\" }"));
        server.write("main.nf", """
            workflow {
                println(params.foo)
            }
            """);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly("Unrecognized parameter `foo`");

        server.writeAndNotify("nextflow_schema.json", schema.formatted("\"foo\": { \"type\": \"string\" }"));
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
    }

    @Test
    void rebuildOnlyTheScriptsNextToAChangedParameterSchema() {
        var server = start();
        server.write("main.nf", "workflow { println(params.foo) }\n");
        server.write("sub/main.nf", "workflow { println(params.foo) }\n");
        server.write("sub/nextflow.config", "process.cpus = 2\n");
        server.settle();
        var before = server.parses();

        server.writeAndNotify("sub/nextflow_schema.json", "{}\n");
        server.settle();
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("sub/main.nf", 1));
    }

    @Test
    void containReadFailureToOneFileAndRetryOnNextEvent() {
        var failing = new boolean[] { true };
        var files = new FailingFileAccess((path) -> failing[0] && path.endsWith("bad.nf"), false);
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.settle = false;
        });
        server.write("bad.nf", "workflow BAD { def x = 1 }\n");
        server.write("good.nf", "workflow GOOD { def y = 1 }\n");
        server.settle();
        assertThat(server.messages("good.nf")).containsExactly(UNUSED);
        assertThat(server.messages("bad.nf")).isEmpty();
        assertThat(server.client().getLogMessages()).anyMatch(m -> m.getMessage().startsWith("Failed to read " + server.path("bad.nf")));

        failing[0] = false;
        server.notify("bad.nf", FileChangeType.Changed);
        server.settle();
        assertThat(server.messages("bad.nf")).containsExactly(UNUSED);
        assertThat(server.messages("good.nf")).containsExactly(UNUSED);
    }

    @Test
    void containInternalErrorToOneFile() {
        var files = new FailingFileAccess((path) -> path.endsWith("bad.nf"), true);
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withFiles(files);
            o.settle = false;
        });
        server.write("bad.nf", "workflow BAD {}\n");
        server.write("good.nf", """
            include { BAD } from './bad.nf'
            workflow {
                def y = 1
                BAD()
            }
            """);
        server.settle();
        assertThat(server.messages("bad.nf")).containsExactly(DiagnosticsLedger.INTERNAL_ERROR_MESSAGE);
        assertThat(server.messages("good.nf")).contains(UNUSED);
        assertThat(server.client().getLogMessages()).anyMatch(m -> m.getMessage().contains("Internal error while parsing"));
    }

    @Test
    void containUnresolvableRemoteModule() {
        var server = start();
        server.write("main.nf", """
            include { X } from 'nf-core/missing'
            workflow {
                def y = 1
            }
            """);
        server.write("other.nf", "workflow OTHER { def z = 1 }\n");
        server.settle();
        assertThat(server.diagnostics("main.nf")).isNotEmpty();
        assertThat(server.messages("other.nf")).containsExactly(UNUSED);
    }

    @Test
    void rebuildDependentsOfAModuleThatARequestBuilt() {
        var server = start();
        server.write("mod.nf", "workflow FOO {}\n");
        server.write("main.nf", "include { FOO } from './mod.nf'\nworkflow { FOO() }\n");
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();

        server.open("mod.nf", "workflow BAR {}\n");
        // builds only the module, so the dependent is stale only by stamp
        server.call(Need.FILE, "mod.nf", null, (context) -> null);
        server.settle();
        assertThat(server.messages("main.nf")).isNotEmpty();
    }

    @Test
    void rebuildAnEditedFileWhenItIsNewlyIncluded() {
        var server = start();
        server.write("mod.nf", "workflow FOO {}\n");
        server.write("main.nf", "workflow {}\n");
        server.settle();

        server.open("mod.nf", "workflow BAR {}\n");
        server.open("main.nf", "include { BAR } from './mod.nf'\nworkflow { BAR() }\n");
        var before = server.parses();
        var errors = server.call(Need.FILE, "main.nf", List.<String>of(), (context) ->
            context.project().scriptView().getErrors(URI.create(server.uri("main.nf"))).stream().map(e -> e.getOriginalMessage()).toList());
        assertThat(errors).isEmpty();
        // main.nf is never analysed against the previous build of mod.nf
        assertThat(server.parsesSince(before)).isEqualTo(Map.of("main.nf", 1, "mod.nf", 1));
        assertThat(server.publishes("main.nf")).allMatch(List::isEmpty);
    }

    @Test
    void publishTheDocumentVersionOfTheBuild() {
        var server = start();
        server.open("main.nf", "workflow { def x = 1 }\n");
        server.change("main.nf", "workflow { def y = 1 }\n");
        server.settle();
        var uri = server.uri("main.nf");
        assertThat(server.client().getPublishLog()).filteredOn(p -> p.getUri().equals(uri)).last()
            .satisfies(p -> assertThat(p.getVersion()).isEqualTo(2));
    }

    @Test
    void keepTheBuildOfAModuleOpenedOrClosedWithUnchangedText() {
        var server = start();
        var module = "workflow FOO { def x = 1 }\n";
        server.write("mod.nf", module);
        for( int i = 0; i < 5; i++ )
            server.write("main" + i + ".nf", "include { FOO } from './mod.nf'\nworkflow W" + i + " { FOO() }\n");
        server.settle();
        var before = server.parses();

        server.open("mod.nf", module);
        server.settle();
        server.close("mod.nf");
        server.settle();
        assertThat(server.parsesSince(before)).isEmpty();
        assertThat(server.messages("mod.nf")).containsExactly(UNUSED);
        assertThat(server.client().getPublishLog()).last().satisfies(p -> assertThat(p.getVersion()).isNull());

        // the dependents still refer to the indexed module
        assertThat(server.includeTarget("main0.nf")).isEqualTo(URI.create(server.uri("mod.nf")));

        // a real change still rebuilds the dependents
        server.open("mod.nf", "workflow BAR {}\n");
        server.settle();
        assertThat(server.parsesSince(before)).isEqualTo(Map.of(
            "mod.nf", 1, "main0.nf", 1, "main1.nf", 1, "main2.nf", 1, "main3.nf", 1, "main4.nf", 1));
        assertThat(server.messages("main0.nf")).isNotEmpty();
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

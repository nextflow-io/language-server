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


package nextflow.lsp.services.script;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.services.LanguageServerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for resolving Groovy classes in the `lib` directory.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptLibDirTest {

    static final String GREETER = """
        class Greeter {
            static String greet(String name) {
                return "Hello, ${name}!"
            }
        }
        """;

    static final String WIDGET = """
        package acme

        class Widget {
            String name
        }
        """;

    final Path libDir = Path.of(URI.create(workspaceRootUri())).resolve("lib");

    @AfterEach
    void deleteLibDir() throws IOException {
        if( !Files.exists(libDir) )
            return;
        try( var paths = Files.walk(libDir) ) {
            for( var path : paths.sorted(Comparator.reverseOrder()).toList() )
                Files.delete(path);
        }
    }

    void writeLib(String path, String contents) throws IOException {
        var file = libDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents.stripIndent());
    }

    @Test
    void resolveClassInLibDirectory() throws IOException {
        writeLib("Greeter.groovy", GREETER);
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println(Greeter.greet('world'))
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void resolveClassInLibSubdirectoryByFullyQualifiedName() throws IOException {
        writeLib("acme/Widget.groovy", WIDGET);
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println(new acme.Widget(name: 'sprocket').name)
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void doNotResolvePackagedClassBySimpleName() throws IOException {
        writeLib("acme/Widget.groovy", WIDGET);
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println(new Widget(name: 'sprocket').name)
            }
            """);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).getMessage()).contains("Widget");
    }

    @Test
    void resolveLibClassUsedAsTypeAnnotation() throws IOException {
        writeLib("Greeter.groovy", GREETER);
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            def describe(Greeter greeter) {
                return greeter.toString()
            }

            workflow {
                println(describe(null))
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void reportErrorForClassNotInLibDirectory() throws IOException {
        writeLib("Greeter.groovy", GREETER);
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println(Gruter.greet('world'))
            }
            """);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).getMessage()).contains("Gruter");
    }

    @Test
    void reportErrorWhenThereIsNoLibDirectory() {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println(Greeter.greet('world'))
            }
            """);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).getMessage()).contains("Greeter");
    }

    @Test
    void resolveLibClassWhenLibDirectoryIsCreatedAfterStartup() throws IOException {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        writeLib("Greeter.groovy", GREETER);
        open(service, uri, """
            workflow {
                println(Greeter.greet('world'))
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void pickUpChangesToLibClassWithoutRestarting() throws IOException {
        writeLib("Greeter.groovy", GREETER);
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");
        var contents = """
            workflow {
                println(Greeter.greet('world'))
            }
            """;

        open(service, uri, contents);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();

        writeLib("Greeter.groovy", GREETER.replace("class Greeter", "class Salutation"));
        open(service, uri, contents);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).getMessage()).contains("Greeter");
    }

    @Test
    void reportDiagnosticsWhenLibClassHasSyntaxError() throws IOException {
        writeLib("Greeter.groovy", "class Greeter {");
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println(Greeter.greet('world'))
                foo()
            }
            """);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).anyMatch(d -> d.getMessage().contains("foo"));
    }

    @Test
    void doNotFailWhenThereIsNoWorkspaceRoot() {
        var client = new TestLanguageClient();
        var configuration = LanguageServerConfiguration.defaults();
        var service = new ScriptService(null);
        service.connect(client);
        service.initialize(configuration, newConfigService());
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println('Hello!')
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

}

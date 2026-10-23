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

import java.util.List;

import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ScriptDiagnosticsTest {

    @Test
    void doNotReportDiagnosticsForValidScript() {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                println('Hello!')
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void resolveClassesFromNextflowRuntime() {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                def json = new groovy.json.JsonSlurper().parseText('{}')
                def text = new groovy.text.SimpleTemplateEngine().createTemplate('$x').make([x: 1]).toString()
                def yaml = new groovy.yaml.YamlSlurper().parseText('a: 1')
                def builder = new groovy.yaml.YamlBuilder()
                builder.call([a: 1])
                def map = new org.yaml.snakeyaml.Yaml().load('a: 1')
                println([json, text, yaml, builder, map])
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void reportSyntaxError() {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
            """);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).hasSizeGreaterThanOrEqualTo(1);
        assertThat(diagnostics).allMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
        assertThat(diagnostics).allMatch(d -> "nextflow".equals(d.getSource()));
    }

    @Test
    void reportUnresolvedInclude() {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        open(service, uri, """
            include { FOO } from './does-not-exist.nf'

            workflow {
                FOO()
            }
            """);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).hasSizeGreaterThanOrEqualTo(1);
        assertThat(diagnostics).anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
    }

    @Test
    void resolveTypesForIncludedScriptsBeforeIncludingScripts() {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        // filenames chosen so the entry file iterates before its modules in
        // the unordered change set, exercising the check-ordering bug
        var mainUri = getUri("workflow.nf");
        var producerUri = getUri("producer.nf");
        var consumerUri = getUri("consumer.nf");

        open(service, producerUri, """
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
            """);
        open(service, consumerUri, """
            nextflow.enable.types = true

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
            """);
        open(service, mainUri, """
            nextflow.enable.types = true

            include { PRODUCER } from './producer.nf'
            include { CONSUMER } from './consumer.nf'

            workflow {
                ch_produced = PRODUCER(channel.of(record(id: 'sample1')))
                CONSUMER(ch_produced)
            }
            """);
        service.updateNow();
        var diagnostics = client.getDiagnostics(mainUri);
        assertThat(diagnostics)
            .extracting(Diagnostic::getMessage)
            .noneMatch(m -> m.contains("is not compatible with process input"));
    }

    @Test
    void clearDiagnosticsWhenErrorIsFixed() {
        var client = new TestLanguageClient();
        var service = getScriptService(client);
        var uri = getUri("main.nf");

        // a file with a syntax error is opened
        open(service, uri, """
            workflow {
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isNotEmpty();

        // the error is fixed
        open(service, uri, """
            workflow {
                println('Hello!')
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    /**
     * Error messages for a script that uses the given plugin.
     */
    static List<String> pluginErrors(String plugin, String script) {
        var configService = newConfigService();
        open(configService, getUri("nextflow.config"), "plugins { id '" + plugin + "' }");
        configService.updateNow();
        var client = new TestLanguageClient();
        var service = getScriptService(client, configService);
        var uri = getUri("main.nf");
        open(service, uri, script);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        // otherwise type checking is skipped and the test passes vacuously
        assertThat(diagnostics)
            .extracting(Diagnostic::getMessage)
            .noneMatch(m -> m.contains("does not exist or is not specified"));
        return diagnostics.stream()
            .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
            .map(Diagnostic::getMessage)
            .toList();
    }

    static String typedScriptCalling(String statement) {
        return """
            nextflow.enable.types = true

            include { samplesheetToList ; paramsHelp ; validateParameters } from 'plugin/nf-schema'

            workflow {
                %s
            }
            """.formatted(statement);
    }

    @Test
    void resolvePluginIncludesBeforeConfigFileIsCompiled() {
        var configService = newConfigService();
        var client = new TestLanguageClient();
        var service = getScriptService(client, configService);
        var uri = getUri("main.nf");

        open(configService, getUri("nextflow.config"), "plugins { id 'nf-schema@2.7.3' }");
        open(service, uri, """
            include { paramsHelp } from 'plugin/nf-schema'

            workflow {
                println paramsHelp()
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void resolveOverloadedPluginFunctionsInTypedScript() {
        var errors = pluginErrors("nf-schema@2.7.3", """
            nextflow.enable.types = true

            include { samplesheetToList ; paramsHelp ; validateParameters ; paramsSummaryMap } from 'plugin/nf-schema'

            workflow {
                def rows: List = samplesheetToList('s.csv', 'schema.json')
                samplesheetToList(file('s.csv'), file('schema.json'))
                def help: String = paramsHelp()
                paramsHelp([:])
                paramsHelp('nextflow run main.nf')
                validateParameters()
                validateParameters([:])
                paramsSummaryMap(workflow)
                println rows
                println help
            }
            """);
        assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        samplesheetToList('s.csv') | samplesheetToList
        paramsHelp(42)             | paramsHelp
        validateParameters(42)     | validateParameters
        """)
    void reportCallMatchingNoSignatureOfPluginFunctionInTypedScript(String call, String function) {
        var errors = pluginErrors("nf-schema@2.7.3", typedScriptCalling(call));
        assertThat(errors).singleElement(as(STRING))
            .contains("Function `" + function + "` (with multiple signatures)");
    }

    @Test
    void reportPluginFunctionReturnValueOfWrongTypeInTypedScript() {
        var errors = pluginErrors("nf-schema@2.7.3",
            typedScriptCalling("def n: Integer = paramsHelp()\n    println n"));
        assertThat(errors).singleElement(as(STRING)).contains("cannot be assigned");
    }

    @Test
    void doNotCheckPluginFunctionArgumentsInUntypedScript() {
        var errors = pluginErrors("nf-schema@2.7.3", """
            include { samplesheetToList ; paramsHelp } from 'plugin/nf-schema'

            workflow {
                samplesheetToList('s.csv')
                println paramsHelp()
            }
            """);
        assertThat(errors).isEmpty();
    }

}

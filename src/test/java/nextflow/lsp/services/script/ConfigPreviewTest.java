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
import java.util.Map;
import java.util.stream.Stream;

import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.services.LanguageServerConfiguration;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static nextflow.lsp.TestUtils.*;
import static nextflow.lsp.util.JsonUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigPreviewTest {

    static final String SCRIPT = """
        process ALIGN {
            label 'big'
            cpus 2

            script:
            \"""
            echo align
            \"""
        }
        """;

    static final String UNLABELED = """
        process ALIGN {
            cpus 2

            script:
            \"""
            echo align
            \"""
        }
        """;

    static final String CONFIG = """
        includeConfig 'conf/extra.config'

        process {
            cpus = 1

            withLabel: big {
                cpus = 8
            }
        }

        profiles {
            hpc {
                process {
                    withName: ALIGN {
                        cpus = 64
                    }
                }
            }
        }
        """;

    static final String EXTRA_CONFIG = """
        process {
            withName: ALIGN {
                memory = '16.GB'
            }
        }
        """;

    /**
     * A layer of a directive, as (source, value, active).
     */
    record Layer(String source, String value, boolean active) {
    }

    @AfterEach
    void cleanup() {
        deleteWorkspaceFile("nextflow.config");
        deleteWorkspaceFile("conf/extra.config");
        deleteWorkspaceFile("modules/align.nf");
        deleteWorkspaceFile("subworkflows/sub.nf");
        deleteWorkspaceFile("main.nf");
    }

    /**
     * The config files are written to disk and never opened, so that the
     * preview has to scan the workspace to find them, as it does in practice
     * when only a script is open.
     */
    static Map<?,?> previewConfig(List<String> profiles) {
        writeWorkspaceFile("nextflow.config", CONFIG);
        writeWorkspaceFile("conf/extra.config", EXTRA_CONFIG);
        var configService = newConfigService();
        var scriptService = getScriptService(new TestLanguageClient(), configService);

        var uri = getUri("main.nf");
        open(scriptService, uri, SCRIPT);
        var arguments = List.of(asJson(uri), asJson("ALIGN"), asJson(profiles), asJson(null));
        return (Map<?,?>) scriptService.executeCommand("nextflow.server.previewConfig", arguments, LanguageServerConfiguration.defaults());
    }

    /**
     * Open a config file and a script, then preview the config for a process
     * in the script.
     *
     * @param config
     * @param script
     * @param process
     * @param profiles
     * @param qualifiedName
     */
    static Map<?,?> preview(String config, String script, String process, List<String> profiles, String qualifiedName) {
        var services = getScriptAndConfigServices();
        var scriptService = services.script();
        var configService = services.config();
        open(configService, getUri("nextflow.config"), config);
        configService.updateNow();

        var uri = getUri("main.nf");
        open(scriptService, uri, script);
        var arguments = List.of(asJson(uri), asJson(process), asJson(profiles), asJson(qualifiedName));
        return (Map<?,?>) scriptService.executeCommand("nextflow.server.previewConfig", arguments, LanguageServerConfiguration.defaults());
    }

    static Map<?,?> preview(String config) {
        return preview(config, SCRIPT, "ALIGN", List.of(), null);
    }

    static Map<?,?> result(Map<?,?> response) {
        return (Map<?,?>) response.get("result");
    }

    /**
     * Get the raw layers of a directive, or null if no layer sets it.
     *
     * @param response
     * @param directive
     */
    static List<Map<?,?>> rawLayers(Map<?,?> response, String directive) {
        var directives = (List<?>) result(response).get("directives");
        return directives.stream()
            .map(d -> (Map<?,?>) d)
            .filter(d -> directive.equals(d.get("name")))
            .findFirst()
            .map(d -> ((List<?>) d.get("layers")).stream().<Map<?,?>>map(l -> (Map<?,?>) l).toList())
            .orElse(null);
    }

    static List<Layer> layers(Map<?,?> response, String directive) {
        var layers = rawLayers(response, directive);
        if( layers == null )
            return null;
        return layers.stream()
            .map(l -> new Layer((String) l.get("source"), (String) l.get("value"), (Boolean) l.get("active")))
            .toList();
    }

    static List<String> codeActionTitles(ScriptService service, String uri, int line, int character) {
        var position = new Position(line, character);
        var params = new CodeActionParams(new TextDocumentIdentifier(uri), new Range(position, position), new CodeActionContext(List.of()));
        return service.codeAction(params).stream()
            .map(CodeAction::getTitle)
            .toList();
    }

    @Test
    void orderLayersThatApplyToProcessByPrecedence() {
        var response = previewConfig(List.of());

        assertThat(result(response).get("process")).isEqualTo("ALIGN");
        assertThat(result(response).get("labels")).isEqualTo(List.of("big"));
        // a selector overrides the process body, which overrides the process scope
        assertThat(layers(response, "cpus")).containsExactly(
            new Layer("withLabel:big", "8", true),
            new Layer("process body", "2", false),
            new Layer("process config", "1", false)
        );
    }

    @Test
    void collectSettingsFromIncludedConfigFile() {
        var response = previewConfig(List.of());

        assertThat(layers(response, "memory")).containsExactly(
            new Layer("withName:ALIGN", "'16.GB'", true)
        );
        assertThat(rawLayers(response, "memory").get(0).get("file")).isEqualTo("conf/extra.config");
    }

    @Test
    void applyProfileOnlyWhenItIsActive() {
        var response = previewConfig(List.of());

        assertThat(result(response).get("profiles")).isEqualTo(List.of("hpc"));
        assertThat(result(response).get("activeProfiles")).isEqualTo(List.of());
        assertThat(layers(response, "cpus").get(0)).isEqualTo(new Layer("withLabel:big", "8", true));

        response = previewConfig(List.of("hpc"));

        assertThat(result(response).get("activeProfiles")).isEqualTo(List.of("hpc"));
        assertThat(layers(response, "cpus")).containsExactly(
            new Layer("withName:ALIGN", "64", true),
            new Layer("withLabel:big", "8", false),
            new Layer("process body", "2", false),
            new Layer("process config", "1", false)
        );
        // the profile layer records where it came from
        assertThat(rawLayers(response, "cpus").get(0).get("profile")).isEqualTo("hpc");
    }

    @Test
    void doNotApplySelectorThatDoesNotMatchProcess() {
        var response = preview("""
            process {
                withName: OTHER {
                    cpus = 16
                }
                withLabel: '!big' {
                    cpus = 32
                }
            }
            """);

        assertThat(layers(response, "cpus")).containsExactly(
            new Layer("process body", "2", true)
        );
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        .*   | withLabel:.*   | 32 | true
        !.*  | process body   | 2  | true
        big  | process body   | 2  | true
        !big | withLabel:!big | 32 | true
        """)
    void matchLabelSelectorAgainstEmptyLabel(String selector, String source, String value, boolean active) {
        var config = """
            process {
                withLabel: '%s' {
                    cpus = 32
                }
            }
            """.formatted(selector);
        var response = preview(config, UNLABELED, "ALIGN", List.of(), null);

        assertThat(layers(response, "cpus").get(0)).isEqualTo(new Layer(source, value, active));
    }

    @Test
    void doNotPromoteSelectorThatProfileOverridesInPlace() {
        var response = preview("""
            process {
                withName: 'ALIGN' {
                    cpus = 1
                }
                withName: 'A.*' {
                    cpus = 9
                }
            }

            profiles {
                docker {
                    process {
                        withName: 'ALIGN' {
                            cpus = 4
                        }
                    }
                }
            }
            """, SCRIPT, "ALIGN", List.of("docker"), null);

        // a profile overrides `withName: ALIGN` where it is declared, so
        // `withName: A.*` is still applied after it
        assertThat(layers(response, "cpus").get(0)).isEqualTo(new Layer("withName:A.*", "9", true));
    }

    @Test
    void treatMissingProfileArgumentAsNoProfiles() {
        var response = preview("""
            process {
                cpus = 1
                memory = '4.GB'
            }
            """, SCRIPT, "ALIGN", null, null);

        assertThat(layers(response, "memory")).containsExactly(
            new Layer("process config", "'4.GB'", true)
        );
    }

    @Test
    void reportErrorWhenProcessDoesNotExist() {
        var response = preview("""
            process.cpus = 1
            """, SCRIPT, "NOPE", List.of(), null);

        assertThat(response.get("error")).isEqualTo("Process 'NOPE' was not found.");
    }

    @Test
    void handleQuotedProfileName() {
        var response = preview("""
            profiles {
                'all-reads' {
                    process.cpus = 99
                }
            }
            """, SCRIPT, "ALIGN", List.of("all-reads"), null);

        assertThat(result(response).get("profiles")).isEqualTo(List.of("all-reads"));
        assertThat(layers(response, "cpus")).containsExactly(
            new Layer("process body", "2", true),
            new Layer("process config", "99", false)
        );
    }

    @Test
    void showDynamicValueAsSourceText() {
        var response = preview("""
            process.memory = '2.GB'
            """, """
            process ALIGN {
                memory { 4.GB * task.attempt }

                script:
                \"""
                echo align
                \"""
            }
            """, "ALIGN", List.of(), null);

        // a closure is shown as written, since the language server cannot evaluate it
        assertThat(layers(response, "memory")).containsExactly(
            new Layer("process body", "{ 4.GB * task.attempt }", true),
            new Layer("process config", "'2.GB'", false)
        );
    }

    static Stream<Arguments> configsWithErrors() {
        return Stream.of(
            Arguments.of("nextflow.config", """
                includeConfig 'conf/missing.config'

                process.cpus = 4
                """),
            Arguments.of("nextflow.config", """
                process {
                    cpus =
                """)
        );
    }

    @ParameterizedTest
    @MethodSource("configsWithErrors")
    void blockPreviewWhenConfigFileHasErrors(String file, String config) {
        var response = preview(config);

        assertThat(response.get("error")).isEqualTo("Config preview cannot be shown because " + file + " has errors.");
    }

    static Stream<Arguments> invocations() {
        return Stream.of(
            Arguments.of("a process invocation", 8, 4, List.of("Preview config for SUB:ALIGN", "Preview config for SUB2:SUB:ALIGN")),
            Arguments.of("a workflow invocation", 12, 4, List.of()),
            Arguments.of("the definition", 0, 8, List.of())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invocations")
    void offerCodeActionForEachQualifiedNameOfInvocation(String location, int line, int character, List<String> expected) {
        var services = getScriptAndConfigServices();
        var scriptService = services.script();
        var uri = getUri("main.nf");
        open(scriptService, uri, """
            process ALIGN {
                script:
                \"""
                echo align
                \"""
            }

            workflow SUB {
                ALIGN()
            }

            workflow SUB2 {
                SUB()
            }

            workflow {
                SUB()
                SUB2()
            }
            """);
        scriptService.updateNow();

        assertThat(codeActionTitles(scriptService, uri, line, character)).isEqualTo(expected);
    }

    @Test
    void qualifyInvocationWithIncludeAlias() {
        writeWorkspaceFile("modules/align.nf", """
            process ALIGN {
                script:
                \"""
                echo align
                \"""
            }
            """);
        writeWorkspaceFile("subworkflows/sub.nf", """
            include { ALIGN as ALIGN_DNA } from '../modules/align.nf'

            workflow SUB {
                main:
                ALIGN_DNA()
            }
            """);
        var main = """
            include { SUB } from './subworkflows/sub.nf'

            workflow {
                SUB()
            }
            """;
        // the workspace scan reads from disk and drops in-memory documents
        writeWorkspaceFile("main.nf", main);
        var scriptService = getScriptService();
        // getScriptService opens main.nf as an empty stub, so restore it
        open(scriptService, getUri("main.nf"), main);
        scriptService.updateNow();
        // the first update defers the workspace scan
        scriptService.updateNow();

        // the qualified name uses the alias at the call site, not the process name
        assertThat(codeActionTitles(scriptService, getUri("subworkflows/sub.nf"), 4, 4))
            .containsExactly("Preview config for SUB:ALIGN_DNA");
    }

    @Test
    void matchSelectorAgainstQualifiedNameAndAlias() {
        var response = preview("""
            process {
                withName: 'SUB:ALIGN_DNA' {
                    cpus = 16
                }
                withName: ALIGN_DNA {
                    cpus = 8
                }
                withName: ALIGN {
                    cpus = 4
                }
                withName: OTHER {
                    cpus = 32
                }
            }
            """, SCRIPT, "ALIGN", List.of(), "SUB:ALIGN_DNA");

        assertThat(result(response).get("process")).isEqualTo("SUB:ALIGN_DNA");
        // the qualified name outranks the alias, which outranks the process name
        assertThat(layers(response, "cpus")).extracting(Layer::source).containsExactly(
            "withName:SUB:ALIGN_DNA",
            "withName:ALIGN_DNA",
            "withName:ALIGN",
            "process body"
        );
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        a wildcard over the process name             | ALIGN.*          | true
        a prefix of the process name                 | ALI              | false
        a wildcard over the enclosing scope          | .*:SUB:ALIGN_DNA | true
        an exact selector for a nested invocation    | SUB:ALIGN_DNA    | false
        a negated selector matching another name     | !ALIGN_DNA       | true
        a pattern that differs only in case          | !align.*         | true
        an alternation                               | "ALIGN|REPORT"   | true
        """)
    void matchNameSelectorAsRegularExpression(String description, String pattern, boolean applies) {
        var config = """
            process {
                withName: '%s' {
                    queue = 'long'
                }
            }
            """.formatted(pattern);
        var response = preview(config, SCRIPT, "ALIGN", List.of(), "SUB2:SUB:ALIGN_DNA");

        assertThat(layers(response, "queue") != null).isEqualTo(applies);
    }

    @Test
    void rankNameSelectorByTheNameThatItMatched() {
        var response = preview("""
            process {
                withName: 'SUB:ALIGN_DNA' {
                    cpus = 128
                }
                withName: ALIGN {
                    cpus = 16
                }
                withName: 'ALIGN.*' {
                    memory = '64.GB'
                }
                withName: ALIGN {
                    memory = '8.GB'
                }
            }
            """, SCRIPT, "ALIGN", List.of(), "SUB:ALIGN_DNA");

        // the qualified selector wins even though the weaker one is declared later
        assertThat(layers(response, "cpus").get(0)).isEqualTo(new Layer("withName:SUB:ALIGN_DNA", "128", true));
        // a selector that matches several names is ranked by the strongest one
        assertThat(layers(response, "memory").get(0)).isEqualTo(new Layer("withName:ALIGN.*", "'64.GB'", true));
    }

    @Test
    void doNotPickWinnerForRepeatableDirective() {
        var response = preview("""
            process {
                withLabel: big {
                    label = 'retry_high'
                }
            }
            """);

        // Nextflow appends labels rather than overriding them, so every layer applies
        assertThat(layers(response, "label")).containsExactly(
            new Layer("withLabel:big", "'retry_high'", true),
            new Layer("process body", "'big'", true)
        );
        // a directive that overrides still has a single winner
        assertThat(layers(response, "cpus")).containsExactly(
            new Layer("process body", "2", true)
        );
    }

}

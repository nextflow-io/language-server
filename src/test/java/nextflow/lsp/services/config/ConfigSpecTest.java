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

package nextflow.lsp.services.config;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

import nextflow.config.control.ConfigParser;
import nextflow.config.control.ConfigResolveVisitor;
import nextflow.lsp.spec.PluginRef;
import nextflow.lsp.spec.PluginSpec;
import nextflow.script.dsl.Types;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigSpecTest {

    static final ConfigParser CONFIG_PARSER = new ConfigParser();

    static final ConfigSpecVisitor.PluginLookup NO_PLUGINS = new ConfigSpecVisitor.PluginLookup() {
        @Override
        public PluginSpec get(PluginRef ref) {
            return null;
        }

        @Override
        public void declare(URI uri, List<PluginRef> refs) {
        }
    };

    static SourceUnit parse(String contents) {
        var source = CONFIG_PARSER.parse("nextflow.config", contents);
        new ConfigResolveVisitor(source, CONFIG_PARSER.compiler().compilationUnit(), Types.DEFAULT_CONFIG_IMPORTS).visit();
        new ConfigSpecVisitor(source, NO_PLUGINS).visit();
        return source;
    }

    static List<SyntaxException> getErrors(String contents) {
        var source = parse(contents);
        var errorCollector = source.getErrorCollector();
        if( !errorCollector.hasErrors() )
            return List.of();
        return errorCollector.getErrors().stream()
            .filter(e -> e instanceof SyntaxErrorMessage)
            .map(e -> ((SyntaxErrorMessage) e).getCause())
            .sorted(ERROR_COMPARATOR)
            .toList();
    }

    static List<WarningMessage> getWarnings(String contents) {
        var source = parse(contents);
        var warnings = source.getErrorCollector().getWarnings();
        if( warnings == null )
            return List.of();
        return warnings.stream()
            .sorted(WARNING_COMPARATOR)
            .toList();
    }

    static final Comparator<SyntaxException> ERROR_COMPARATOR = (a, b) -> {
        return a.getStartLine() != b.getStartLine()
            ? a.getStartLine() - b.getStartLine()
            : a.getStartColumn() - b.getStartColumn();
    };

    static final Comparator<WarningMessage> WARNING_COMPARATOR = (w1, w2) -> {
        var a = w1.getContext();
        var b = w2.getContext();
        return a.getStartLine() != b.getStartLine()
            ? a.getStartLine() - b.getStartLine()
            : a.getStartColumn() - b.getStartColumn();
    };

    @Test
    void checkEnvSettings() {
        var errors = getErrors("""
            env {
                foo.bar = 'HELLO'
            }
            """);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getStartLine()).isEqualTo(2);
        assertThat(errors.get(0).getStartColumn()).isEqualTo(5);
        assertThat(errors.get(0).getOriginalMessage()).isEqualTo("Invalid environment variable name 'foo.bar'");

        errors = getErrors("""
            env {
                FOO_BAR = 'HELLO'
            }
            """);
        assertThat(errors).isEmpty();
    }

    @Test
    void doNotCheckParams() {
        var warnings = getWarnings("""
            params {
                publish_mode = 'copy'
            }
            """);
        assertThat(warnings).isEmpty();
    }

    @Test
    void inferParamTypesFromParamDeclarations() {
        var warnings = getWarnings("""
            params.cpus = 'two'
            process.cpus = params.cpus
            """);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("Config option 'process.cpus'");

        warnings = getWarnings("""
            params.cpus = 2
            process.cpus = params.cpus
            """);
        assertThat(warnings).isEmpty();

        warnings = getWarnings("""
            process.cpus = params.cpus
            """);
        assertThat(warnings).isEmpty();
    }

    @Test
    void doNotInferTypeOfParamDeclaredTwiceWithDifferentTypes() {
        var warnings = getWarnings("""
            params.cpus = 2
            profiles {
                big {
                    params.cpus = 'many'
                }
            }
            process.cpus = params.cpus
            """);
        assertThat(warnings).isEmpty();
    }

    @Test
    void doNotCheckProcessExtSettings() {
        var warnings = getWarnings("""
            process {
                ext.prefix = { meta.id }
            }
            """);
        assertThat(warnings).isEmpty();
    }

    @Test
    void reportErrorForUnrecognizedConfigSettings() {
        var warnings = getWarnings("""
            wokDir = 'work'
            workDir = 'work'

            process {
                cpu = 2
                cpus = 2
            }
            """);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0).getContext().getStartLine()).isEqualTo(1);
        assertThat(warnings.get(0).getContext().getStartColumn()).isEqualTo(1);
        assertThat(warnings.get(0).getMessage()).isEqualTo("Unrecognized config option 'wokDir'");
        assertThat(warnings.get(1).getContext().getStartLine()).isEqualTo(5);
        assertThat(warnings.get(1).getContext().getStartColumn()).isEqualTo(5);
        assertThat(warnings.get(1).getMessage()).isEqualTo("Unrecognized config option 'process.cpu'");

        warnings = getWarnings("""
            profiles {
                test {
                    wokDir = 'work'
                    workDir = 'work'

                    process {
                        cpu = 2
                        cpus = 2
                    }
                }
            }
            """);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0).getContext().getStartLine()).isEqualTo(3);
        assertThat(warnings.get(0).getContext().getStartColumn()).isEqualTo(9);
        assertThat(warnings.get(0).getMessage()).isEqualTo("Unrecognized config option 'wokDir'");
        assertThat(warnings.get(1).getContext().getStartLine()).isEqualTo(7);
        assertThat(warnings.get(1).getContext().getStartColumn()).isEqualTo(13);
        assertThat(warnings.get(1).getMessage()).isEqualTo("Unrecognized config option 'process.cpu'");
    }

    @Test
    void ignoreProcessSelectors() {
        var warnings = getWarnings("""
            process {
                withLabel:'foobar' {
                    cpus = 2
                }
                withName:'foobar' {
                    cpus = 2
                }
                withName:'.*TASK.*' {
                    cpus = 2
                }
            }
            """);
        assertThat(warnings).isEmpty();
    }

    @Test
    void reportErrorForInvalidType() {
        var warnings = getWarnings("""
            process.cpus = '2'
            """);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getContext().getStartLine()).isEqualTo(1);
        assertThat(warnings.get(0).getContext().getStartColumn()).isEqualTo(1);
        assertThat(warnings.get(0).getMessage()).isEqualTo("Config option 'process.cpus' with type Integer cannot be assigned to value with type String");

        warnings = getWarnings("""
            process.cpus = 2
            process.cpus = '2'.toInteger()
            """);
        assertThat(warnings).isEmpty();

        warnings = getWarnings("""
            trace.fields = true
            """);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getContext().getStartLine()).isEqualTo(1);
        assertThat(warnings.get(0).getContext().getStartColumn()).isEqualTo(1);
        assertThat(warnings.get(0).getMessage()).isEqualTo("Config option 'trace.fields' cannot be assigned to value with type Boolean -- valid types are: List<String>, String");
    }

    @Test
    void checkReturnTypeForDynamicConfigSettings() {
        var warnings = getWarnings("""
            process.cpus = { '2' }
            """);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getContext().getStartLine()).isEqualTo(1);
        assertThat(warnings.get(0).getContext().getStartColumn()).isEqualTo(1);
        assertThat(warnings.get(0).getMessage()).isEqualTo("Config option 'process.cpus' with type Integer cannot be assigned to value with type String");

        warnings = getWarnings("""
            process.cpus = { 2 * task.attempt }
            """);
        assertThat(warnings).isEmpty();
    }

}

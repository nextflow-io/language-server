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

package nextflow.lsp.services.config

import nextflow.config.control.ConfigParser
import nextflow.config.control.ConfigResolveVisitor
import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.lsp.spec.PluginSpecCache
import nextflow.script.types.Types
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.control.messages.WarningMessage
import org.codehaus.groovy.syntax.SyntaxException
import spock.lang.Shared
import spock.lang.Specification

import static nextflow.script.types.TypeCheckingUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigSpecTest extends Specification {

    @Shared
    ConfigParser configParser

    def setupSpec() {
        configParser = new ConfigParser()
    }

    SourceUnit parse(String contents) {
        def configuration = LanguageServerConfiguration.defaults()
        def pluginSpecCache = new PluginSpecCache(configuration.pluginRegistryUrl())
        def source = configParser.parse('nextflow.config', contents.stripIndent())
        new ConfigResolveVisitor(source, configParser.compiler().compilationUnit(), Types.DEFAULT_CONFIG_IMPORTS).visit()
        new ConfigSpecVisitor(source, pluginSpecCache, true).visit()
        return source
    }

    def getErrors(String contents) {
        final source = parse(contents)
        final errorCollector = source.getErrorCollector()
        if( !errorCollector.hasErrors() )
            return Collections.emptyList()
        return errorCollector.getErrors().stream()
            .filter(e -> e instanceof SyntaxErrorMessage)
            .map(e -> e.cause)
            .sorted(ERROR_COMPARATOR)
            .toList()
    }

    def getWarnings(String contents) {
        final source = parse(contents)
        final warnings = source.getErrorCollector().getWarnings() ?: []
        return warnings.stream()
            .sorted(WARNING_COMPARATOR)
            .toList()
    }

    static final Comparator<SyntaxException> ERROR_COMPARATOR = (SyntaxException a, SyntaxException b) -> {
        return a.getStartLine() != b.getStartLine()
            ? a.getStartLine() - b.getStartLine()
            : a.getStartColumn() - b.getStartColumn()
    }

    static final Comparator<WarningMessage> WARNING_COMPARATOR = (WarningMessage w1, WarningMessage w2) -> {
        final a = w1.getContext()
        final b = w2.getContext()
        return a.getStartLine() != b.getStartLine()
            ? a.getStartLine() - b.getStartLine()
            : a.getStartColumn() - b.getStartColumn()
    }

    def 'should check env settings' () {
        when:
        def errors = getErrors(
            '''\
            env {
                foo.bar = 'HELLO'
            }
            '''
        )
        then:
        errors.size() == 1
        errors[0].getStartLine() == 2
        errors[0].getStartColumn() == 5
        errors[0].getOriginalMessage() == "Invalid environment variable name 'foo.bar'"

        when:
        errors = getErrors(
            '''\
            env {
                FOO_BAR = 'HELLO'
            }
            '''
        )
        then:
        errors.size() == 0
    }

    def 'should not check params' () {
        when:
        def warnings = getWarnings(
            '''\
            params {
                publish_mode = 'copy'
            }
            '''
        )
        then:
        warnings.size() == 0
    }

    def 'should not check process ext settings' () {
        when:
        def warnings = getWarnings(
            '''\
            process {
                ext.prefix = { meta.id }
            }
            '''
        )
        then:
        warnings.size() == 0
    }

    def 'should report error for unrecognized config settings' () {
        when:
        def warnings = getWarnings(
            '''\
            wokDir = 'work'
            workDir = 'work'

            process {
                cpu = 2
                cpus = 2
            }
            '''
        )
        then:
        warnings.size() == 2
        warnings[0].getContext().getStartLine() == 1
        warnings[0].getContext().getStartColumn() == 1
        warnings[0].getMessage() == "Unrecognized config option 'wokDir'"
        warnings[1].getContext().getStartLine() == 5
        warnings[1].getContext().getStartColumn() == 5
        warnings[1].getMessage() == "Unrecognized config option 'process.cpu'"

        when:
        warnings = getWarnings(
            '''\
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
            '''
        )
        then:
        warnings.size() == 2
        warnings[0].getContext().getStartLine() == 3
        warnings[0].getContext().getStartColumn() == 9
        warnings[0].getMessage() == "Unrecognized config option 'wokDir'"
        warnings[1].getContext().getStartLine() == 7
        warnings[1].getContext().getStartColumn() == 13
        warnings[1].getMessage() == "Unrecognized config option 'process.cpu'"
    }

    def 'should ignore process selectors' () {
        when:
        def warnings = getWarnings(
            '''\
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
            '''
        )
        then:
        warnings.size() == 0
    }

    def 'should report error for invalid type' () {
        when:
        def warnings = getWarnings(
            '''\
            process.cpus = '2'
            '''
        )
        then:
        warnings.size() == 1
        warnings[0].getContext().getStartLine() == 1
        warnings[0].getContext().getStartColumn() == 1
        warnings[0].getMessage() == "Config option 'process.cpus' with type Integer cannot be assigned to value with type String"

        when:
        warnings = getWarnings(
            '''\
            process.cpus = 2
            process.cpus = '2'.toInteger()
            '''
        )
        then:
        warnings.size() == 0

        when:
        warnings = getWarnings(
            '''\
            trace.fields = true
            '''
        )
        then:
        warnings.size() == 1
        warnings[0].getContext().getStartLine() == 1
        warnings[0].getContext().getStartColumn() == 1
        warnings[0].getMessage() == "Config option 'trace.fields' cannot be assigned to value with type Boolean -- valid types are: List, String"
    }

    def 'should check return type for dynamic config settings' () {
        when:
        def warnings = getWarnings(
            '''\
            process.cpus = { '2' }
            '''
        )
        then:
        warnings.size() == 1
        warnings[0].getContext().getStartLine() == 1
        warnings[0].getContext().getStartColumn() == 1
        warnings[0].getMessage() == "Config option 'process.cpus' with type Integer cannot be assigned to value with type String"

        when:
        warnings = getWarnings(
            '''\
            process.cpus = { 2 * task.attempt }
            '''
        )
        then:
        warnings.size() == 0
    }

}

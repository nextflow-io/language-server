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

package nextflow.lsp.services.script

import nextflow.lsp.TestLanguageClient
import nextflow.lsp.services.LanguageServerConfiguration
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*
import static nextflow.lsp.util.JsonUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigPreviewTest extends Specification {

    static final String SCRIPT = '''\
        process ALIGN {
            label 'big'
            cpus 2

            script:
            """
            echo align
            """
        }
        '''

    static final String CONFIG = '''\
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
        '''

    static final String EXTRA_CONFIG = '''\
        process {
            withName: ALIGN {
                memory = '16.GB'
            }
        }
        '''

    def cleanup() {
        deleteWorkspaceFile('nextflow.config')
        deleteWorkspaceFile('conf/extra.config')
        deleteWorkspaceFile('modules/align.nf')
        deleteWorkspaceFile('subworkflows/sub.nf')
        deleteWorkspaceFile('main.nf')
    }

    /**
     * The config files are written to disk and never opened, so that the
     * preview has to scan the workspace to find them, as it does in practice
     * when only a script is open.
     */
    def previewConfig(List profiles) {
        writeWorkspaceFile('nextflow.config', CONFIG)
        writeWorkspaceFile('conf/extra.config', EXTRA_CONFIG)
        def configService = newConfigService()
        def scriptService = getScriptService(new TestLanguageClient(), configService)

        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson(profiles), asJson(null) ]
        return scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())
    }

    def layers(Map response, String directive) {
        def entry = response.result.directives.find { it.name == directive }
        return entry?.layers?.collect { [ it.source, it.value, it.active ] }
    }

    def 'should order the layers that apply to a process by precedence' () {
        when:
        def response = previewConfig([])

        then:
        response.result.process == 'ALIGN'
        response.result.labels == ['big']
        and:
        'a selector overrides the process body, which overrides the process scope'
        layers(response, 'cpus') == [
            ['withLabel:big', '8', true],
            ['process body', '2', false],
            ['process config', '1', false],
        ]
    }

    def 'should collect settings from an included config file' () {
        when:
        def response = previewConfig([])

        then:
        layers(response, 'memory') == [
            ['withName:ALIGN', "'16.GB'", true],
        ]
        and:
        response.result.directives.find { it.name == 'memory' }.layers[0].file == 'conf/extra.config'
    }

    def 'should apply a profile only when it is active' () {
        when:
        def response = previewConfig([])

        then:
        response.result.profiles == ['hpc']
        response.result.activeProfiles == []
        layers(response, 'cpus').first() == ['withLabel:big', '8', true]

        when:
        response = previewConfig(['hpc'])

        then:
        response.result.activeProfiles == ['hpc']
        layers(response, 'cpus') == [
            ['withName:ALIGN', '64', true],
            ['withLabel:big', '8', false],
            ['process body', '2', false],
            ['process config', '1', false],
        ]
        and:
        'the profile layer records where it came from'
        response.result.directives.find { it.name == 'cpus' }.layers[0].profile == 'hpc'
    }

    def 'should not apply a selector that does not match the process' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), '''\
            process {
                withName: OTHER {
                    cpus = 16
                }
                withLabel: '!big' {
                    cpus = 32
                }
            }
            '''.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson([]), asJson(null) ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        layers(response, 'cpus') == [
            ['process body', '2', true],
        ]
    }

    def 'should report an error when the process does not exist' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('NOPE'), asJson([]), asJson(null) ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        response.error == "Process 'NOPE' was not found."
    }

    def 'should handle a quoted profile name' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), '''\
            profiles {
                'all-reads' {
                    process.cpus = 99
                }
            }
            '''.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson(['all-reads']), asJson(null) ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        response.result.profiles == ['all-reads']
        layers(response, 'cpus') == [
            ['process body', '2', true],
            ['process config', '99', false],
        ]
    }

    def 'should show a dynamic value as source text' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), '''\
            process.memory = '2.GB'
            '''.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, '''\
            process ALIGN {
                memory { 4.GB * task.attempt }

                script:
                """
                echo align
                """
            }
            '''.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson([]), asJson(null) ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        'a closure is shown as written, since the language server cannot evaluate it'
        layers(response, 'memory') == [
            ['process body', '{ 4.GB * task.attempt }', true],
            ['process config', "'2.GB'", false],
        ]
    }

    def 'should block the preview when a config file has errors' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), config.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson([]), asJson(null) ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        response.error == "Config preview cannot be shown because ${file} has errors."

        where:
        file                | config
        'nextflow.config'   | '''\
                              includeConfig 'conf/missing.config'

                              process.cpus = 4
                              '''
        'nextflow.config'   | '''\
                              process {
                                  cpus =
                              '''
    }

    def 'should offer a code action for each qualified name of an invocation' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        def uri = getUri('main.nf')
        open(scriptService, uri, '''\
            process ALIGN {
                script:
                """
                echo align
                """
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
            '''.stripIndent())

        when:
        def position = new Position(line, character)
        def params = new CodeActionParams(new TextDocumentIdentifier(uri), new Range(position, position), new CodeActionContext([]))
        def actions = scriptService.codeAction(params)

        then:
        actions*.title == expected

        where:
        location                | line  | character | expected
        'a process invocation'  | 8     | 4         | ['Preview config for SUB:ALIGN', 'Preview config for SUB2:SUB:ALIGN']
        'a workflow invocation' | 12    | 4         | []
        'the definition'        | 0     | 8         | []
    }

    def 'should qualify an invocation with the include alias' () {
        given:
        writeWorkspaceFile('modules/align.nf', '''\
            process ALIGN {
                script:
                """
                echo align
                """
            }
            ''')
        writeWorkspaceFile('subworkflows/sub.nf', '''\
            include { ALIGN as ALIGN_DNA } from '../modules/align.nf'

            workflow SUB {
                main:
                ALIGN_DNA()
            }
            ''')
        def main = '''\
            include { SUB } from './subworkflows/sub.nf'

            workflow {
                SUB()
            }
            '''
        // the workspace scan reads from disk and drops in-memory documents
        writeWorkspaceFile('main.nf', main)
        def scriptService = getScriptService()
        // getScriptService opens main.nf as an empty stub, so restore it
        open(scriptService, getUri('main.nf'), main.stripIndent())
        scriptService.updateNow()
        // the first update defers the workspace scan
        scriptService.updateNow()

        when:
        def position = new Position(4, 4)
        def params = new CodeActionParams(new TextDocumentIdentifier(getUri('subworkflows/sub.nf')), new Range(position, position), new CodeActionContext([]))
        def actions = scriptService.codeAction(params)

        then:
        'the qualified name uses the alias at the call site, not the process name'
        actions*.title == ['Preview config for SUB:ALIGN_DNA']
    }

    def 'should match a selector against the qualified name and the alias' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), '''\
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
            '''.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson([]), asJson('SUB:ALIGN_DNA') ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        response.result.process == 'SUB:ALIGN_DNA'
        and:
        'the qualified name outranks the alias, which outranks the process name'
        layers(response, 'cpus')*.first() == [
            'withName:SUB:ALIGN_DNA',
            'withName:ALIGN_DNA',
            'withName:ALIGN',
            'process body',
        ]
    }

    def 'should match a name selector as a regular expression' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), """\
            process {
                withName: '${pattern}' {
                    queue = 'long'
                }
            }
            """.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson([]), asJson('SUB2:SUB:ALIGN_DNA') ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        (layers(response, 'queue') != null) == applies

        where:
        description                                     | pattern              | applies
        'a wildcard over the process name'              | 'ALIGN.*'            | true
        'a prefix of the process name'                  | 'ALI'                | false
        'a wildcard over the enclosing scope'           | '.*:SUB:ALIGN_DNA'   | true
        'an exact selector for a nested invocation'     | 'SUB:ALIGN_DNA'      | false
        'a negated selector matching another name'      | '!ALIGN_DNA'         | true
        'a pattern that differs only in case'           | '!align.*'           | true
        'an alternation'                                | 'ALIGN|REPORT'       | true
    }

    def 'should rank a name selector by the name that it matched' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), '''\
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
            '''.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson([]), asJson('SUB:ALIGN_DNA') ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        'the qualified selector wins even though the weaker one is declared later'
        layers(response, 'cpus').first() == ['withName:SUB:ALIGN_DNA', '128', true]
        and:
        'a selector that matches several names is ranked by the strongest one'
        layers(response, 'memory').first() == ['withName:ALIGN.*', "'64.GB'", true]
    }

    def 'should not pick a winner for a repeatable directive' () {
        given:
        def (scriptService, configService) = getScriptAndConfigServices()
        open(configService, getUri('nextflow.config'), '''\
            process {
                withLabel: big {
                    label = 'retry_high'
                }
            }
            '''.stripIndent())
        configService.updateNow()

        when:
        def uri = getUri('main.nf')
        open(scriptService, uri, SCRIPT.stripIndent())
        def arguments = [ asJson(uri), asJson('ALIGN'), asJson([]), asJson(null) ]
        def response = scriptService.executeCommand('nextflow.server.previewConfig', arguments, LanguageServerConfiguration.defaults())

        then:
        'Nextflow appends labels rather than overriding them, so every layer applies'
        layers(response, 'label') == [
            ['withLabel:big', "'retry_high'", true],
            ['process body', "'big'", true],
        ]
        and:
        'a directive that overrides still has a single winner'
        layers(response, 'cpus') == [
            ['process body', '2', true],
        ]
    }

}

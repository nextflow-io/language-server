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
import org.eclipse.lsp4j.DiagnosticSeverity
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptDiagnosticsTest extends Specification {

    def 'should not report diagnostics for a valid script' () {
        given:
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println('Hello!')
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'should report a syntax error' () {
        given:
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
            ''')
        service.updateNow()
        def diagnostics = client.getDiagnostics(uri)
        then:
        diagnostics.size() >= 1
        diagnostics.every { it.getSeverity() == DiagnosticSeverity.Error }
        diagnostics.every { it.getSource() == 'nextflow' }
    }

    def 'should report an unresolved include' () {
        given:
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            include { FOO } from './does-not-exist.nf'

            workflow {
                FOO()
            }
            ''')
        service.updateNow()
        def diagnostics = client.getDiagnostics(uri)
        then:
        diagnostics.size() >= 1
        diagnostics.any { it.getSeverity() == DiagnosticSeverity.Error }
    }

    def 'should resolve types for included scripts before including scripts' () {
        given:
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        // filenames chosen so the entry file iterates before its modules in
        // the unordered change set, exercising the check-ordering bug
        def mainUri = getUri('workflow.nf')
        def producerUri = getUri('producer.nf')
        def consumerUri = getUri('consumer.nf')

        when:
        open(service, producerUri, '''\
            nextflow.enable.types = true

            process PRODUCER {
                input:
                record(id: String)

                output:
                record(id: id, data: file('data.txt'))

                script:
                """
                echo hello > data.txt
                """
            }
            ''')
        open(service, consumerUri, '''\
            nextflow.enable.types = true

            process CONSUMER {
                input:
                record(id: String, data: Path)

                output:
                record(id: id)

                script:
                """
                cat ${data}
                """
            }
            ''')
        open(service, mainUri, '''\
            nextflow.enable.types = true

            include { PRODUCER } from './producer.nf'
            include { CONSUMER } from './consumer.nf'

            workflow {
                ch_produced = PRODUCER(channel.of(record(id: 'sample1')))
                CONSUMER(ch_produced)
            }
            ''')
        service.updateNow()
        def diagnostics = client.getDiagnostics(mainUri)
        then:
        diagnostics.findAll { it.message.contains('is not compatible with process input') } == []
    }

    def 'should clear diagnostics when an error is fixed' () {
        given:
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when: 'a file with a syntax error is opened'
        open(service, uri, '''\
            workflow {
            ''')
        service.updateNow()
        then:
        !client.getDiagnostics(uri).isEmpty()

        when: 'the error is fixed'
        open(service, uri, '''\
            workflow {
                println('Hello!')
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }


    def pluginErrors(String plugin, String script) {
        def configService = newConfigService()
        open(configService, getUri('nextflow.config'), "plugins { id '${plugin}' }")
        configService.updateNow()
        def client = new TestLanguageClient()
        def service = getScriptService(client, configService)
        def uri = getUri('main.nf')
        open(service, uri, script)
        service.updateNow()
        return client.getDiagnostics(uri)
            .findAll { it.getSeverity() == DiagnosticSeverity.Error }
            .collect { it.getMessage() }
    }

    def 'should resolve overloaded plugin functions in a typed script' () {
        when:
        def errors = pluginErrors('nf-schema@2.7.3', """\
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
            """)
        then:
        errors == []
    }

    def 'should report an invalid call to a plugin function in a typed script' () {
        when:
        def errors = pluginErrors('nf-schema@2.7.3', """\
            nextflow.enable.types = true

            include { samplesheetToList ; paramsHelp ; validateParameters } from 'plugin/nf-schema'

            workflow {
                ${call}
            }
            """)
        then:
        errors.size() == 1
        errors[0].contains(message)

        where:
        call                                            | message
        "samplesheetToList('s.csv')"                    | 'Function `samplesheetToList` (with multiple signatures)'
        'paramsHelp(42)'                                | 'Function `paramsHelp` (with multiple signatures)'
        'validateParameters(42)'                        | 'Function `validateParameters` (with multiple signatures)'
        'def n: Integer = paramsHelp()\n    println n'  | 'cannot be assigned'
    }

    def 'should not check plugin function arguments in an untyped script' () {
        when:
        def errors = pluginErrors('nf-schema@2.7.3', """\
            include { samplesheetToList ; paramsHelp } from 'plugin/nf-schema'

            workflow {
                samplesheetToList('s.csv')
                println paramsHelp()
            }
            """)
        then:
        errors == []
    }

}

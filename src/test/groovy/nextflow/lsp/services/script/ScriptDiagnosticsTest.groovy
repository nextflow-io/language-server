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

}

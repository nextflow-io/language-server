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
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 * Tests for including a pipeline -- the `params` / `workflow` / `output`
 * trio of a script -- as a named workflow.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptPipelineCompositionTest extends Specification {

    static final String PIPELINE = '''\
        nextflow.enable.types = true

        params {
            input: Path
            aligner: String = 'star'
        }

        workflow {
            main:
            ch_bams = channel.of(params.input)

            publish:
            bams = ch_bams
        }

        output {
            bams: Channel<Path> { path 'bams' }
        }
        '''

    ScriptService pipelineService(TestLanguageClient client = new TestLanguageClient()) {
        def service = getScriptService(client)
        open(service, getUri('rnaseq.nf'), PIPELINE)
        return service
    }

    def 'should not report diagnostics for a pipeline include' () {
        given:
        def client = new TestLanguageClient()
        def service = pipelineService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            nextflow.enable.types = true

            include {
                params as RnaseqParams ;
                workflow as RNASEQ
            } from './rnaseq.nf'

            params {
                rnaseq: RnaseqParams
            }

            workflow {
                main:
                rnaseq = RNASEQ( params.rnaseq )

                publish:
                bams = rnaseq
            }

            output {
                bams: Channel<Path> { path 'bams' }
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'should get the hover of an included pipeline' () {
        given:
        def service = pipelineService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            nextflow.enable.types = true

            include { workflow as RNASEQ } from './rnaseq.nf'

            workflow {
                main:
                RNASEQ( record(input: file('sample.fq')) )
            }
            ''')
        service.updateNow()
        def hover = service.hover(new HoverParams(new TextDocumentIdentifier(uri), new Position(6, 4)))
        then:
        def value = hover.getContents().getRight().getValue()
        value.contains('workflow RNASEQ {')
        value.contains('input: Path')
        value.contains('bams: Channel<Path>')
    }

}

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

import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for including a pipeline -- the `params` / `workflow` / `output`
 * trio of a script -- as a named workflow.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ScriptPipelineCompositionTest {

    static final String PIPELINE = """
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
        """;

    static TestServer pipelineService() {
        return pipelineService(new TestLanguageClient());
    }

    static TestServer pipelineService(TestLanguageClient client) {
        var server = TestServer.start((o) -> o.client = client);
        open(server, getUri("rnaseq.nf"), PIPELINE);
        return server;
    }

    @Test
    void noDiagnosticsForPipelineInclude() {
        var client = new TestLanguageClient();
        var server = pipelineService(client);
        var uri = getUri("main.nf");

        open(server, uri, """
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
            """);
        server.settle();

        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void getHoverOfIncludedPipeline() {
        var server = pipelineService();
        var uri = getUri("main.nf");

        open(server, uri, """
            nextflow.enable.types = true

            include { workflow as RNASEQ } from './rnaseq.nf'

            workflow {
                main:
                RNASEQ( record(input: file('sample.fq')) )
            }
            """);
        var hover = server.call(s -> s.hover(new HoverParams(new TextDocumentIdentifier(uri), new Position(6, 4))));

        var value = hover.getContents().getRight().getValue();
        assertThat(value).contains("workflow RNASEQ {");
        assertThat(value).contains("input: Path");
        assertThat(value).contains("bams: Channel<Path>");
    }

}

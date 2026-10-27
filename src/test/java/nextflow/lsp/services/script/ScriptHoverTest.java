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
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ScriptHoverTest {

    static String getHoverHint(TestServer server, String uri, Position position) {
        var hover = server.call(s -> s.hover(new HoverParams(new TextDocumentIdentifier(uri), position)));
        return hover != null
            ? hover.getContents().getRight().getValue()
            : null;
    }

    @Test
    void showSignatureAndDocumentationWhenHoveringOverFunctionCall() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            /**
             * Greet someone.
             */
            def greet(name) {
                return "Hello, ${name}!"
            }

            workflow {
                greet('World')
            }
            """);
        var value = getHoverHint(server, uri, new Position(8, 5));

        assertThat(value).isEqualTo("```nextflow\ndef greet(name)\n```\n\n---\n\nGreet someone.");
    }

    @Test
    void showInferredReturnTypeWhenHoveringOverUntypedFunctionCall() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            nextflow.enable.types = true

            def make() {
                return channel.of(1, 2, 3)
            }

            workflow {
                make()
            }
            """);
        var value = getHoverHint(server, uri, new Position(7, 5));

        assertThat(value).isEqualTo("```nextflow\ndef make() -> Channel<Integer>\n```");
    }

    @Test
    void showLabelWhenHoveringOverWorkflowInvocation() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            """);
        var value = getHoverHint(server, uri, new Position(4, 4));

        assertThat(value).startsWith("```nextflow\nworkflow HELLO {");
    }

    @Test
    void showLabelWhenHoveringOverProcess() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        // destructured record inputs/outputs
        server.open("main.nf", """
            nextflow.enable.types = true

            process SAMTOOLS_INDEX {
                input:
                record(id: String, input: Path)

                output:
                record(id: id, index: file('index.fa'))

                script:
                'samtools index'
            }

            workflow {
                SAMTOOLS_INDEX( channel.empty() )
            }
            """);
        var value = getHoverHint(server, uri, new Position(14, 4));

        // render as Record { ... } with body
        assertThat(value).isEqualTo("""
            ```nextflow
            process SAMTOOLS_INDEX {
              input:
              Record {
                id: String
                input: Path
              }

              output:
              Record {
                id: String
                index: Path
              }
            }
            ```""");

        // named record type input
        server.change("main.nf", """
            nextflow.enable.types = true

            record SamtoolsIndexInput {
                id: String
                input: Path
            }

            process SAMTOOLS_INDEX {
                input:
                data: SamtoolsIndexInput

                script:
                'samtools index'
            }

            workflow {
                SAMTOOLS_INDEX( channel.empty() )
            }
            """);
        value = getHoverHint(server, uri, new Position(16, 4));

        // expand named record body
        assertThat(value).isEqualTo("""
            ```nextflow
            process SAMTOOLS_INDEX {
              input:
              data: SamtoolsIndexInput {
                id: String
                input: Path
              }

              output:
              <none>
            }
            ```""");
    }

    @Test
    void returnNullWhenHoveringOverWhitespace() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            workflow {
                println('Hello!')
            }
            """);

        assertThat(getHoverHint(server, uri, new Position(3, 0))).isNull();
    }

}

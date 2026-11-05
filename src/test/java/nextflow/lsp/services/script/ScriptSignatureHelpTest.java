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

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpContext;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureHelpTriggerKind;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies signature help for calls in scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ScriptSignatureHelpTest {

    static SignatureHelp getSignatureHelp(TestServer server, Position position) {
        return getSignatureHelp(server, position, null);
    }

    static SignatureHelp getSignatureHelp(TestServer server, Position position, SignatureHelpContext context) {
        var params = new SignatureHelpParams(new TextDocumentIdentifier(getUri("main.nf")), position, context);
        return server.call(s -> s.signatureHelp(params));
    }

    static SignatureInformation activeSignature(SignatureHelp help) {
        return help.getSignatures().get(help.getActiveSignature());
    }

    static List<String> parameterLabels(SignatureInformation signature) {
        var label = signature.getLabel();
        return signature.getParameters().stream()
            .map((pi) -> {
                var offsets = pi.getLabel().getRight();
                return label.substring(offsets.getFirst(), offsets.getSecond());
            })
            .toList();
    }

    static String activeParameterLabel(SignatureHelp help) {
        var signature = activeSignature(help);
        return parameterLabels(signature).get(signature.getActiveParameter());
    }

    static String documentation(SignatureInformation signature) {
        var documentation = signature.getDocumentation();
        return documentation != null ? documentation.getRight().getValue() : null;
    }

    static String documentation(ParameterInformation parameter) {
        var documentation = parameter.getDocumentation();
        return documentation != null ? documentation.getRight().getValue() : null;
    }

    @Test
    void showSignatureAndDocumentationOfFunctionCall() {
        var server = TestServer.start();
        server.open("main.nf", """
            nextflow.enable.types = true

            /**
             * Greet someone.
             */
            def greet(name: String, times: Integer) -> String {
                return "Hello, ${name}!" * times
            }

            workflow {
                greet()
            }
            """);

        var help = getSignatureHelp(server, new Position(10, 10));

        var signature = activeSignature(help);
        assertThat(help.getSignatures()).hasSize(1);
        assertThat(signature.getLabel()).isEqualTo("greet(name: String, times: Integer) -> String");
        assertThat(parameterLabels(signature)).containsExactly("name: String", "times: Integer");
        assertThat(signature.getActiveParameter()).isEqualTo(0);
        assertThat(documentation(signature)).isEqualTo("Greet someone.");
    }

    @Test
    void highlightParameterAfterTrailingComma() {
        var server = TestServer.start();
        server.open("main.nf", """
            def greet(name, times) {
            }

            workflow {
                greet('World', )
            }
            """);

        var help = getSignatureHelp(server, new Position(4, 19));

        assertThat(activeParameterLabel(help)).isEqualTo("times");
    }

    @Test
    void highlightParameterOfArgumentAtCursor() {
        var server = TestServer.start();
        server.open("main.nf", """
            def greet(name, times) {
            }

            workflow {
                greet('World', 3)
            }
            """);

        assertThat(activeParameterLabel(getSignatureHelp(server, new Position(4, 12)))).isEqualTo("name");
        assertThat(activeParameterLabel(getSignatureHelp(server, new Position(4, 17)))).isEqualTo("name");
        assertThat(activeParameterLabel(getSignatureHelp(server, new Position(4, 19)))).isEqualTo("times");
    }

    @Test
    void showParameterDocumentationFromGroovydocTags() {
        var server = TestServer.start();
        server.open("main.nf", """
            /**
             * Greet someone.
             *
             * @param name the name to greet
             * @param times how many times
             *   to greet
             */
            def greet(name, times) {
            }

            workflow {
                greet()
            }
            """);

        var help = getSignatureHelp(server, new Position(11, 10));

        var signature = activeSignature(help);
        assertThat(documentation(signature)).isEqualTo("Greet someone.");
        assertThat(documentation(signature.getParameters().get(0))).isEqualTo("the name to greet");
        assertThat(documentation(signature.getParameters().get(1))).isEqualTo("how many times to greet");
    }

    @Test
    void showInputsOfTypedProcess() {
        var server = TestServer.start();
        server.open("main.nf", """
            nextflow.enable.types = true

            process ALIGN {
                input:
                tuple(id: String, reads: Path)
                index: Path     // the genome index
                record(sample: String, bam: Path)

                script:
                ""
            }

            workflow {
                ALIGN()
            }
            """);

        var help = getSignatureHelp(server, new Position(13, 10));

        var signature = activeSignature(help);
        assertThat(signature.getLabel()).isEqualTo("ALIGN(tuple(id: String, reads: Path), index: Path, record(sample: String, bam: Path))");
        assertThat(documentation(signature.getParameters().get(1))).isEqualTo("the genome index");
    }

    @Test
    void showNoSignatureForLegacyProcess() {
        var server = TestServer.start();
        server.open("main.nf", """
            process HELLO {
                input:
                val name

                script:
                ""
            }

            workflow {
                HELLO()
            }
            """);

        var help = getSignatureHelp(server, new Position(9, 10));

        assertThat(help).isNull();
    }

    @Test
    void showTakesOfWorkflow() {
        var server = TestServer.start();
        server.open("main.nf", """
            nextflow.enable.types = true

            workflow ALIGN {
                take:
                reads: Channel<Path>    // the reads to align
                index: Value<Path>

                main:
                println reads
            }

            workflow {
                ALIGN(channel.empty(), )
            }
            """);

        var help = getSignatureHelp(server, new Position(12, 27));

        var signature = activeSignature(help);
        assertThat(signature.getLabel()).isEqualTo("ALIGN(reads: Channel<Path>, index: Value<Path>)");
        assertThat(signature.getActiveParameter()).isEqualTo(1);
        assertThat(documentation(signature.getParameters().get(0))).isEqualTo("the reads to align");
    }

    @Test
    void showParamsOfIncludedPipelineAsRecordParameter() {
        var server = TestServer.start();
        server.open("rnaseq.nf", """
            nextflow.enable.types = true

            params {
                input: Path
                aligner: String = 'star'
            }

            workflow {
            }
            """);
        server.open("main.nf", """
            nextflow.enable.types = true

            include { workflow as RNASEQ } from './rnaseq.nf'

            workflow {
                RNASEQ()
            }
            """);

        var help = getSignatureHelp(server, new Position(5, 11));

        var signature = activeSignature(help);
        assertThat(signature.getLabel()).isEqualTo("RNASEQ(params: Record)");
        assertThat(documentation(signature.getParameters().get(0))).isEqualTo("```nextflow\ninput: Path\naligner: String\n```");
    }

    @Test
    void showEveryOverloadAndSelectTheResolvedOne() {
        var server = TestServer.start();
        server.open("main.nf", """
            nextflow.enable.types = true

            workflow {
                channel.of(1).combine(channel.value(2))
            }
            """);

        var help = getSignatureHelp(server, new Position(3, 26));

        assertThat(help.getSignatures())
            .extracting(SignatureInformation::getLabel)
            .containsExactlyInAnyOrder(
                "combine(right: Channel) -> Channel<Tuple>",
                "combine(right: Value) -> Channel<Tuple>",
                "combine(fields: Map<String, ?>) -> Channel<Record>");
        assertThat(activeSignature(help).getLabel()).isEqualTo("combine(right: Value) -> Channel<Tuple>");
        assertThat(help.getSignatures())
            .filteredOn(si -> si.getLabel().startsWith("combine(right: Channel)"))
            .extracting(ScriptSignatureHelpTest::documentation)
            .singleElement().asString().startsWith("The `combine` operator");
    }

    @Test
    void highlightOptionsParameterForNamedArgument() {
        var server = TestServer.start();
        server.open("main.nf", """
            workflow {
                file('*.fq', hidden: true)
            }
            """);

        var help = getSignatureHelp(server, new Position(1, 19));

        var signature = activeSignature(help);
        assertThat(signature.getLabel()).isEqualTo("file(filePattern: String, [options]) -> Path");
        assertThat(activeParameterLabel(help)).isEqualTo("[options]");
        assertThat(documentation(signature.getParameters().get(1))).contains("`hidden: Boolean`");
        assertThat(documentation(signature)).doesNotContain("`hidden: Boolean`");
    }

    @Test
    void highlightPositionalParameterAfterNamedArgument() {
        var server = TestServer.start();
        server.open("main.nf", """
            workflow {
                file(hidden: true, '*.fq')
            }
            """);

        var help = getSignatureHelp(server, new Position(1, 24));

        assertThat(activeParameterLabel(help)).isEqualTo("filePattern: String");
    }

    @Test
    void keepOverloadSelectedByUserWhenRetriggered() {
        var server = TestServer.start();
        server.open("main.nf", """
            workflow {
                channel.of(1).combine()
            }
            """);
        var first = getSignatureHelp(server, new Position(1, 26));
        var other = (first.getActiveSignature() + 1) % first.getSignatures().size();
        first.setActiveSignature(other);

        var context = new SignatureHelpContext(SignatureHelpTriggerKind.ContentChange, true);
        context.setActiveSignatureHelp(first);
        var help = getSignatureHelp(server, new Position(1, 26), context);

        assertThat(help.getActiveSignature()).isEqualTo(other);
    }

    @Test
    void showInnermostCallForNestedCall() {
        var server = TestServer.start();
        server.open("main.nf", """
            def greet(name, times) {
            }

            def shout(message) {
            }

            workflow {
                greet(shout(), 1)
            }
            """);

        assertThat(activeSignature(getSignatureHelp(server, new Position(7, 16))).getLabel()).isEqualTo("shout(message)");
        assertThat(activeSignature(getSignatureHelp(server, new Position(7, 17))).getLabel()).isEqualTo("greet(name, times)");
    }

    @Test
    void showNoSignatureInsideClosureArgument() {
        var server = TestServer.start();
        server.open("main.nf", """
            workflow {
                channel.of(1).map { v -> [v, v] }
            }
            """);

        var help = getSignatureHelp(server, new Position(1, 33));

        assertThat(help).isNull();
    }

    @Test
    void showNoSignatureOutsideParentheses() {
        var server = TestServer.start();
        server.open("main.nf", """
            def greet(name) {
            }

            workflow {
                greet('World')
            }
            """);

        assertThat(getSignatureHelp(server, new Position(4, 6))).isNull();
        assertThat(getSignatureHelp(server, new Position(4, 18))).isNull();
    }

    @Test
    void showNoSignatureForUnclosedCall() {
        var server = TestServer.start();
        server.open("main.nf", """
            def greet(name) {
            }

            workflow {
                greet('World',
            }
            """);

        var help = getSignatureHelp(server, new Position(4, 18));

        assertThat(help).isNull();
    }

}

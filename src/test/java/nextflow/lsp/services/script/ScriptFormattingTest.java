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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Stream;

import nextflow.lsp.Golden;
import nextflow.script.formatter.FormattingOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptFormattingTest {

    static void checkFormat(ScriptService service, String uri, FormattingOptions options, String before, String after) {
        // open verbatim to preserve CRLF line endings
        openVerbatim(service, uri, before);
        var textEdits = service.formatting(URI.create(uri), options);
        // the provider returns no edits both when the document is already
        // formatted and when it refuses to format (e.g. a comment would be
        // lost), so a document that should change must produce an edit
        if( !before.equals(after) )
            assertThat(textEdits).isNotEmpty();
        var newText = !textEdits.isEmpty() ? textEdits.get(0).getNewText() : before;
        assertThat(newText).isEqualTo(after);
    }

    static void checkFormat(ScriptService service, String uri, String before, String after) {
        checkFormat(service, uri, new FormattingOptions(4, true), before, after);
    }

    /**
     * Format each script in format/script and compare it with the sibling
     * .formatted file. Inputs are deliberately non-canonical so that a
     * refusal to format cannot pass as a no-op, and the formatted output
     * must be stable when formatted again.
     */
    @TestFactory
    Stream<DynamicTest> formatGoldenFiles() {
        var service = getScriptService();
        var uri = getUri("main.nf");
        var options = new FormattingOptions(4, true);

        return Golden.inputs("format/script", ".nf").stream().map(input -> dynamicTest(input.getFileName().toString(), input.toUri(), () -> {
            openVerbatim(service, uri, Golden.read(input));
            var textEdits = service.formatting(URI.create(uri), options);
            assertThat(textEdits).isNotEmpty();
            var formatted = textEdits.get(0).getNewText();
            Golden.check(Golden.sibling(input, ".formatted"), formatted);

            openVerbatim(service, uri, formatted);
            var reformatted = service.formatting(URI.create(uri), options);
            if( !reformatted.isEmpty() )
                assertThat(reformatted.get(0).getNewText()).isEqualTo(formatted);
        }));
    }

    @Test
    void reindentMultiLineStrings() {
        var service = getScriptService();
        var uri = getUri("main.nf");
        var options = new FormattingOptions(2, true);

        checkFormat(service, uri, options,
            """
            process foo {
                script:
                \"""
                echo 'hello world!'
                \"""
            }
            """,
            """
            process foo {
              script:
              \"""
              echo 'hello world!'
              \"""
            }
            """
        );
    }

    @Test
    void formatLeadingCommentsInFileWithCrlfLineEndings() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        checkFormat(service, uri,
            "workflow {\r\n    // ALIGN reads to reference genome\r\n    BWA_ALIGN(sample_id, library_id)\r\n}\r\n",
            """
            workflow {
                // ALIGN reads to reference genome
                BWA_ALIGN(sample_id, library_id)
            }
            """
        );
    }

    @Test
    void produceIdenticalOutputWhenFormattingCachedAstTwice() {
        // formatting the same document repeatedly without a document change
        // reuses the same cached AST -- the output must not change, including
        // for comments inside wrapped expressions; the input is deliberately
        // non-canonical so that every request returns an edit
        var service = getScriptService();
        var uri = getUri("main.nf");
        var contents = """
            workflow {
                foo(
                    // leading comment on element
                    alpha,
                    beta, // trailing comment on element
                )
                data
                    // comment on chain link
                    .map { x -> x }
                    .view()
                y=1
            }
            """;
        var expected = contents.replace("y=1", "y = 1");

        try {
            openOnDisk(service, uri, contents);
            var texts = new ArrayList<String>();
            for( int i = 0; i < 3; i++ ) {
                var edits = service.formatting(URI.create(uri), new FormattingOptions(4, true));
                texts.add(edits != null && !edits.isEmpty() ? edits.get(0).getNewText() : contents);
            }

            assertThat(texts).isEqualTo(Collections.nCopies(3, expected));
        }
        finally {
            deleteOnDisk(uri);
        }
    }

}

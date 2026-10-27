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

import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Stream;

import nextflow.lsp.Golden;
import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.script.formatter.FormattingOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Specifies formatting of config files.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ConfigFormattingTest {

    /**
     * Format each config file in format/config and compare it with the
     * sibling .formatted file. Inputs are deliberately non-canonical so that
     * a refusal to format cannot pass as a no-op, and the formatted output
     * must be stable when formatted again.
     */
    @TestFactory
    @Execution(ExecutionMode.SAME_THREAD) // the dynamic tests share one server
    Stream<DynamicTest> formatGoldenFiles() {
        var server = TestServer.start();
        var uri = getUri("nextflow.config");
        var options = new FormattingOptions(4, true);

        return Golden.inputs("format/config", ".config").stream().map(input -> dynamicTest(input.getFileName().toString(), input.toUri(), () -> {
            server.openVerbatim("nextflow.config", Golden.read(input));
            try {
                var textEdits = formatting(server, uri, options);
                assertThat(textEdits).isNotEmpty();
                var formatted = textEdits.get(0).getNewText();
                Golden.check(Golden.sibling(input, ".formatted"), formatted);

                server.changeVerbatim("nextflow.config", formatted);
                var reformatted = formatting(server, uri, options);
                if( !reformatted.isEmpty() )
                    assertThat(reformatted.get(0).getNewText()).isEqualTo(formatted);
            } finally {
                server.close("nextflow.config");
            }
        }));
    }

    @Test
    void produceIdenticalOutputWhenFormattingCachedConfigAstTwice() {
        // formatting the same document repeatedly without a document change
        // re-derives the comment metadata on the same cached AST -- the
        // output must not change; the input is deliberately non-canonical
        // so that every request returns an edit
        var server = TestServer.start();
        var uri = getUri("nextflow.config");
        var contents = """
            // top comment
            process {
                cpus=2 // trailing comment
                // dangling comment
            }
            """;
        var expected = contents.replace("cpus=2", "cpus = 2");

        server.open("nextflow.config", contents);
        var texts = new ArrayList<String>();
        for( int i = 0; i < 3; i++ ) {
            var edits = formatting(server, uri, new FormattingOptions(4, true));
            texts.add(edits != null && !edits.isEmpty() ? edits.get(0).getNewText() : contents);
        }

        assertThat(texts).isEqualTo(Collections.nCopies(3, expected));
    }

}

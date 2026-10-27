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


package nextflow.lsp.services.script.dag;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import nextflow.lsp.Golden;
import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Specifies the Mermaid DAG preview of workflows.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class PreviewDagTest {

    /**
     * Preview the DAG of each workflow in each script in dag, via its
     * "Preview DAG" code lens, and compare the Mermaid diagrams with the
     * sibling .mmd file. Links to the script are relative to the workspace
     * root, so that the expected output doesn't depend on its location.
     */
    @TestFactory
    @Execution(ExecutionMode.SAME_THREAD) // the dynamic tests share one server
    Stream<DynamicTest> previewDagGoldenFiles() {
        var server = TestServer.start();
        var uri = getUri("main.nf");
        var rootUri = getUri("");

        return Golden.inputs("dag", ".nf").stream().map(input -> dynamicTest(input.getFileName().toString(), input.toUri(), () -> {
            server.openVerbatim("main.nf", Golden.read(input));
            try {
                var lenses = server.call(s -> s.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri)))).stream()
                    .filter(lens -> "nextflow.previewDag".equals(lens.getCommand().getCommand()))
                    .toList();
                assertThat(lenses).isNotEmpty();

                var mmd = lenses.stream()
                    .map((lens) -> {
                        var response = (Map<?,?>) executeCommand(server, "nextflow.server.previewDag", lens.getCommand().getArguments());
                        assertThat(response).isNotNull();
                        var result = (String) response.get("result");
                        assertThat(result).as("DAG preview error: %s", response.get("error")).isNotNull();
                        return result.replace(rootUri, "");
                    })
                    .collect(Collectors.joining("\n"));
                Golden.check(Golden.sibling(input, ".mmd"), mmd);
            } finally {
                server.close("main.nf");
            }
        }));
    }

}

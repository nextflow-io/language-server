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
import java.util.Map;

import nextflow.lsp.TestWorkspace;
import nextflow.lsp.services.LanguageServerConfiguration;
import org.eclipse.lsp4j.CodeLensParams;
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
class ScriptCodeLensTest {

    @Test
    void providePreviewDagCodeLensForEachWorkflow() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            """);
        service.updateNow();
        var lenses = service.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri)));

        // one lens per workflow (named + entry)
        assertThat(lenses).hasSize(2);
        assertThat(lenses).allMatch(lens -> "Preview DAG".equals(lens.getCommand().getTitle()));
        assertThat(lenses).allMatch(lens -> "nextflow.previewDag".equals(lens.getCommand().getCommand()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewWorkspaceViaExecuteCommand() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        // open without a manual update; executeCommand drives the compile
        open(service, uri, """
            process FOO {
                script:
                "echo foo"
            }

            workflow {
                FOO()
            }
            """);
        var result = (Map<String,Object>) service.executeCommand("nextflow.server.previewWorkspace", List.of(), LanguageServerConfiguration.defaults());
        var definitions = (List<Map<String,Object>>) result.get("result");

        assertThat(definitions).anyMatch(d -> "process".equals(d.get("type")) && "FOO".equals(d.get("name")));
        var entry = definitions.stream()
            .filter(d -> "workflow".equals(d.get("type")) && "<entry>".equals(d.get("name")))
            .findFirst().orElse(null);
        assertThat(entry).isNotNull();
        var children = (List<Map<String,Object>>) entry.get("children");
        assertThat(children).anyMatch(c -> "FOO".equals(c.get("name")));
    }

    @Test
    void returnNullForUnknownCommand() {
        var service = getScriptService();

        assertThat(service.executeCommand("nextflow.server.bogus", List.of(), LanguageServerConfiguration.defaults())).isNull();
    }

}

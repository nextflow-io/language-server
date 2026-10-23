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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nextflow.lsp.util.Positions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptRenameTest {

    static Map<String,List<TextEdit>> rename(ScriptService service, String uri, Position position, String newName) {
        var workspaceEdit = service.rename(new RenameParams(new TextDocumentIdentifier(uri), position, newName));
        var result = new HashMap<String,List<TextEdit>>();
        workspaceEdit.getChanges().forEach((key, edits) -> {
            var sorted = new ArrayList<>(edits);
            sorted.sort((a, b) -> (
                Positions.COMPARATOR.compare(a.getRange().getStart(), b.getRange().getStart())
            ));
            result.put(key, sorted);
        });
        return result;
    }

    @Test
    void renameWorkflow() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            """;
        open(service, uri, contents);
        service.updateNow();
        var changes = rename(service, uri, new Position(0, 9), "hi");

        assertThat(changes.get(uri)).hasSize(2);
        assertThat(changes.get(uri).get(0).getRange().getStart()).isEqualTo(new Position(0, 9));
        assertThat(changes.get(uri).get(0).getRange().getEnd()).isEqualTo(new Position(0, 14));
        assertThat(changes.get(uri).get(0).getNewText()).isEqualTo("hi");
        assertThat(changes.get(uri).get(1).getRange().getStart()).isEqualTo(new Position(4, 4));
        assertThat(changes.get(uri).get(1).getRange().getEnd()).isEqualTo(new Position(4, 9));
        assertThat(changes.get(uri).get(1).getNewText()).isEqualTo("hi");
    }

    @Test
    void renameWorkflowInput() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            workflow HELLO {
                take:
                greeting

                main:
                greeting.view()
            }
            """;
        open(service, uri, contents);
        service.updateNow();
        var changes = rename(service, uri, new Position(2, 4), "message");

        assertThat(changes.get(uri)).hasSize(2);
        assertThat(changes.get(uri).get(0).getRange().getStart()).isEqualTo(new Position(2, 4));
        assertThat(changes.get(uri).get(0).getRange().getEnd()).isEqualTo(new Position(2, 12));
        assertThat(changes.get(uri).get(0).getNewText()).isEqualTo("message");
        assertThat(changes.get(uri).get(1).getRange().getStart()).isEqualTo(new Position(5, 4));
        assertThat(changes.get(uri).get(1).getRange().getEnd()).isEqualTo(new Position(5, 12));
        assertThat(changes.get(uri).get(1).getNewText()).isEqualTo("message");
    }

    @Test
    void renameProcess() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            process HELLO {
                script:
                'true'
            }

            workflow {
                HELLO()
            }
            """;
        open(service, uri, contents);
        service.updateNow();
        var changes = rename(service, uri, new Position(0, 8), "hi");

        assertThat(changes.get(uri)).hasSize(2);
        assertThat(changes.get(uri).get(0).getRange().getStart()).isEqualTo(new Position(0, 8));
        assertThat(changes.get(uri).get(0).getRange().getEnd()).isEqualTo(new Position(0, 13));
        assertThat(changes.get(uri).get(0).getNewText()).isEqualTo("hi");
        assertThat(changes.get(uri).get(1).getRange().getStart()).isEqualTo(new Position(6, 4));
        assertThat(changes.get(uri).get(1).getRange().getEnd()).isEqualTo(new Position(6, 9));
        assertThat(changes.get(uri).get(1).getNewText()).isEqualTo("hi");
    }

    @Test
    void renameProcessInput() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            process HELLO {
                input:
                val greeting

                script:
                "echo $greeting"
            }
            """;
        open(service, uri, contents);
        service.updateNow();
        var changes = rename(service, uri, new Position(2, 8), "message");

        assertThat(changes.get(uri)).hasSize(2);
        assertThat(changes.get(uri).get(0).getRange().getStart()).isEqualTo(new Position(2, 8));
        assertThat(changes.get(uri).get(0).getRange().getEnd()).isEqualTo(new Position(2, 16));
        assertThat(changes.get(uri).get(0).getNewText()).isEqualTo("message");
        assertThat(changes.get(uri).get(1).getRange().getStart()).isEqualTo(new Position(5, 10));
        assertThat(changes.get(uri).get(1).getRange().getEnd()).isEqualTo(new Position(5, 18));
        assertThat(changes.get(uri).get(1).getNewText()).isEqualTo("message");
    }

    @Test
    void renameLocalVariable() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            def msg = 'hello'
            println msg
            """;
        open(service, uri, contents);
        service.updateNow();
        var changes = rename(service, uri, new Position(0, 4), "message");

        assertThat(changes.get(uri)).hasSize(2);
        assertThat(changes.get(uri).get(0).getRange().getStart()).isEqualTo(new Position(0, 4));
        assertThat(changes.get(uri).get(0).getRange().getEnd()).isEqualTo(new Position(0, 7));
        assertThat(changes.get(uri).get(0).getNewText()).isEqualTo("message");
        assertThat(changes.get(uri).get(1).getRange().getStart()).isEqualTo(new Position(1, 8));
        assertThat(changes.get(uri).get(1).getRange().getEnd()).isEqualTo(new Position(1, 11));
        assertThat(changes.get(uri).get(1).getNewText()).isEqualTo("message");
    }

    @Test
    void renameFunction() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            def hello() {
            }

            workflow {
                hello()
            }
            """;
        open(service, uri, contents);
        service.updateNow();
        var changes = rename(service, uri, new Position(0, 4), "hi");

        assertThat(changes.get(uri)).hasSize(2);
        assertThat(changes.get(uri).get(0).getRange().getStart()).isEqualTo(new Position(0, 4));
        assertThat(changes.get(uri).get(0).getRange().getEnd()).isEqualTo(new Position(0, 9));
        assertThat(changes.get(uri).get(0).getNewText()).isEqualTo("hi");
        assertThat(changes.get(uri).get(1).getRange().getStart()).isEqualTo(new Position(4, 4));
        assertThat(changes.get(uri).get(1).getRange().getEnd()).isEqualTo(new Position(4, 9));
        assertThat(changes.get(uri).get(1).getNewText()).isEqualTo("hi");
    }

    @Test
    void renameFunctionParameter() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            def hello(greeting: String) {
                println greeting
            }
            """;
        open(service, uri, contents);
        service.updateNow();
        var changes = rename(service, uri, new Position(0, 10), "message");

        assertThat(changes.get(uri)).hasSize(2);
        assertThat(changes.get(uri).get(0).getRange().getStart()).isEqualTo(new Position(0, 10));
        assertThat(changes.get(uri).get(0).getRange().getEnd()).isEqualTo(new Position(0, 18));
        assertThat(changes.get(uri).get(0).getNewText()).isEqualTo("message");
        assertThat(changes.get(uri).get(1).getRange().getStart()).isEqualTo(new Position(1, 12));
        assertThat(changes.get(uri).get(1).getRange().getEnd()).isEqualTo(new Position(1, 20));
        assertThat(changes.get(uri).get(1).getNewText()).isEqualTo("message");
    }

}

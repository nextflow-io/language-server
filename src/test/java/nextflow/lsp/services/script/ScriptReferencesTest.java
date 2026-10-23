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

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.util.Positions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
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
class ScriptReferencesTest {

    static Map<String,List<Range>> getReferences(TestServer server, String uri, Position position) {
        var locations = server.call(s -> s.references(new ReferenceParams(new TextDocumentIdentifier(uri), position, new ReferenceContext(true))));
        var result = new HashMap<String,List<Range>>();
        for( var location : locations ) {
            var ranges = result.computeIfAbsent(location.getUri(), (k) -> new ArrayList<>());
            ranges.add(location.getRange());
        }
        for( var ranges : result.values() )
            ranges.sort((a, b) -> Positions.COMPARATOR.compare(a.getStart(), b.getStart()));
        return result;
    }

    @Test
    void getReferencesOfWorkflowDefinition() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        var contents = """
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            """;
        open(server, uri, contents);
        var references = getReferences(server, uri, new Position(0, 9));

        assertThat(references.get(uri)).hasSize(2);
        assertThat(references.get(uri).get(0).getStart()).isEqualTo(new Position(0, 0));
        assertThat(references.get(uri).get(0).getEnd()).isEqualTo(new Position(1, 1));
        assertThat(references.get(uri).get(1).getStart()).isEqualTo(new Position(4, 4));
        assertThat(references.get(uri).get(1).getEnd()).isEqualTo(new Position(4, 11));
    }

    @Test
    void getReferencesOfWorkflowDefinitionInDifferentFile() {
        var server = TestServer.start();
        var mainUri = getUri("main.nf");
        var moduleUri = getUri("module.nf");

        open(server, moduleUri, """
            workflow HELLO {
            }
            """);
        open(server, mainUri, """
            include { HELLO } from './module.nf'

            workflow {
                HELLO()
            }
            """);
        var references = getReferences(server, moduleUri, new Position(0, 9));

        assertThat(references.get(moduleUri)).hasSize(1);
        assertThat(references.get(moduleUri).get(0).getStart()).isEqualTo(new Position(0, 0));
        assertThat(references.get(moduleUri).get(0).getEnd()).isEqualTo(new Position(1, 1));
        assertThat(references.get(mainUri)).hasSize(2);
        assertThat(references.get(mainUri).get(0).getStart()).isEqualTo(new Position(0, 10));
        assertThat(references.get(mainUri).get(0).getEnd()).isEqualTo(new Position(0, 15));
        assertThat(references.get(mainUri).get(1).getStart()).isEqualTo(new Position(3, 4));
        assertThat(references.get(mainUri).get(1).getEnd()).isEqualTo(new Position(3, 11));
    }

}

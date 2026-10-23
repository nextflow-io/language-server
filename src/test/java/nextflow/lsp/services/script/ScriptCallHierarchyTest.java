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
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
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
class ScriptCallHierarchyTest {

    static final String CONTENTS = """
        workflow HELLO {
        }

        workflow {
            HELLO()
        }
        """;

    static List<CallHierarchyItem> prepare(TestServer server, String uri, Position position) {
        return server.call(s -> s.prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), position)));
    }

    @Test
    void prepareCallHierarchyItemForDefinitionAndCall() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        open(server, uri, CONTENTS);
        var atDef = prepare(server, uri, new Position(0, 9));
        var atCall = prepare(server, uri, new Position(4, 4));

        assertThat(atDef).hasSize(1);
        assertThat(atDef.get(0).getName()).isEqualTo("HELLO");
        assertThat(atDef.get(0).getRange().getStart()).isEqualTo(new Position(0, 0));

        assertThat(atCall).hasSize(1);
        assertThat(atCall.get(0).getName()).isEqualTo("HELLO");
        assertThat(atCall.get(0).getRange().getStart()).isEqualTo(new Position(4, 4));
    }

    @Test
    void resolveIncomingCallsToWorkflow() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        open(server, uri, CONTENTS);
        var item = prepare(server, uri, new Position(0, 9)).get(0);
        var incoming = server.call(s -> s.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(item)));

        // the entry workflow calls HELLO at line 4
        assertThat(incoming).hasSize(1);
        assertThat(incoming.get(0).getFrom().getName()).isEqualTo("<entry>");
        assertThat(incoming.get(0).getFromRanges()).anyMatch(r -> r.getStart().equals(new Position(4, 4)));
    }

    @Test
    void resolveOutgoingCallsFromEntryWorkflow() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        open(server, uri, CONTENTS);
        var item = prepare(server, uri, new Position(3, 0)).get(0);
        var outgoing = server.call(s -> s.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(item)));

        // the entry workflow calls HELLO
        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.get(0).getTo().getName()).isEqualTo("HELLO");
        assertThat(outgoing.get(0).getFromRanges()).anyMatch(r -> r.getStart().equals(new Position(4, 4)));
    }

    @Test
    void noCallHierarchyItemForNonCallablePosition() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        open(server, uri, CONTENTS);

        // the blank line between the two workflows is not a method or call
        assertThat(prepare(server, uri, new Position(2, 0))).isEmpty();
    }

}

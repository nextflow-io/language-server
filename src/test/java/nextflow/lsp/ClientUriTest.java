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
package nextflow.lsp;

import java.util.List;

import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test requests on documents that the client opened with a URI that
 * is spelled differently from the canonical form, such as the
 * percent-encoded drive letter of VS Code on Windows.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ClientUriTest {

    static final String MAIN = """
        include { GREET as HELLO } from './mod.nf'

        workflow {
            HELLO()
        }
        """;

    static final String MODULE = """
        workflow GREET {
        }
        """;

    /**
     * Start a server with main.nf open under the given client URI.
     */
    static TestServer start(String dir, String encodedDir) {
        var server = TestServer.start((o) -> o.settle = false);
        server.write(dir + "/main.nf", MAIN);
        server.write(dir + "/mod.nf", MODULE);
        var clientUri = server.uri("") + encodedDir + "/main.nf";
        server.server().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(clientUri, "nextflow", 1, MAIN)));
        server.settle();
        return server;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "c:|c%3A",
        "my@pipeline (copy)|my%40pipeline%20%28copy%29"
    })
    void findReferencesOfAnIncludeAlias(String dir, String encodedDir) {
        try( var server = start(dir, encodedDir) ) {
            var clientUri = server.uri("") + encodedDir + "/main.nf";
            var document = new TextDocumentIdentifier(clientUri);
            var references = server.call((s) -> s.references(new ReferenceParams(document, new Position(0, 20), new ReferenceContext(true))));
            assertThat(references).isNotEmpty().extracting(Location::getUri).containsOnly(clientUri);

            var edit = server.call((s) -> s.rename(new RenameParams(document, new Position(0, 20), "HI")));
            assertThat(edit.getChanges()).containsOnlyKeys(clientUri);
            assertThat(edit.getChanges().get(clientUri)).hasSize(references.size());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "c:|c%3A",
        "my@pipeline (copy)|my%40pipeline%20%28copy%29"
    })
    void referToOpenDocumentsByTheirClientUri(String dir, String encodedDir) {
        try( var server = start(dir, encodedDir) ) {
            var clientUri = server.uri("") + encodedDir + "/main.nf";
            var document = new TextDocumentIdentifier(clientUri);
            assertThat(server.client().getAllDiagnostics()).containsKey(clientUri);

            var definition = server.call((s) -> s.definition(new DefinitionParams(document, new Position(3, 6))));
            assertThat(definition.getLeft()).extracting(Location::getUri).containsExactly(server.uri(dir + "/mod.nf"));

            var items = server.call((s) -> s.prepareCallHierarchy(new CallHierarchyPrepareParams(document, new Position(3, 6))));
            assertThat(items).extracting(item -> item.getUri()).containsExactly(clientUri);

            var symbols = server.call((s) -> s.symbol(new WorkspaceSymbolParams("")));
            assertThat(symbols.getRight()).extracting(symbol -> symbol.getLocation().getLeft().getUri())
                .containsExactlyInAnyOrder(clientUri, server.uri(dir + "/mod.nf"));

            var lenses = server.call((s) -> s.codeLens(new CodeLensParams(document)));
            assertThat(lenses).isNotEmpty().allSatisfy(lens ->
                assertThat(lens.getCommand().getArguments().get(0).toString()).isEqualTo("\"" + clientUri + "\""));
        }
    }

}

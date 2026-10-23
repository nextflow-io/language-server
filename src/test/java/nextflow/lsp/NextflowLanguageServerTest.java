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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test the workspace setup of the language server for clients
 * that don't send a configuration (e.g. editors other than VS Code).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Timeout(15) // longer than the 10 s wait in awaitDiagnostics()
class NextflowLanguageServerTest {

    private static final String INVALID_SCRIPT = "workflow {\n";

    @TempDir
    Path workspace;

    private final TestLanguageClient client = new TestLanguageClient();

    private final NextflowLanguageServer server = new NextflowLanguageServer(50);

    @BeforeEach
    void setup() {
        server.connect(client);
    }

    @Test
    void reportDiagnosticsWithoutConfiguration() throws Exception {
        var uri = writeScript("main.nf");

        initialize(workspaceFolders(), null, null);
        open(uri);
        awaitDiagnostics(uri);
    }

    @Test
    void useRootUriIfThereAreNoWorkspaceFolders() throws Exception {
        var uri = writeScript("main.nf");

        initialize(null, workspace.toUri().toString(), null);
        open(uri);
        awaitDiagnostics(uri);
    }

    @Test
    void reportDiagnosticsForFilesOutsideOfWorkspaceFolders() throws Exception {
        var uri = writeScript("main.nf");

        initialize(null, null, null);
        open(uri);
        awaitDiagnostics(uri);
    }

    @Test
    void excludeWorkDirectoryByDefault() throws Exception {
        var uri = writeScript("main.nf");
        var excludedUri = writeScript("work/ab/cdef/main.nf");

        initialize(workspaceFolders(), null, null);
        open(uri);
        awaitDiagnostics(uri);
        assertThat(client.getAllDiagnostics()).doesNotContainKey(excludedUri);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportProgressOnlyIfClientSupportsIt(boolean supported) throws Exception {
        initialize(workspaceFolders(), null, supported);
        assertThat(client.getProgress().isEmpty()).isNotEqualTo(supported);
    }

    private String writeScript(String path) throws IOException {
        var file = workspace.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, INVALID_SCRIPT);
        return file.toUri().toString();
    }

    private List<WorkspaceFolder> workspaceFolders() {
        return List.of(new WorkspaceFolder(workspace.toUri().toString(), "test"));
    }

    @SuppressWarnings("deprecation") // rootUri
    private void initialize(List<WorkspaceFolder> workspaceFolders, String rootUri, Boolean workDoneProgress) throws Exception {
        var params = new InitializeParams();
        params.setWorkspaceFolders(workspaceFolders);
        params.setRootUri(rootUri);
        if( workDoneProgress != null ) {
            var window = new WindowClientCapabilities();
            window.setWorkDoneProgress(workDoneProgress);
            params.setCapabilities(new ClientCapabilities(null, null, window, null));
        }
        server.initialize(params).get();
        server.initialized(new InitializedParams());
    }

    private void open(String uri) {
        server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "nextflow", 1, INVALID_SCRIPT)));
    }

    private void awaitDiagnostics(String uri) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000;
        while( System.currentTimeMillis() < deadline ) {
            if( !client.getDiagnostics(uri).isEmpty() )
                return;
            Thread.sleep(100);
        }
        assertThat(client.getDiagnostics(uri)).as("diagnostics for " + uri).isNotEmpty();
    }

}

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

package nextflow.lsp

import java.nio.file.Files
import java.nio.file.Path

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Test the workspace setup of the language server for clients
 * that don't send a configuration (e.g. editors other than VS Code).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class NextflowLanguageServerTest extends Specification {

    static final String INVALID_SCRIPT = 'workflow {\n'

    @TempDir
    Path workspace

    TestLanguageClient client = new TestLanguageClient()

    NextflowLanguageServer server = new NextflowLanguageServer(50)

    def setup() {
        server.connect(client)
    }

    def 'should report diagnostics without a configuration' () {
        given:
        def uri = writeScript('main.nf')

        when:
        initialize(workspaceFolders: [new WorkspaceFolder(workspace.toUri().toString(), 'test')])
        open(uri)
        then:
        awaitDiagnostics(uri)
    }

    def 'should use the root URI if there are no workspace folders' () {
        given:
        def uri = writeScript('main.nf')

        when:
        initialize(rootUri: workspace.toUri().toString())
        open(uri)
        then:
        awaitDiagnostics(uri)
    }

    def 'should report diagnostics for files outside of the workspace folders' () {
        given:
        def uri = writeScript('main.nf')

        when:
        initialize([:])
        open(uri)
        then:
        awaitDiagnostics(uri)
    }

    def 'should exclude the work directory by default' () {
        given:
        def uri = writeScript('main.nf')
        def excludedUri = writeScript('work/ab/cdef/main.nf')

        when:
        initialize(workspaceFolders: [new WorkspaceFolder(workspace.toUri().toString(), 'test')])
        open(uri)
        then:
        awaitDiagnostics(uri)
        !client.getAllDiagnostics().containsKey(excludedUri)
    }

    def 'should report progress only if the client supports it' () {
        when:
        initialize(workspaceFolders: [new WorkspaceFolder(workspace.toUri().toString(), 'test')], workDoneProgress: supported)
        then:
        client.getProgress().isEmpty() != supported

        where:
        supported << [true, false]
    }

    private String writeScript(String path) {
        def file = workspace.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, INVALID_SCRIPT)
        return file.toUri().toString()
    }

    private void initialize(Map opts) {
        def params = new InitializeParams()
        params.setWorkspaceFolders(opts.workspaceFolders as List<WorkspaceFolder>)
        params.setRootUri(opts.rootUri as String)
        if( opts.workDoneProgress != null ) {
            def window = new WindowClientCapabilities()
            window.setWorkDoneProgress(opts.workDoneProgress as Boolean)
            params.setCapabilities(new ClientCapabilities(null, null, window, null))
        }
        server.initialize(params).get()
        server.initialized(new InitializedParams())
    }

    private void open(String uri) {
        server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, 'nextflow', 1, INVALID_SCRIPT)))
    }

    private boolean awaitDiagnostics(String uri) {
        def deadline = System.currentTimeMillis() + 10_000
        while( System.currentTimeMillis() < deadline ) {
            if( !client.getDiagnostics(uri).isEmpty() )
                return true
            sleep(100)
        }
        return false
    }

}

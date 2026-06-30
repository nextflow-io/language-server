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

import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.lsp.spec.PluginSpecCache
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 * Tests for the update mechanics in the base LanguageService:
 * change tracking, the workspace scan / scanned flag, and initialize().
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class LanguageServiceTest extends Specification {

    // --- helpers

    /**
     * Force the initial workspace scan with an empty file set so that
     * `scanned` becomes true. Subsequent updates then compile changed files
     * directly instead of deferring (and re-triggering) the scan.
     */
    private void scanEmptyWorkspace(RecordingLanguageService service) {
        service.workspaceFilesOverride = [] as Set
        service.updateNow()
    }

    private int codeLensCount(RecordingLanguageService service, String uri) {
        return service.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri))).size()
    }

    private List<String> documentSymbolNames(RecordingLanguageService service, String uri) {
        return service
            .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)))
            .collect { it.getRight().getName() }
    }

    private void changeFull(RecordingLanguageService service, String uri, String contents) {
        service.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, 2),
            [ new TextDocumentContentChangeEvent(contents.stripIndent()) ]
        ))
    }

    private void changeRange(RecordingLanguageService service, String uri, Range range, String text) {
        service.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, 2),
            [ new TextDocumentContentChangeEvent(range, text) ]
        ))
    }

    private void close(RecordingLanguageService service, String uri) {
        service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)))
    }

    // --- Group 1: change tracking -> recompile

    def 'should recompile after a full-content change' () {
        given:
        def client = new TestLanguageClient()
        def service = recordingService(client: client)
        def uri = getUri('main.nf')

        when: 'a valid file is opened'
        scanEmptyWorkspace(service)
        open(service, uri, '''\
            workflow {
                println('Hello!')
            }
            ''')
        service.updateNow()
        then: 'no diagnostics'
        client.getDiagnostics(uri).isEmpty()

        when: 'the file is changed to introduce a syntax error'
        changeFull(service, uri, 'workflow {\n')
        service.updateNow()
        then: 'the error is reported'
        !client.getDiagnostics(uri).isEmpty()
    }

    def 'should recompile after an incremental change' () {
        given:
        def service = recordingService()
        def uri = getUri('main.nf')

        when:
        scanEmptyWorkspace(service)
        open(service, uri, 'workflow HELLO {\n}\n')
        service.updateNow()
        then:
        documentSymbolNames(service, uri) == ['workflow HELLO']

        when: 'the workflow name is edited in place'
        changeRange(service, uri, new Range(new Position(0, 9), new Position(0, 14)), 'WORLD')
        service.updateNow()
        then:
        documentSymbolNames(service, uri) == ['workflow WORLD']
    }

    def 'should drop the AST of a closed in-memory file' () {
        given:
        def service = recordingService()
        def uri = getUri('main.nf')

        when:
        scanEmptyWorkspace(service)
        open(service, uri, 'workflow {\n}\n')
        service.updateNow()
        then:
        codeLensCount(service, uri) == 1

        when: 'the file is closed (it exists only in memory, not on disk)'
        close(service, uri)
        service.updateNow()
        then:
        codeLensCount(service, uri) == 0
    }

    def 'should compile multiple changed files in a single update pass' () {
        given:
        def service = recordingService()
        def aUri = getUri('a.nf')
        def bUri = getUri('b.nf')

        when:
        scanEmptyWorkspace(service)
        def baseline = service.getUpdateCount()
        open(service, aUri, 'workflow {\n}\n')
        open(service, bUri, 'workflow {\n}\n')
        service.updateNow()
        then: 'one compile pass handled both files'
        service.getUpdateCount() == baseline + 1
        codeLensCount(service, aUri) == 1
        codeLensCount(service, bUri) == 1
    }

    // --- Group 2: scanned flag / deferred scan

    def 'should scan the workspace on the first update with no pending changes' () {
        given:
        def service = recordingService()

        when:
        service.workspaceFilesOverride = [] as Set
        service.updateNow()
        then: 'the workspace is scanned exactly once'
        service.getScanCount() == 1

        when: 'a later update has no changes'
        service.updateNow()
        then: 'the workspace is not rescanned'
        service.getScanCount() == 1
    }

    def 'should defer the scan when changes are pending before the first scan' () {
        given:
        def client = new TestLanguageClient()
        def service = recordingService(client: client)
        def uri = getUri('main.nf')
        service.workspaceFilesOverride = [] as Set

        when: 'a file with an error is opened and compiled before any scan'
        open(service, uri, 'workflow {\n')
        service.updateNow()
        then: 'the changed file was compiled but the scan was deferred'
        !client.getDiagnostics(uri).isEmpty()
        service.getScanCount() == 0

        when: 'a subsequent update has no pending changes'
        service.updateNow()
        then: 'the deferred scan now runs'
        service.getScanCount() == 1
    }

    def 'should drop in-memory files when the scan finds nothing on disk' () {
        given:
        def service = recordingService()
        def uri = getUri('main.nf')
        service.workspaceFilesOverride = [] as Set

        when: 'a file is opened (deferred compile), then the scan runs with an empty workspace'
        open(service, uri, 'workflow {\n}\n')
        service.updateNow()
        service.updateNow()
        then:
        service.getScanCount() == 1
        codeLensCount(service, uri) == 0
    }

    def 'should keep open files on scan when there is no workspace root' () {
        given:
        def service = recordingService(rootUri: null)
        def uri = getUri('main.nf')

        when: 'with no root, the scan falls back to the set of open files'
        open(service, uri, 'workflow {\n}\n')
        service.updateNow()
        service.updateNow()
        then:
        service.getScanCount() == 1
        codeLensCount(service, uri) == 1
    }

    // --- Group 3: initialized gating + initialize()

    def 'should not update before the service is initialized' () {
        given: 'a connected but uninitialized service'
        def service = new RecordingLanguageService(workspaceRootUri())
        service.connect(new TestLanguageClient())
        def uri = getUri('main.nf')

        when:
        open(service, uri, 'workflow {\n}\n')
        service.updateNow()
        then: 'update() ran but returned early: nothing was scanned'
        service.getUpdateCount() == 1
        service.getScanCount() == 0
    }

    def 'should clear diagnostics and reset the scan state on initialize' () {
        given:
        def client = new TestLanguageClient()
        def service = recordingService(client: client)
        def uri = getUri('main.nf')

        when:
        scanEmptyWorkspace(service)
        open(service, uri, 'workflow {\n')
        service.updateNow()
        then: 'an error is reported and the workspace has been scanned'
        !client.getDiagnostics(uri).isEmpty()
        service.getScanCount() == 1

        when: 're-initializing the service'
        def configuration = LanguageServerConfiguration.defaults()
        service.initialize(configuration, new PluginSpecCache(configuration.pluginRegistryUrl()))
        then: 'diagnostics for the previously-open file are cleared'
        client.getDiagnostics(uri).isEmpty()

        when: 'an update runs after re-initialization'
        service.updateNow()
        then: 'the workspace is scanned again (scanned flag was reset)'
        service.getScanCount() == 2
    }

    // --- Group 4: awaitUpdate / debounce coordination (timing-sensitive)

    def 'a request should await a pending debounced update' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when: 'the file is opened but left to debounce (no updateNow)'
        open(service, uri, '''\
            workflow {
            }
            ''')
        def lenses = service.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri)))
        then: 'the request blocked until the debounced compile produced the workflow'
        lenses.size() == 1
    }

    def 'a request should not block when no update is pending' () {
        given:
        def service = recordingService()
        def uri = getUri('main.nf')
        scanEmptyWorkspace(service)
        open(service, uri, 'workflow {\n}\n')
        service.updateNow()

        when: 'the service is idle and a request arrives'
        def start = System.currentTimeMillis()
        def count = codeLensCount(service, uri)
        def elapsed = System.currentTimeMillis() - start
        then: 'it returns the up-to-date result without waiting out the debounce'
        count == 1
        elapsed < 500
    }

}

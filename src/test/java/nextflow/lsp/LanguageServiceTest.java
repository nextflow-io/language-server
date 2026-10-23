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
import java.util.Set;

import nextflow.lsp.services.LanguageServerConfiguration;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the update mechanics in the base LanguageService:
 * change tracking, the workspace scan / scanned flag, and initialize().
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class LanguageServiceTest {

    // --- helpers

    /**
     * Force the initial workspace scan with an empty file set so that
     * `scanned` becomes true. Subsequent updates then compile changed files
     * directly instead of deferring (and re-triggering) the scan.
     */
    private static void scanEmptyWorkspace(RecordingLanguageService service) {
        service.setWorkspaceFilesOverride(Set.of());
        service.updateNow();
    }

    private static int codeLensCount(RecordingLanguageService service, String uri) {
        return service.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri))).size();
    }

    private static List<String> documentSymbolNames(RecordingLanguageService service, String uri) {
        return service
            .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)))
            .stream()
            .map(it -> it.getRight().getName())
            .toList();
    }

    private static void changeFull(RecordingLanguageService service, String uri, String contents) {
        service.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, 2),
            List.of(new TextDocumentContentChangeEvent(contents.stripIndent()))
        ));
    }

    private static void changeRange(RecordingLanguageService service, String uri, Range range, String text) {
        service.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, 2),
            List.of(new TextDocumentContentChangeEvent(range, text))
        ));
    }

    private static void close(RecordingLanguageService service, String uri) {
        service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
    }

    // --- Group 1: change tracking -> recompile

    @Test
    void recompileAfterFullContentChange() {
        var client = new TestLanguageClient();
        var service = recordingService(client);
        var uri = getUri("main.nf");

        // a valid file is opened -> no diagnostics
        scanEmptyWorkspace(service);
        open(service, uri, """
            workflow {
                println('Hello!')
            }
            """);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();

        // the file is changed to introduce a syntax error -> the error is reported
        changeFull(service, uri, "workflow {\n");
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isNotEmpty();
    }

    @Test
    void recompileAfterIncrementalChange() {
        var service = recordingService();
        var uri = getUri("main.nf");

        scanEmptyWorkspace(service);
        open(service, uri, "workflow HELLO {\n}\n");
        service.updateNow();
        assertThat(documentSymbolNames(service, uri)).containsExactly("workflow HELLO");

        // the workflow name is edited in place
        changeRange(service, uri, new Range(new Position(0, 9), new Position(0, 14)), "WORLD");
        service.updateNow();
        assertThat(documentSymbolNames(service, uri)).containsExactly("workflow WORLD");
    }

    @Test
    void dropAstOfClosedInMemoryFile() {
        var service = recordingService();
        var uri = getUri("main.nf");

        scanEmptyWorkspace(service);
        open(service, uri, "workflow {\n}\n");
        service.updateNow();
        assertThat(codeLensCount(service, uri)).isEqualTo(1);

        // the file is closed (it exists only in memory, not on disk)
        close(service, uri);
        service.updateNow();
        assertThat(codeLensCount(service, uri)).isEqualTo(0);
    }

    @Test
    void compileMultipleChangedFilesInSingleUpdatePass() {
        var service = recordingService();
        var aUri = getUri("a.nf");
        var bUri = getUri("b.nf");

        scanEmptyWorkspace(service);
        var baseline = service.getUpdateCount();
        open(service, aUri, "workflow {\n}\n");
        open(service, bUri, "workflow {\n}\n");
        service.updateNow();

        // one compile pass handled both files
        assertThat(service.getUpdateCount()).isEqualTo(baseline + 1);
        assertThat(codeLensCount(service, aUri)).isEqualTo(1);
        assertThat(codeLensCount(service, bUri)).isEqualTo(1);
    }

    // --- Group 2: scanned flag / deferred scan

    @Test
    void scanWorkspaceOnFirstUpdateWithNoPendingChanges() {
        var service = recordingService();

        // the workspace is scanned exactly once
        service.setWorkspaceFilesOverride(Set.of());
        service.updateNow();
        assertThat(service.getScanCount()).isEqualTo(1);

        // a later update has no changes -> the workspace is not rescanned
        service.updateNow();
        assertThat(service.getScanCount()).isEqualTo(1);
    }

    @Test
    void deferScanWhenChangesArePendingBeforeFirstScan() {
        var client = new TestLanguageClient();
        var service = recordingService(client);
        var uri = getUri("main.nf");
        service.setWorkspaceFilesOverride(Set.of());

        // a file with an error is opened and compiled before any scan
        // -> the changed file was compiled but the scan was deferred
        open(service, uri, "workflow {\n");
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isNotEmpty();
        assertThat(service.getScanCount()).isEqualTo(0);

        // a subsequent update has no pending changes -> the deferred scan now runs
        service.updateNow();
        assertThat(service.getScanCount()).isEqualTo(1);
    }

    @Test
    void dropInMemoryFilesWhenScanFindsNothingOnDisk() {
        var service = recordingService();
        var uri = getUri("main.nf");
        service.setWorkspaceFilesOverride(Set.of());

        // a file is opened (deferred compile), then the scan runs with an empty workspace
        open(service, uri, "workflow {\n}\n");
        service.updateNow();
        service.updateNow();
        assertThat(service.getScanCount()).isEqualTo(1);
        assertThat(codeLensCount(service, uri)).isEqualTo(0);
    }

    @Test
    void keepOpenFilesOnScanWhenThereIsNoWorkspaceRoot() {
        var service = recordingServiceWithoutWorkspace();
        var uri = getUri("main.nf");

        // with no root, the scan falls back to the set of open files
        open(service, uri, "workflow {\n}\n");
        service.updateNow();
        service.updateNow();
        assertThat(service.getScanCount()).isEqualTo(1);
        assertThat(codeLensCount(service, uri)).isEqualTo(1);
    }

    // --- Group 3: initialized gating + initialize()

    @Test
    void doNotUpdateBeforeServiceIsInitialized() {
        // a connected but uninitialized service
        var service = new RecordingLanguageService(workspaceRootUri());
        service.connect(new TestLanguageClient());
        var uri = getUri("main.nf");

        // update() ran but returned early: nothing was scanned
        open(service, uri, "workflow {\n}\n");
        service.updateNow();
        assertThat(service.getUpdateCount()).isEqualTo(1);
        assertThat(service.getScanCount()).isEqualTo(0);
    }

    @Test
    void clearDiagnosticsAndResetScanStateOnInitialize() {
        var client = new TestLanguageClient();
        var service = recordingService(client);
        var uri = getUri("main.nf");

        // an error is reported and the workspace has been scanned
        scanEmptyWorkspace(service);
        open(service, uri, "workflow {\n");
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isNotEmpty();
        assertThat(service.getScanCount()).isEqualTo(1);

        // re-initializing the service -> diagnostics for the previously-open file are cleared
        var configuration = LanguageServerConfiguration.defaults();
        service.initialize(configuration, newConfigService());
        assertThat(client.getDiagnostics(uri)).isEmpty();

        // an update runs after re-initialization
        // -> the workspace is scanned again (scanned flag was reset)
        service.updateNow();
        assertThat(service.getScanCount()).isEqualTo(2);
    }

    // --- Group 4: awaitUpdate / debounce coordination (timing-sensitive)

    @Test
    void requestAwaitsPendingDebouncedUpdate() {
        var service = recordingService(50);
        var uri = getUri("main.nf");
        scanEmptyWorkspace(service);

        // the file is opened but left to debounce (no updateNow)
        open(service, uri, """
            workflow {
            }
            """);
        var lenses = service.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri)));

        // the request blocked until the debounced compile produced the workflow
        assertThat(lenses).hasSize(1);
    }

    @Test
    void requestDoesNotBlockWhenNoUpdateIsPending() {
        var service = recordingService();
        var uri = getUri("main.nf");
        scanEmptyWorkspace(service);
        open(service, uri, "workflow {\n}\n");
        service.updateNow();

        // the service is idle and a request arrives
        var start = System.currentTimeMillis();
        var count = codeLensCount(service, uri);
        var elapsed = System.currentTimeMillis() - start;

        // it returns the up-to-date result without waiting out the debounce
        assertThat(count).isEqualTo(1);
        assertThat(elapsed).isLessThan(500);
    }

}

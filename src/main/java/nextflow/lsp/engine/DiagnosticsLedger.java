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
package nextflow.lsp.engine;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import nextflow.lsp.services.ErrorReportingMode;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.script.control.ParanoidWarning;
import nextflow.script.control.RelatedInformationAware;
import nextflow.script.control.SeverityAware;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;

/**
 * The diagnostics last published for each file.
 *
 * Diagnostics are computed from the built entry of the file's
 * owner and published only if they differ from the last publish,
 * so a rebuild or re-scan never publishes a transient empty list.
 * A file without an owner gets one empty publish and is forgotten.
 *
 * Engine-confined.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class DiagnosticsLedger {

    static final String SOURCE = "nextflow";

    static final String INTERNAL_ERROR_MESSAGE = "Internal error while analysing this file; see the language server log";

    /**
     * Resolves the owner of a file.
     */
    interface Owners {
        /**
         * Get the entry that publishes the diagnostics of a file, or
         * null if no project owns it.
         *
         * @param uri
         */
        FileEntry owner(URI uri);

        ErrorReportingMode errorReportingMode();
    }

    private record Published(List<Diagnostic> diagnostics, Integer version) {}

    private final Engine engine;

    private final Owners owners;

    private final Map<URI, Published> published = new HashMap<>();

    DiagnosticsLedger(Engine engine, Owners owners) {
        this.engine = engine;
        this.owners = owners;
    }

    /**
     * Publish the diagnostics of the given files where they changed.
     *
     * @param uris
     */
    void publish(Collection<URI> uris) {
        for( var uri : new TreeSet<>(uris) )
            publish(uri);
    }

    /**
     * Recompute and publish the diagnostics of every file that was
     * published before, plus the given files.
     *
     * @param uris
     */
    void republishAll(Collection<URI> uris) {
        var all = new TreeSet<>(published.keySet());
        all.addAll(uris);
        publish(all);
    }

    private void publish(URI uri) {
        var entry = owners.owner(uri);
        var last = published.get(uri);
        if( entry == null ) {
            if( last != null && !last.diagnostics().isEmpty() )
                send(uri, List.of(), null);
            published.remove(uri);
            return;
        }
        // keep the last publish until the owner has built the file
        if( !entry.isBuilt() )
            return;
        var documents = engine.documents();
        var version = documents.isOpen(uri) ? entry.builtFromVersion : null;
        var diagnostics = diagnostics(entry, owners.errorReportingMode());
        if( last == null && diagnostics.isEmpty() && version == null )
            return;
        if( last != null && last.diagnostics().equals(diagnostics) && Objects.equals(last.version(), version) )
            return;
        send(uri, diagnostics, version);
        published.put(uri, new Published(diagnostics, version));
    }

    private void send(URI uri, List<Diagnostic> diagnostics, Integer version) {
        var client = engine.client();
        if( client == null )
            return;
        var clientUri = engine.documents().get(uri)
            .map(DocumentStore.OpenDocument::clientUri)
            .orElse(uri.toString());
        client.publishDiagnostics(new PublishDiagnosticsParams(clientUri, diagnostics, version));
    }

    /**
     * Convert the errors and warnings of an entry to diagnostics.
     *
     * @param entry
     * @param mode
     */
    List<Diagnostic> diagnostics(FileEntry entry, ErrorReportingMode mode) {
        var result = new ArrayList<Diagnostic>();
        if( entry.internalError != null && mode != ErrorReportingMode.OFF ) {
            var range = new Range(new Position(0, 0), new Position(0, 0));
            result.add(new Diagnostic(range, INTERNAL_ERROR_MESSAGE, DiagnosticSeverity.Warning, SOURCE));
        }
        for( var error : entry.errors ) {
            if( !mode.isRelevant(error) )
                continue;
            var message = error.getOriginalMessage();
            var range = LanguageServerUtils.errorToRange(error);
            if( range == null ) {
                engine.log(MessageType.Error, entry.uri + ": invalid range for error: " + message);
                continue;
            }
            var severity = error instanceof SeverityAware sa && sa.isSoftError()
                ? DiagnosticSeverity.Warning
                : DiagnosticSeverity.Error;
            var diagnostic = new Diagnostic(range, message, severity, SOURCE);
            if( error instanceof RelatedInformationAware ria )
                diagnostic.setRelatedInformation(relatedInformation(ria, entry.uri));
            result.add(diagnostic);
        }
        for( var warning : entry.warnings ) {
            if( !mode.isRelevant(warning) )
                continue;
            var message = warning.getMessage();
            var range = LanguageServerUtils.warningToRange(warning);
            if( range == null ) {
                engine.log(MessageType.Error, entry.uri + ": invalid range for warning: " + message);
                continue;
            }
            var severity = warning instanceof ParanoidWarning
                ? DiagnosticSeverity.Information
                : DiagnosticSeverity.Warning;
            var diagnostic = new Diagnostic(range, message, severity, SOURCE);
            if( warning instanceof RelatedInformationAware ria )
                diagnostic.setRelatedInformation(relatedInformation(ria, entry.uri));
            result.add(diagnostic);
        }
        return result;
    }

    private static List<DiagnosticRelatedInformation> relatedInformation(RelatedInformationAware ria, URI uri) {
        var otherNode = ria.getOtherNode();
        if( otherNode == null )
            return null;
        var location = LanguageServerUtils.astNodeToLocation(otherNode, uri);
        return List.of(new DiagnosticRelatedInformation(location, ria.getOtherMessage()));
    }

}

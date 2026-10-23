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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import nextflow.lsp.util.Positions;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

/**
 * The open documents, keyed by canonical URI.
 *
 * Engine-confined: every method asserts that it is called on the
 * engine thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class DocumentStore {

    /**
     * An open document.
     *
     * @param uri the canonical URI
     * @param clientUri the URI as sent by the client, for publishing
     * @param text
     * @param version
     * @param languageId
     */
    record OpenDocument(URI uri, String clientUri, String text, int version, String languageId) {}

    private final SerialExecutor executor;

    private final Map<URI, OpenDocument> documents = new HashMap<>();

    DocumentStore(SerialExecutor executor) {
        this.executor = executor;
    }

    /**
     * Open a document, replacing it if it is already open.
     *
     * Returns empty if the URI is not a file URI.
     *
     * @param params
     */
    Optional<OpenDocument> open(DidOpenTextDocumentParams params) {
        EngineThread.check(executor);
        var item = params.getTextDocument();
        var uri = Uris.canonical(item.getUri());
        if( uri.isEmpty() )
            return Optional.empty();
        var text = item.getText() != null ? item.getText() : "";
        var document = new OpenDocument(uri.get(), item.getUri(), text, item.getVersion(), item.getLanguageId());
        documents.put(document.uri(), document);
        return Optional.of(document);
    }

    /**
     * Apply the changes of a didChange notification in order, and
     * record the new version.
     *
     * Returns empty if the document is not open.
     *
     * @param params
     */
    Optional<OpenDocument> change(DidChangeTextDocumentParams params) {
        EngineThread.check(executor);
        var identifier = params.getTextDocument();
        var uri = Uris.canonical(identifier.getUri());
        if( uri.isEmpty() )
            return Optional.empty();
        var document = documents.get(uri.get());
        if( document == null )
            return Optional.empty();
        var text = applyChanges(document.text(), params.getContentChanges());
        var version = identifier.getVersion() != null ? identifier.getVersion() : document.version();
        var result = new OpenDocument(document.uri(), document.clientUri(), text, version, document.languageId());
        documents.put(result.uri(), result);
        return Optional.of(result);
    }

    /**
     * Close a document.
     *
     * Returns empty if the document is not open.
     *
     * @param uri canonical URI
     */
    Optional<OpenDocument> close(URI uri) {
        EngineThread.check(executor);
        return Optional.ofNullable(documents.remove(uri));
    }

    Optional<OpenDocument> get(URI uri) {
        EngineThread.check(executor);
        return Optional.ofNullable(documents.get(uri));
    }

    /**
     * Get the version of an open document, or null if it is not open.
     *
     * @param uri canonical URI
     */
    Integer version(URI uri) {
        EngineThread.check(executor);
        var document = documents.get(uri);
        return document != null ? document.version() : null;
    }

    boolean isOpen(URI uri) {
        EngineThread.check(executor);
        return documents.containsKey(uri);
    }

    /**
     * Get the canonical URIs of the open documents, in sorted order.
     */
    List<URI> uris() {
        EngineThread.check(executor);
        return documents.keySet().stream().sorted().toList();
    }

    /**
     * Apply a list of content changes to a text, in order.
     *
     * A change without a range replaces the whole text. Positions
     * are clamped to the text (see {@link Positions#getOffset}), and
     * a range whose end precedes its start is treated as empty.
     *
     * @param text
     * @param changes
     */
    static String applyChanges(String text, List<TextDocumentContentChangeEvent> changes) {
        if( changes == null )
            return text;
        for( var change : changes ) {
            var newText = change.getText() != null ? change.getText() : "";
            var range = change.getRange();
            if( range == null ) {
                text = newText;
                continue;
            }
            var start = Positions.getOffset(text, range.getStart());
            var end = Math.max(start, Positions.getOffset(text, range.getEnd()));
            text = new StringBuilder(text.length() - (end - start) + newText.length())
                .append(text, 0, start)
                .append(newText)
                .append(text, end, text.length())
                .toString();
        }
        return text;
    }

}

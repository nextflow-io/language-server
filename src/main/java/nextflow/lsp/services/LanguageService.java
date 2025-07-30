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
package nextflow.lsp.services;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.file.FileCache;
import nextflow.lsp.util.DebouncingExecutor;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.Positions;
import nextflow.script.control.ParanoidWarning;
import nextflow.script.control.RelatedInformationAware;
import nextflow.script.formatter.FormattingOptions;
import nextflow.util.PathUtils;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Base language service which handles document updates,
 * publishes diagnostics, and provides language features.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public abstract class LanguageService {

    private static final long DEBOUNCE_MILLIS = 1_000;

    private static Logger log = Logger.getInstance();

    private String rootUri;

    private LanguageClient client;

    private FileCache fileCache = new FileCache();

    private DebouncingExecutor updateExecutor;

    public LanguageService(String rootUri) {
        this.rootUri = rootUri;
        this.updateExecutor = new DebouncingExecutor(DEBOUNCE_MILLIS, this::update);
    }

    public abstract boolean matchesFile(String uri);
    protected abstract ASTNodeCache getAstCache();
    protected CallHierarchyProvider getCallHierarchyProvider() { return null; }
    protected CodeLensProvider getCodeLensProvider() { return null; }
    protected CompletionProvider getCompletionProvider(int maxItems, boolean extended) { return null; }
    protected DefinitionProvider getDefinitionProvider() { return null; }
    protected FormattingProvider getFormattingProvider() { return null; }
    protected HoverProvider getHoverProvider() { return null; }
    protected LinkProvider getLinkProvider() { return null; }
    protected ReferenceProvider getReferenceProvider() { return null; }
    protected RenameProvider getRenameProvider() { return null; }
    protected SemanticTokensProvider getSemanticTokensProvider() { return null; }
    protected SymbolProvider getSymbolProvider() { return null; }

    private volatile boolean initialized;

    private volatile boolean scanned;

    private volatile LanguageServerConfiguration configuration;

    public void initialize(LanguageServerConfiguration configuration) {
        synchronized (this) {
            this.initialized = false;
            this.scanned = false;
            this.configuration = configuration;

            clearDiagnostics();
            getAstCache().clear();

            this.initialized = true;
        }
    }

    public void connect(LanguageClient client) {
        this.client = client;
    }

    // -- NOTIFICATIONS

    public void didOpen(DidOpenTextDocumentParams params) {
        fileCache.didOpen(params);
        updateLater();
    }

    public void didChange(DidChangeTextDocumentParams params) {
        fileCache.didChange(params);
        updateLater();
    }

    public void didClose(DidCloseTextDocumentParams params) {
        fileCache.didClose(params);
        updateLater();
    }

    // --- REQUESTS

    public List<CallHierarchyItem> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        var provider = getCallHierarchyProvider();
        if( provider == null )
            return Collections.emptyList();

        return provider.prepare(params.getTextDocument(), params.getPosition());
    }

    public List<CallHierarchyIncomingCall> callHierarchyIncomingCalls(CallHierarchyItem item) {
        var provider = getCallHierarchyProvider();
        if( provider == null )
            return Collections.emptyList();

        return provider.incomingCalls(item);
    }

    public List<CallHierarchyOutgoingCall> callHierarchyOutgoingCalls(CallHierarchyItem item) {
        var provider = getCallHierarchyProvider();
        if( provider == null )
            return Collections.emptyList();

        return provider.outgoingCalls(item);
    }

    public List<CodeLens> codeLens(CodeLensParams params) {
        var provider = getCodeLensProvider();
        if( provider == null )
            return Collections.emptyList();

        awaitUpdate();
        return provider.codeLens(params.getTextDocument());
    }

    public Either<List<CompletionItem>, CompletionList> completion(CompletionParams params, int maxItems, boolean extended) {
        var provider = getCompletionProvider(maxItems, extended);
        if( provider == null )
            return Either.forLeft(Collections.emptyList());

        updateNow();
        return provider.completion(params.getTextDocument(), params.getPosition());
    }

    public Either<List<? extends Location>, List<? extends LocationLink>> definition(DefinitionParams params) {
        var provider = getDefinitionProvider();
        if( provider == null )
            return Either.forLeft(Collections.emptyList());

        return provider.definition(params.getTextDocument(), params.getPosition());
    }

    public List<DocumentLink> documentLink(DocumentLinkParams params) {
        var provider = getLinkProvider();
        if( provider == null )
            return Collections.emptyList();

        awaitUpdate();
        return provider.documentLink(params.getTextDocument());
    }

    public List<Either<SymbolInformation, DocumentSymbol>> documentSymbol(DocumentSymbolParams params) {
        var provider = getSymbolProvider();
        if( provider == null )
            return Collections.emptyList();

        awaitUpdate();
        return provider.documentSymbol(params.getTextDocument());
    }

    public Object executeCommand(String command, List<Object> arguments) {
        return null;
    }

    public List<? extends TextEdit> formatting(URI uri, FormattingOptions options) {
        var provider = getFormattingProvider();
        if( provider == null )
            return Collections.emptyList();

        updateNow();
        return provider.formatting(uri, options);
    }

    public Hover hover(HoverParams params) {
        var provider = getHoverProvider();
        if( provider == null )
            return null;

        return provider.hover(params.getTextDocument(), params.getPosition());
    }

    public List<? extends Location> references(ReferenceParams params) {
        var provider = getReferenceProvider();
        if( provider == null )
            return Collections.emptyList();

        return provider.references(params.getTextDocument(), params.getPosition(), params.getContext().isIncludeDeclaration());
    }

    public WorkspaceEdit rename(RenameParams params) {
        var provider = getRenameProvider();
        if( provider == null )
            return null;

        return provider.rename(params.getTextDocument(), params.getPosition(), params.getNewName());
    }

    public SemanticTokens semanticTokensFull(SemanticTokensParams params) {
        var provider = getSemanticTokensProvider();
        if( provider == null )
            return null;

        awaitUpdate();
        return provider.semanticTokensFull(params.getTextDocument());
    }

    public List<? extends WorkspaceSymbol> symbol(WorkspaceSymbolParams params) {
        var provider = getSymbolProvider();
        if( provider == null )
            return Collections.emptyList();

        return provider.symbol(params.getQuery());
    }

    // --- INTERNAL

    private Lock updateLock = new ReentrantLock();

    private Condition updateCondition = updateLock.newCondition();

    private volatile boolean awaitingUpdate;

    protected void updateLater() {
        awaitingUpdate = true;
        updateExecutor.executeLater();
    }

    protected void updateNow() {
        updateExecutor.executeNow();
    }

    protected void awaitUpdate() {
        if( !awaitingUpdate )
            return;

        updateLock.lock();
        try {
            updateCondition.await(DEBOUNCE_MILLIS * 2, TimeUnit.MILLISECONDS);
        }
        catch( InterruptedException e ) {
        }
        finally {
            updateLock.unlock();
        }
    }

    /**
     * Re-compile any changed files.
     */
    protected void update() {
        synchronized (this) {
            update0();
        }

        updateLock.lock();
        try {
            updateCondition.signalAll();
            awaitingUpdate = false;
        }
        finally {
            updateLock.unlock();
        }
    }

    private void update0() {
        if( !initialized )
            return;

        var astCache = getAstCache();
        var uris = fileCache.removeChangedFiles();

        if( !scanned ) {
            if( uris.isEmpty() ) {
                uris = getWorkspaceFiles();
                astCache.clear();
                this.scanned = true;
            }
            else {
                updateLater();
            }
        }

        if( log.isDebugEnabled() ) {
            var builder = new StringBuilder();
            builder.append("update\n");
            uris.forEach((uri) -> {
                builder.append("- ");
                builder.append(uri.toString().replace(rootUri, "."));
                builder.append('\n');
            });
            log.debug(builder.toString());
        }

        var changedUris = astCache.update(uris, fileCache);
        publishDiagnostics(changedUris);
    }

    /**
     * Scan the workspace for files that can be managed by the language service.
     *
     * If there is no workspace root, use the set of open files instead.
     */
    protected Set<URI> getWorkspaceFiles() {
        if( rootUri == null ) {
            return fileCache.getOpenFiles();
        }
        try {
            var result = new HashSet<URI>();
            PathUtils.visitFiles(
                Path.of(URI.create(rootUri)),
                (path) -> !PathUtils.isExcluded(path, configuration.excludePatterns()),
                (path) -> {
                    if( matchesFile(path.toString()) )
                        result.add(path.toUri());
                });
            return result;
        }
        catch( IOException e ) {
            log.error("Failed to query workspace files: " + rootUri + " -- cause: " + e.toString());
            return Collections.emptySet();
        }
    }

    /**
     * Publish diagnostics for a set of compilation errors.
     *
     * @param changedUris
     */
    protected void publishDiagnostics(Set<URI> changedUris) {
        var astCache = getAstCache();
        changedUris.forEach((uri) -> {
            var diagnostics = new ArrayList<Diagnostic>();
            for( var error : astCache.getErrors(uri) ) {
                if( !configuration.errorReportingMode().isRelevant(error) )
                    continue;

                var message = error.getOriginalMessage();
                var range = LanguageServerUtils.errorToRange(error);
                if( range == null ) {
                    log.error(uri + ": invalid range for error: " + message);
                    continue;
                }

                var diagnostic = new Diagnostic(range, message, DiagnosticSeverity.Error, "nextflow");
                if( error instanceof RelatedInformationAware ria )
                    diagnostic.setRelatedInformation(relatedInformation(ria, uri));
                diagnostics.add(diagnostic);
            }

            for( var warning : astCache.getWarnings(uri) ) {
                if( !configuration.errorReportingMode().isRelevant(warning) )
                    continue;

                var message = warning.getMessage();
                var range = LanguageServerUtils.warningToRange(warning);
                if( range == null ) {
                    log.error(uri + ": invalid range for warning: " + message);
                    continue;
                }

                var severity = warning instanceof ParanoidWarning
                    ? DiagnosticSeverity.Information
                    : DiagnosticSeverity.Warning;
                var diagnostic = new Diagnostic(range, message, severity, "nextflow");
                if( warning instanceof RelatedInformationAware ria )
                    diagnostic.setRelatedInformation(relatedInformation(ria, uri));
                diagnostics.add(diagnostic);
            }

            var params = new PublishDiagnosticsParams(uri.toString(), diagnostics);
            client.publishDiagnostics(params);
        });
    }

    private static List<DiagnosticRelatedInformation> relatedInformation(RelatedInformationAware ria, URI uri) {
        var otherNode = ria.getOtherNode();
        if( otherNode != null ) {
            var result = new ArrayList<DiagnosticRelatedInformation>();
            var location = LanguageServerUtils.astNodeToLocation(otherNode, uri);
            var dri = new DiagnosticRelatedInformation(location, ria.getOtherMessage());
            result.add(dri);
            return result;
        }
        return null;
    }

    /**
     * Clear diagnostics when the language service is shut down.
     */
    public void clearDiagnostics() {
        getAstCache().getUris().forEach((uri) -> {
            var params = new PublishDiagnosticsParams(uri.toString(), Collections.emptyList());
            client.publishDiagnostics(params);
        });
    }

}

/*
 * Copyright 2024, Seqera Labs
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

import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.compiler.Compiler;
import nextflow.lsp.compiler.SyntaxWarning;
import nextflow.lsp.file.FileCache;
import nextflow.lsp.file.PathUtils;
import nextflow.lsp.util.DebouncingExecutor;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.Positions;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
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
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
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

    private static final int DEBOUNCE_MILLIS = 1_000;

    private static final Object DEBOUNCE_KEY = new Object();

    private static Logger log = Logger.getInstance();

    private LanguageClient client;

    private FileCache fileCache = new FileCache();

    private DebouncingExecutor updateExecutor;

    public LanguageService() {
        this.updateExecutor = new DebouncingExecutor(DEBOUNCE_MILLIS, (key) -> update());
    }

    public abstract boolean matchesFile(String uri);
    protected abstract ASTNodeCache getAstCache();
    protected CallHierarchyProvider getCallHierarchyProvider() { return null; }
    protected CompletionProvider getCompletionProvider() { return null; }
    protected DefinitionProvider getDefinitionProvider() { return null; }
    protected FormattingProvider getFormattingProvider() { return null; }
    protected HoverProvider getHoverProvider() { return null; }
    protected LinkProvider getLinkProvider() { return null; }
    protected ReferenceProvider getReferenceProvider() { return null; }
    protected RenameProvider getRenameProvider() { return null; }
    protected SymbolProvider getSymbolProvider() { return null; }

    private volatile boolean initialized;

    private volatile boolean suppressWarnings;

    public void initialize(String rootUri, List<String> excludes, boolean suppressWarnings) {
        synchronized (this) {
            this.initialized = false;
            this.suppressWarnings = suppressWarnings;

            var uris = rootUri != null
                ? getWorkspaceFiles(rootUri, excludes)
                : fileCache.getOpenFiles();

            var astCache = getAstCache();
            astCache.clear();
            var errors = astCache.update(uris, fileCache);
            publishDiagnostics(errors);

            this.initialized = true;
        }
    }

    protected Set<URI> getWorkspaceFiles(String rootUri, List<String> excludes) {
        try {
            var root = Path.of(URI.create(rootUri));
            var result = new HashSet<URI>();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                    return PathUtils.isPathExcluded(path, excludes)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if( matchesFile(path.toString()) && !PathUtils.isPathExcluded(path, excludes) )
                        result.add(path.toUri());
                    return FileVisitResult.CONTINUE;
                }
            });

            return result;
        }
        catch( IOException e ) {
            log.error("Failed to query workspace files: " + rootUri + " -- cause: " + e.toString());
            return Collections.emptySet();
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

    public Either<List<CompletionItem>, CompletionList> completion(CompletionParams params) {
        var provider = getCompletionProvider();
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

        return provider.documentLink(params.getTextDocument());
    }

    public List<Either<SymbolInformation, DocumentSymbol>> documentSymbol(DocumentSymbolParams params) {
        var provider = getSymbolProvider();
        if( provider == null )
            return Collections.emptyList();

        return provider.documentSymbol(params.getTextDocument());
    }

    public List<? extends TextEdit> formatting(URI uri, CustomFormattingOptions options) {
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

    public List<? extends SymbolInformation> symbol(WorkspaceSymbolParams params) {
        var provider = getSymbolProvider();
        if( provider == null )
            return Collections.emptyList();

        return provider.symbol(params.getQuery());
    }

    // --- INTERNAL

    protected void updateLater() {
        updateExecutor.submit(DEBOUNCE_KEY);
    }

    protected void updateNow() {
        updateExecutor.executeNow(DEBOUNCE_KEY);
    }

    /**
     * Re-compile any changed files.
     */
    protected void update() {
        synchronized (this) {
            if( !initialized )
                return;

            var uris = fileCache.removeChangedFiles();

            log.debug("update " + DefaultGroovyMethods.join(uris, " , "));
            var errors = getAstCache().update(uris, fileCache);
            publishDiagnostics(errors);
        }
    }

    /**
     * Publish diagnostics for a set of compilation errors.
     *
     * @param errorsByUri
     */
    protected void publishDiagnostics(Map<URI, List<SyntaxException>> errorsByUri) {
        errorsByUri.forEach((uri, errors) -> {
            var diagnostics = new ArrayList<Diagnostic>();
            for( var error : errors ) {
                var range = LanguageServerUtils.syntaxExceptionToRange(error);
                if( range == null ) {
                    log.error(uri + ": invalid range for error: " + error.getMessage());
                    continue;
                }

                var message = error.getMessage();
                var severity = error instanceof SyntaxWarning
                    ? DiagnosticSeverity.Warning
                    : DiagnosticSeverity.Error;

                if( suppressWarnings && severity == DiagnosticSeverity.Warning )
                    continue;

                var diagnostic = new Diagnostic(range, message, severity, "nextflow");
                diagnostics.add(diagnostic);
            }

            var params = new PublishDiagnosticsParams(uri.toString(), diagnostics);
            client.publishDiagnostics(params);
        });
    }

    /**
     * Clear diagnostics when the language service is shut down.
     */
    public void clearDiagnostics() {
        var errorsByUri = getAstCache().update(Collections.emptySet(), fileCache);
        errorsByUri.forEach((uri, errors) -> {
            var params = new PublishDiagnosticsParams(uri.toString(), Collections.emptyList());
            client.publishDiagnostics(params);
        });
    }

}

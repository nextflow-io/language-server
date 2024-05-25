package nextflow.lsp.services

import java.nio.file.Path
import java.util.concurrent.CompletableFuture

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.CompilationCache
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient

@CompileStatic
abstract class LanguageService {

    private static Logger log = Logger.instance

    private LanguageClient client
    private FileCache fileCache = new FileCache()
    private ASTNodeCache astCache
    private String previousUri

    private CompletionProvider completionProvider
    private SymbolProvider symbolProvider
    private HoverProvider hoverProvider

    LanguageService() {
        this.astCache = new ASTNodeCache(getCompiler())
        this.completionProvider = getCompletionProvider(astCache)
        this.symbolProvider = getSymbolProvider(astCache)
        this.hoverProvider = getHoverProvider(astCache)
    }

    abstract boolean matchesFile(String uri)
    abstract protected CompilationCache getCompiler()
    protected CompletionProvider getCompletionProvider(ASTNodeCache astCache) { null }
    protected SymbolProvider getSymbolProvider(ASTNodeCache astCache) { null }
    protected HoverProvider getHoverProvider(ASTNodeCache astCache) { null }

    void setWorkspaceRoot(Path workspaceRoot) {
        astCache.initialize(workspaceRoot, fileCache)
        publishDiagnostics()
        previousUri = null
    }

    void connect(LanguageClient client) {
        this.client = client
    }

    // -- NOTIFICATIONS

    void didOpen(DidOpenTextDocumentParams params) {
        fileCache.didOpen(params)
        update()
        previousUri = params.getTextDocument().getUri()
    }

    void didChange(DidChangeTextDocumentParams params) {
        fileCache.didChange(params)
        update()
        previousUri = params.getTextDocument().getUri()
    }

    void didClose(DidCloseTextDocumentParams params) {
        fileCache.didClose(params)
        update()
        previousUri = params.getTextDocument().getUri()
    }

    // --- REQUESTS

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        if( !completionProvider )
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()))

        final uri = params.getTextDocument().getUri()
        recompileIfContextChanged(uri)

        final result = completionProvider.completion(params.getTextDocument(), params.getPosition())
        return CompletableFuture.completedFuture(result)
    }

    CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        if( !symbolProvider )
            return CompletableFuture.completedFuture(Collections.emptyList())

        final uri = params.getTextDocument().getUri()
        recompileIfContextChanged(uri)

        final result = symbolProvider.documentSymbol(params.getTextDocument())
        return CompletableFuture.completedFuture(result)
    }

    CompletableFuture<Hover> hover(HoverParams params) {
        if( !hoverProvider )
            return CompletableFuture.completedFuture(null)

        final uri = params.getTextDocument().getUri()
        recompileIfContextChanged(uri)

        final result = hoverProvider.hover(params.getTextDocument(), params.getPosition())
        return CompletableFuture.completedFuture(result)
    }

    CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
        if( !symbolProvider )
            return CompletableFuture.completedFuture(Collections.emptyList())

        final result = symbolProvider.symbol(params.getQuery())
        return CompletableFuture.completedFuture(result)
    }

    // --- INTERNAL

    private void recompileIfContextChanged(String uri) {
        if( previousUri != null && previousUri != uri ) {
            fileCache.markChanged(uri)
            update()
            previousUri = uri
        }
    }

    /**
     * Re-compile any changed files.
     */
    private void update() {
        astCache.update(fileCache.getChangedFiles())
        fileCache.resetChangedFiles()
        publishDiagnostics()
    }

    /**
     * Publish diagnostics for compilation errors.
     */
    private void publishDiagnostics() {
        astCache.getCompilerErrors().forEach((uri, errors) -> {
            final List<Diagnostic> diagnostics = []
            for( final error : errors ) {
                final diagnostic = new Diagnostic()
                diagnostic.setRange(LanguageServerUtils.syntaxExceptionToRange(error))
                diagnostic.setSeverity(DiagnosticSeverity.Error)
                diagnostic.setMessage(error.getMessage())
                diagnostics << diagnostic
            }

            final params = new PublishDiagnosticsParams(uri.toString(), diagnostics)
            client.publishDiagnostics(params)
        })
    }

}

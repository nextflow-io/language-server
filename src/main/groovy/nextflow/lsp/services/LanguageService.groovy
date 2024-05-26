package nextflow.lsp.services

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Positions
import org.codehaus.groovy.syntax.SyntaxException
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

/**
 * Base language service which handles document updates,
 * publishes diagnostics, and provides language features.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
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
        this.astCache = getAstCache()
        this.completionProvider = getCompletionProvider(astCache)
        this.symbolProvider = getSymbolProvider(astCache)
        this.hoverProvider = getHoverProvider(astCache)
    }

    abstract boolean matchesFile(String uri)
    abstract protected ASTNodeCache getAstCache()
    protected CompletionProvider getCompletionProvider(ASTNodeCache astCache) { null }
    protected SymbolProvider getSymbolProvider(ASTNodeCache astCache) { null }
    protected HoverProvider getHoverProvider(ASTNodeCache astCache) { null }

    void initialize(Path workspaceRoot) {
        final uris = workspaceRoot != null
            ? getWorkspaceFiles(workspaceRoot)
            : fileCache.getOpenFiles()
        final errors = astCache.update(uris, fileCache)
        publishDiagnostics(errors)
        previousUri = null
    }

    protected Set<URI> getWorkspaceFiles(Path workspaceRoot) {
        try {
            final Set<URI> result = []
            for( final path : Files.walk(workspaceRoot) ) {
                if( path.isDirectory() )
                    continue
                if( !matchesFile(path.toString()) )
                    continue

                result << path.toUri()
            }

            return result
        }
        catch( IOException e ) {
            log.error "Failed to query workspace files: ${workspaceRoot} -- cause: ${e}"
            return Collections.emptySet()
        }
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
        final uris = fileCache.removeChangedFiles()
        final errors = astCache.update(uris, fileCache)
        publishDiagnostics(errors)
    }

    /**
     * Publish diagnostics for a set of compilation errors.
     *
     * @param errorsByUri
     */
    private void publishDiagnostics(Map<URI, List<SyntaxException>> errorsByUri) {
        errorsByUri.forEach((uri, errors) -> {
            final List<Diagnostic> diagnostics = []
            for( final error : errors ) {
                final range = LanguageServerUtils.syntaxExceptionToRange(error)
                if( !Positions.isValid(range.start) || !Positions.isValid(range.end) ) {
                    log.error "${uri}: invalid range for error: ${error.message}"
                    continue
                }

                final diagnostic = new Diagnostic()
                diagnostic.setRange(range)
                diagnostic.setSeverity(DiagnosticSeverity.Error)
                diagnostic.setMessage(error.message)
                diagnostics << diagnostic
            }

            final params = new PublishDiagnosticsParams(uri.toString(), diagnostics)
            client.publishDiagnostics(params)
        })
    }

}

package nextflow.lsp.services

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.DebouncingExecutor
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
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextEdit
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

    private static final int DEBOUNCE_MILLIS = 1_000

    private static final Object DEBOUNCE_KEY = new Object()

    private static Logger log = Logger.instance

    private LanguageClient client
    private FileCache fileCache = new FileCache()
    private ASTNodeCache astCache
    private DebouncingExecutor updateExecutor
    private CompletionProvider completionProvider
    private SymbolProvider symbolProvider
    private FormattingProvider formattingProvider
    private HoverProvider hoverProvider

    LanguageService() {
        this.updateExecutor = new DebouncingExecutor(DEBOUNCE_MILLIS, { key -> update() })
        this.astCache = getAstCache()
        this.completionProvider = getCompletionProvider(astCache)
        this.symbolProvider = getSymbolProvider(astCache)
        this.formattingProvider = getFormattingProvider(astCache)
        this.hoverProvider = getHoverProvider(astCache)
    }

    abstract boolean matchesFile(String uri)
    abstract protected ASTNodeCache getAstCache()
    protected CompletionProvider getCompletionProvider(ASTNodeCache astCache) { null }
    protected SymbolProvider getSymbolProvider(ASTNodeCache astCache) { null }
    protected FormattingProvider getFormattingProvider(ASTNodeCache astCache) { null }
    protected HoverProvider getHoverProvider(ASTNodeCache astCache) { null }

    void initialize(Path workspaceRoot) {
        synchronized (this) {
            final uris = workspaceRoot != null
                ? getWorkspaceFiles(workspaceRoot)
                : fileCache.getOpenFiles()

            final errors = astCache.update(uris, fileCache)
            publishDiagnostics(errors)
        }
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
        updateExecutor.submit(DEBOUNCE_KEY)
    }

    void didChange(DidChangeTextDocumentParams params) {
        fileCache.didChange(params)
        updateExecutor.submit(DEBOUNCE_KEY)
    }

    void didClose(DidCloseTextDocumentParams params) {
        fileCache.didClose(params)
        updateExecutor.submit(DEBOUNCE_KEY)
    }

    // --- REQUESTS

    Either<List<CompletionItem>, CompletionList> completion(CompletionParams params) {
        if( !completionProvider )
            return Either.forLeft(Collections.emptyList())

        return completionProvider.completion(params.getTextDocument(), params.getPosition())
    }

    List<Either<SymbolInformation, DocumentSymbol>> documentSymbol(DocumentSymbolParams params) {
        if( !symbolProvider )
            return Collections.emptyList()

        return symbolProvider.documentSymbol(params.getTextDocument())
    }

    List<? extends TextEdit> formatting(DocumentFormattingParams params) {
        if( !formattingProvider )
            return Collections.emptyList()

        return formattingProvider.formatting(params.getTextDocument(), params.getOptions())
    }

    Hover hover(HoverParams params) {
        if( !hoverProvider )
            return null

        return hoverProvider.hover(params.getTextDocument(), params.getPosition())
    }

    List<? extends SymbolInformation> symbol(WorkspaceSymbolParams params) {
        if( !symbolProvider )
            return Collections.emptyList()

        return symbolProvider.symbol(params.getQuery())
    }

    // --- INTERNAL

    /**
     * Re-compile any changed files.
     */
    protected void update() {
        synchronized (this) {
            final uris = fileCache.removeChangedFiles()

            log.debug "update: ${uris}"
            final errors = astCache.update(uris, fileCache)
            publishDiagnostics(errors)
        }
    }

    /**
     * Publish diagnostics for a set of compilation errors.
     *
     * @param errorsByUri
     */
    protected void publishDiagnostics(Map<URI, List<SyntaxException>> errorsByUri) {
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

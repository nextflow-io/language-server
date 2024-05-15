package nextflow.lsp

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.CompilationCache
import nextflow.lsp.file.FileCache
import nextflow.lsp.providers.CompletionProvider
import nextflow.lsp.providers.DocumentSymbolProvider
import nextflow.lsp.providers.HoverProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService

/**
 * Implementation of language services for Nextflow.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class NextflowServices implements TextDocumentService, WorkspaceService, LanguageClientAware {

    private static Logger log = Logger.instance

    private LanguageClient languageClient
    private Path workspaceRoot
    private FileCache fileCache = new FileCache()
    private CompilationCache compileCache = CompilationCache.create()
    private ASTNodeCache astCache = new ASTNodeCache()
    private URI previousUri
    private Map<URI, List<Diagnostic>> prevDiagnosticsByFile = [:]

    NextflowServices() {
    }

    void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot
        compileCache.initialize(workspaceRoot, fileCache)
    }

    @Override
    void connect(LanguageClient client) {
        this.languageClient = client
    }

    // -- NOTIFICATIONS

    @Override
    void didOpen(DidOpenTextDocumentParams params) {
        log.debug "text/didOpen ${relativePath(params.getTextDocument().getUri())}"

        fileCache.didOpen(params)
        final uri = URI.create(params.getTextDocument().getUri())
        compileAndUpdateAst(uri)
    }

    @Override
    void didChange(DidChangeTextDocumentParams params) {
        log.debug "text/didChange ${relativePath(params.getTextDocument().getUri())}"

        fileCache.didChange(params)
        final uri = URI.create(params.getTextDocument().getUri())
        compileAndUpdateAst(uri)
    }

    @Override
    void didClose(DidCloseTextDocumentParams params) {
        log.debug "text/didClose ${relativePath(params.getTextDocument().getUri())}"

        fileCache.didClose(params)
        final uri = URI.create(params.getTextDocument().getUri())
        compileAndUpdateAst(uri)
    }

    @Override
    void didSave(DidSaveTextDocumentParams params) {
        log.debug "text/didSave ${relativePath(params.getTextDocument().getUri())}"
    }

    @Override
    void didChangeConfiguration(DidChangeConfigurationParams params) {
        log.debug "workspace/didChangeConfiguration ${params.getSettings()}"

        updateCompilationSources()
        compile()
        updateAst()
        previousUri = null
    }

    @Override
    void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        log.debug "workspace/didChangeWatchedFiles"

        final changedUris = params.getChanges().collect { fileEvent -> URI.create(fileEvent.getUri()) } as Set
        updateCompilationSources()
        compile()
        updateAst(changedUris)
    }

    @Override
    void didRenameFiles(RenameFilesParams params) {
        log.debug "workspace/didRenameFiles"
    }

    // --- REQUESTS

    @Override
    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        final uri = URI.create(params.getTextDocument().getUri())
        recompileIfContextChanged(uri)

        final provider = new CompletionProvider(astCache)
        final result = provider.provideCompletion(params.getTextDocument(), params.getPosition())
        return CompletableFuture.completedFuture(result)
    }

    @Override
    CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        final uri = URI.create(params.getTextDocument().getUri())
        recompileIfContextChanged(uri)

        final provider = new DocumentSymbolProvider(astCache)
        final result = provider.provideDocumentSymbols(params.getTextDocument())
        return CompletableFuture.completedFuture(result)
    }

    @Override
    CompletableFuture<Hover> hover(HoverParams params) {
        final uri = URI.create(params.getTextDocument().getUri())
        recompileIfContextChanged(uri)

        final provider = new HoverProvider(astCache)
        final result = provider.provideHover(params.getTextDocument(), params.getPosition())
        return CompletableFuture.completedFuture(result)
    }

    // --- INTERNAL

    private Path relativePath(String uri) {
        workspaceRoot.relativize(Path.of(uri.replace('file://', '')))
    }

    private void recompileIfContextChanged(URI uri) {
        if( previousUri != null && previousUri != uri ) {
            fileCache.markChanged(uri)
            compileAndUpdateAst(uri)
        }
    }

    private void compileAndUpdateAst(URI uri) {
        updateCompilationSources()
        compile()
        updateAst( Collections.singleton(uri) )
        previousUri = uri
    }

    /**
     * Update the compilation cache with the current workspace files.
     */
    private void updateCompilationSources() {
        compileCache.update(workspaceRoot, fileCache)
    }

    /**
     * Compile all source files and publish any errors as diagnostics.
     */
    private void compile() {
        compileCache.compile()
        for( final diagnostics : getDiagnosticsForErrors(compileCache.getErrorCollector()) )
            languageClient.publishDiagnostics(diagnostics)
    }

    /**
     * Get the set of diagnostics for compilation errors.
     *
     * @param collector
     */
    private Set<PublishDiagnosticsParams> getDiagnosticsForErrors(ErrorCollector collector) {
        // create diagnostics for each error
        final Map<URI, List<Diagnostic>> diagnosticsByFile = [:]
        for( final message : collector.getErrors() ?: [] ) {
            if( message !instanceof SyntaxErrorMessage )
                continue
            final cause = ((SyntaxErrorMessage)message).getCause()
            final range = LanguageServerUtils.syntaxExceptionToRange(cause)
            final diagnostic = new Diagnostic()
            diagnostic.setRange(range)
            diagnostic.setSeverity(DiagnosticSeverity.Error)
            diagnostic.setMessage(cause.getMessage())
            final uri = Paths.get(cause.getSourceLocator()).toUri()
            diagnosticsByFile.computeIfAbsent(uri, (key) -> []).add(diagnostic)
        }

        final result = diagnosticsByFile.collect { key, value -> new PublishDiagnosticsParams(key.toString(), value) } as Set

        // reset diagnostics for previous files that no longer have any errors
        for( final key : prevDiagnosticsByFile.keySet() ) {
            if( !diagnosticsByFile.containsKey(key) )
                result.add(new PublishDiagnosticsParams(key.toString(), []))
        }
        prevDiagnosticsByFile = diagnosticsByFile

        return result
    }

    /**
     * Update the AST cache for all compiled source files.
     */
    private void updateAst() {
        astCache.update(compileCache)
    }

    /**
     * Update the AST cache for a set of compiled source files.
     *
     * @param uris
     */
    private void updateAst(Set<URI> uris) {
        astCache.update(compileCache, uris)
    }

}

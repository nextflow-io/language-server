package nextflow.lsp

import java.nio.file.Path
import java.util.concurrent.CompletableFuture

import groovy.transform.CompileStatic
import nextflow.lsp.util.Logger
import nextflow.lsp.services.ConfigServices
import nextflow.lsp.services.ScriptServices
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
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

    private Path workspaceRoot

    private ConfigServices configServices = new ConfigServices()
    private ScriptServices scriptServices = new ScriptServices()

    NextflowServices() {
    }

    void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot
        configServices.setWorkspaceRoot(workspaceRoot)
        scriptServices.setWorkspaceRoot(workspaceRoot)
    }

    @Override
    void connect(LanguageClient client) {
        configServices.connect(client)
        scriptServices.connect(client)
    }

    // -- NOTIFICATIONS

    @Override
    void didOpen(DidOpenTextDocumentParams params) {
        final filename = relativePath(params.getTextDocument().getUri())
        log.debug "text/didOpen ${filename}"

        if( filename.endsWith('.config') )
            configServices.didOpen(params)
        else // if( filename.endsWith('.nf') )
            scriptServices.didOpen(params)
    }

    @Override
    void didChange(DidChangeTextDocumentParams params) {
        final filename = relativePath(params.getTextDocument().getUri())
        log.debug "text/didChange ${filename}"

        if( filename.endsWith('.config') )
            configServices.didChange(params)
        else // if( filename.endsWith('.nf') )
            scriptServices.didChange(params)
    }

    @Override
    void didClose(DidCloseTextDocumentParams params) {
        final filename = relativePath(params.getTextDocument().getUri())
        log.debug "text/didClose ${filename}"

        if( filename.endsWith('.config') )
            configServices.didClose(params)
        else // if( filename.endsWith('.nf') )
            scriptServices.didClose(params)
    }

    @Override
    void didSave(DidSaveTextDocumentParams params) {
        final filename = relativePath(params.getTextDocument().getUri())
        log.debug "text/didSave ${filename}"

        if( filename.endsWith('.config') )
            configServices.didSave(params)
        else // if( filename.endsWith('.nf') )
            scriptServices.didSave(params)
    }

    @Override
    void didChangeConfiguration(DidChangeConfigurationParams params) {
        log.debug "workspace/didChangeConfiguration ${params.getSettings()}"

        configServices.didChangeConfiguration(params)
        scriptServices.didChangeConfiguration(params)
    }

    @Override
    void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        log.debug "workspace/didChangeWatchedFiles"

        configServices.didChangeWatchedFiles(params)
        scriptServices.didChangeWatchedFiles(params)
    }

    @Override
    void didRenameFiles(RenameFilesParams params) {
        log.debug "workspace/didRenameFiles"

        configServices.didRenameFiles(params)
        scriptServices.didRenameFiles(params)
    }

    // --- REQUESTS

    @Override
    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        final filename = relativePath(params.getTextDocument().getUri())
        if( filename.endsWith('.config') )
            return configServices.completion(params)
        else // if( filename.endsWith('.nf') )
            return scriptServices.completion(params)
    }

    @Override
    CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        final filename = relativePath(params.getTextDocument().getUri())
        if( filename.endsWith('.config') )
            return configServices.documentSymbol(params)
        else // if( filename.endsWith('.nf') )
            return scriptServices.documentSymbol(params)
    }

    @Override
    CompletableFuture<Hover> hover(HoverParams params) {
        final filename = relativePath(params.getTextDocument().getUri())
        if( filename.endsWith('.config') )
            return configServices.hover(params)
        else // if( filename.endsWith('.nf') )
            return scriptServices.hover(params)
    }

    private String relativePath(String uri) {
        workspaceRoot.relativize(Path.of(uri.replace('file://', ''))).toString()
    }

}

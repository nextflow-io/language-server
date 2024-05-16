package nextflow.lsp

import groovy.transform.CompileStatic
import nextflow.lsp.util.Logger
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService

import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

@CompileStatic
class NextflowLanguageServer implements LanguageServer, LanguageClientAware {

    static void main(String[] args) {
        final server = new NextflowLanguageServer()
        final launcher = Launcher.createLauncher(server, LanguageClient.class, System.in, System.out)
        server.connect(launcher.getRemoteProxy())
        launcher.startListening()
    }

    private NextflowServices nextflowServices = new NextflowServices()

    @Override
    CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        final rootUriString = params.getRootUri()
        if( rootUriString != null ) {
            final uri = URI.create(params.getRootUri())
            final workspaceRoot = Paths.get(uri)
            nextflowServices.setWorkspaceRoot(workspaceRoot)
        }

        final completionOptions = new CompletionOptions(false, List.of('.'))
        final serverCapabilities = new ServerCapabilities()
        serverCapabilities.setCompletionProvider(completionOptions)
        serverCapabilities.setDocumentSymbolProvider(true)
        serverCapabilities.setHoverProvider(true)
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)
        serverCapabilities.setWorkspaceSymbolProvider(true)

        final initializeResult = new InitializeResult(serverCapabilities)
        return CompletableFuture.completedFuture(initializeResult)
    }

    @Override
    CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(new Object())
    }

    @Override
    void exit() {
        System.exit(0)
    }

    @Override
    TextDocumentService getTextDocumentService() {
        return nextflowServices
    }

    @Override
    WorkspaceService getWorkspaceService() {
        return nextflowServices
    }

    @Override
    void connect(LanguageClient client) {
        Logger.instance.initialize(client)
        nextflowServices.connect(client)
    }
}

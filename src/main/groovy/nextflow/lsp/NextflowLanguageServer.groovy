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
package nextflow.lsp

import java.nio.file.Path
import java.util.concurrent.CompletableFuture

import com.google.gson.JsonObject
import groovy.transform.CompileStatic
import nextflow.lsp.util.Logger
import nextflow.lsp.services.CustomFormattingOptions
import nextflow.lsp.services.config.ConfigService
import nextflow.lsp.services.script.ScriptService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CreateFilesParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DeleteFilesParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SetTraceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class NextflowLanguageServer implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {

    static void main(String[] args) {
        final server = new NextflowLanguageServer()
        final launcher = Launcher.createLauncher(server, LanguageClient.class, System.in, System.out)
        server.connect(launcher.getRemoteProxy())
        launcher.startListening()
    }

    private static Logger log = Logger.instance

    private ConfigService configService = new ConfigService()
    private ScriptService scriptService = new ScriptService()

    private Path workspaceRoot

    private boolean harshilAlignment

    // -- LanguageServer

    @Override
    CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        final root = params.getRootUri()
        if( root != null ) {
            this.workspaceRoot = Path.of(URI.create(root))
            configService.initialize(workspaceRoot)
            scriptService.initialize(workspaceRoot)
        }

        final serverCapabilities = new ServerCapabilities()
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)
        final completionOptions = new CompletionOptions(false, List.of('.'))
        serverCapabilities.setCompletionProvider(completionOptions)
        serverCapabilities.setDefinitionProvider(true)
        serverCapabilities.setDocumentFormattingProvider(true)
        serverCapabilities.setDocumentSymbolProvider(true)
        serverCapabilities.setHoverProvider(true)
        serverCapabilities.setReferencesProvider(true)
        serverCapabilities.setWorkspaceSymbolProvider(true)

        final initializeResult = new InitializeResult(serverCapabilities)
        return CompletableFuture.completedFuture(initializeResult)
    }

    @Override
    void setTrace(SetTraceParams params) {
        System.err.println "trace ${params.value}"
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
        return this
    }

    @Override
    WorkspaceService getWorkspaceService() {
        return this
    }

    // -- LanguageClientAware

    @Override
    void connect(LanguageClient client) {
        log.initialize(client)
        configService.connect(client)
        scriptService.connect(client)
    }

    // -- TextDocumentService

    @Override
    void didOpen(DidOpenTextDocumentParams params) {
        final uri = params.getTextDocument().getUri()
        log.debug "textDocument/didOpen ${relativePath(uri)}"

        if( configService.matchesFile(uri) )
            configService.didOpen(params)
        if( scriptService.matchesFile(uri) )
            scriptService.didOpen(params)
    }

    @Override
    void didChange(DidChangeTextDocumentParams params) {
        final uri = params.getTextDocument().getUri()
        log.debug "textDocument/didChange ${relativePath(uri)}"

        if( configService.matchesFile(uri) )
            configService.didChange(params)
        if( scriptService.matchesFile(uri) )
            scriptService.didChange(params)
    }

    @Override
    void didClose(DidCloseTextDocumentParams params) {
        final uri = params.getTextDocument().getUri()
        log.debug "textDocument/didClose ${relativePath(uri)}"

        if( configService.matchesFile(uri) )
            configService.didClose(params)
        if( scriptService.matchesFile(uri) )
            scriptService.didClose(params)
    }

    @Override
    void didSave(DidSaveTextDocumentParams params) {
        final uri = params.getTextDocument().getUri()
        log.debug "textDocument/didSave ${relativePath(uri)}"
    }

    @Override
    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled()
            final uri = params.getTextDocument().getUri()
            if( configService.matchesFile(uri) )
                return configService.completion(params)
            if( scriptService.matchesFile(uri) )
                return scriptService.completion(params)
            log.debug("File was not matched by any language service: ${uri}")
        })
    }

    @Override
    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled()
            final uri = params.getTextDocument().getUri()
            final position = params.getPosition()
            log.debug "textDocument/definition ${uri} [ ${position.getLine()}, ${position.getCharacter()} ]"
            if( configService.matchesFile(uri) )
                return configService.definition(params)
            if( scriptService.matchesFile(uri) )
                return scriptService.definition(params)
            log.debug("File was not matched by any language service: ${uri}")
        })
    }

    @Override
    CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled()
            final uri = params.getTextDocument().getUri()
            log.debug "textDocument/symbol ${uri}"
            if( configService.matchesFile(uri) )
                return configService.documentSymbol(params)
            if( scriptService.matchesFile(uri) )
                return scriptService.documentSymbol(params)
            log.debug("File was not matched by any language service: ${uri}")
        })
    }

    @Override
    CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled()
            final uri = params.getTextDocument().getUri()
            final options = new CustomFormattingOptions(
                tabSize: params.getOptions().getTabSize(),
                insertSpaces: params.getOptions().isInsertSpaces(),
                harshilAlignment: harshilAlignment
            )
            log.debug "textDocument/formatting ${uri} ${options.insertSpaces ? 'spaces' : 'tabs'} ${options.tabSize}"
            if( configService.matchesFile(uri) )
                return configService.formatting(URI.create(uri), options)
            if( scriptService.matchesFile(uri) )
                return scriptService.formatting(URI.create(uri), options)
            log.debug("File was not matched by any language service: ${uri}")
        })
    }

    @Override
    CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled()
            final uri = params.getTextDocument().getUri()
            if( configService.matchesFile(uri) )
                return configService.hover(params)
            if( scriptService.matchesFile(uri) )
                return scriptService.hover(params)
            log.debug("File was not matched by any language service: ${uri}")
        })
    }

    @Override
    CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled()
            final uri = params.getTextDocument().getUri()
            final position = params.getPosition()
            log.debug "textDocument/references ${uri} [ ${position.getLine()}, ${position.getCharacter()} ]"
            if( configService.matchesFile(uri) )
                return configService.references(params)
            if( scriptService.matchesFile(uri) )
                return scriptService.references(params)
            log.debug("File was not matched by any language service: ${uri}")
        })
    }

    // --- WorkspaceService

    @Override
    void didChangeConfiguration(DidChangeConfigurationParams params) {
        log.debug "workspace/didChangeConfiguration ${params.getSettings()}"

        final debug = getJsonBoolean(params.getSettings(), 'nextflow.debug')
        if( debug != null )
            Logger.setDebugEnabled(debug)

        final harshilAlignment = getJsonBoolean(params.getSettings(), 'nextflow.harshilAlignment')
        if( harshilAlignment != null )
            this.harshilAlignment = harshilAlignment
    }

    private Boolean getJsonBoolean(Object json, String path) {
        if( json !instanceof JsonObject )
            return null

        JsonObject object = (JsonObject) json
        final names = path.tokenize('.')
        final scopes = names[0..<-1]
        for( final scope : scopes ) {
            if( !object.has(scope) || !object.get(scope).isJsonObject() )
                return null
            object = object.get(scope).getAsJsonObject()
        }

        final property = names.last()
        if( !object.has(property) || !object.get(property).isJsonPrimitive() )
            return null

        final result = object.get(property).getAsJsonPrimitive()
        if( !result.isBoolean() )
            return null
        return result.getAsBoolean()
    }

    @Override
    void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        log.debug "workspace/didChangeWatchedFiles ${params}"
    }

    @Override
    void didCreateFiles(CreateFilesParams params) {
        log.debug "workspace/didCreateFiles ${params}"
    }

    @Override
    void didDeleteFiles(DeleteFilesParams params) {
        log.debug "workspace/didDeleteFiles ${params}"
    }

    @Override
    void didRenameFiles(RenameFilesParams params) {
        log.debug "workspace/didRenameFiles ${params}"
    }

    @Override
    CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled()
            log.debug "workspace/symbol ${params}"
            return scriptService.symbol(params)
        })
    }

    // -- INTERNAL

    private String relativePath(String uri) {
        uri.replace("file://${workspaceRoot ?: ''}", '')
    }

}

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
package nextflow.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import nextflow.lsp.engine.DefaultEngineEnvironment;
import nextflow.lsp.engine.Engine;
import nextflow.lsp.engine.EngineEnvironment;
import nextflow.lsp.engine.EngineState;
import nextflow.lsp.engine.Event;
import nextflow.lsp.engine.FileKind;
import nextflow.lsp.engine.Need;
import nextflow.lsp.engine.RequestBody;
import nextflow.lsp.engine.RequestSpec;
import nextflow.lsp.engine.Uris;
import nextflow.lsp.engine.Workspace;
import nextflow.lsp.services.SemanticTokensVisitor;
import nextflow.lsp.services.config.ConfigCompletionProvider;
import nextflow.lsp.services.config.ConfigFormattingProvider;
import nextflow.lsp.services.config.ConfigHoverProvider;
import nextflow.lsp.services.config.ConfigLinkProvider;
import nextflow.lsp.services.config.ConfigSemanticTokensProvider;
import nextflow.lsp.services.script.ConfigPreviewProvider;
import nextflow.lsp.services.script.ScriptCallHierarchyProvider;
import nextflow.lsp.services.script.ScriptCodeActionProvider;
import nextflow.lsp.services.script.ScriptCodeLensProvider;
import nextflow.lsp.services.script.ScriptCompletionProvider;
import nextflow.lsp.services.script.ScriptDefinitionProvider;
import nextflow.lsp.services.script.ScriptFormattingProvider;
import nextflow.lsp.services.script.ScriptHoverProvider;
import nextflow.lsp.services.script.ScriptLinkProvider;
import nextflow.lsp.services.script.ScriptReferenceProvider;
import nextflow.lsp.services.script.ScriptSemanticTokensProvider;
import nextflow.lsp.services.script.ScriptSymbolProvider;
import nextflow.lsp.services.script.WorkspacePreviewProvider;
import nextflow.lsp.util.JsonUtils;
import nextflow.lsp.util.Logger;
import nextflow.script.formatter.FormattingOptions;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CreateFilesParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DeleteFilesParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkOptions;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameFilesParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Adapter from lsp4j to the language service engine.
 *
 * Every notification is submitted to the engine as an event, and
 * every request as a request with its freshness need, so that the
 * lsp4j reader thread never blocks. The engine is created when the
 * client is connected.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class NextflowLanguageServer implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {

    public static void main(String[] args) {
        var server = new NextflowLanguageServer();
        var launcher = Launcher.createLauncher(server, LanguageClient.class, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening();
    }

    private static final String PREVIEW_CONFIG = "nextflow.server.previewConfig";

    private static final String PREVIEW_DAG = "nextflow.server.previewDag";

    private static final String PREVIEW_WORKSPACE = "nextflow.server.previewWorkspace";

    private static Logger log = Logger.getInstance();

    private final EngineEnvironment environment;

    private final Duration debounce;

    private final EngineState.Factory stateFactory;

    private volatile Engine engine;

    private volatile boolean shutdownReceived;

    public NextflowLanguageServer() {
        this(new DefaultEngineEnvironment(), Engine.DEFAULT_DEBOUNCE, Workspace.factory());
    }

    /**
     * Create a server with the given environment, debounce delay
     * and engine state, for testing.
     *
     * @param environment
     * @param debounce
     * @param stateFactory
     */
    NextflowLanguageServer(EngineEnvironment environment, Duration debounce, EngineState.Factory stateFactory) {
        this.environment = environment;
        this.debounce = debounce;
        this.stateFactory = stateFactory;
    }

    /**
     * The engine, once the client is connected.
     */
    Engine engine() {
        var result = engine;
        if( result == null )
            throw new IllegalStateException("Language server is not connected to a client");
        return result;
    }

    // -- LanguageServer

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        engine().submit(new Event.Init(params));
        return CompletableFuture.completedFuture(new InitializeResult(serverCapabilities()));
    }

    private static ServerCapabilities serverCapabilities() {
        var result = new ServerCapabilities();
        result.setTextDocumentSync(TextDocumentSyncKind.Incremental);

        var workspaceFoldersOptions = new WorkspaceFoldersOptions();
        workspaceFoldersOptions.setSupported(true);
        workspaceFoldersOptions.setChangeNotifications(true);
        var workspaceCapabilities = new WorkspaceServerCapabilities(workspaceFoldersOptions);
        result.setWorkspace(workspaceCapabilities);

        result.setCallHierarchyProvider(true);
        result.setCodeActionProvider(true);
        var codeLensOptions = new CodeLensOptions(false);
        result.setCodeLensProvider(codeLensOptions);
        var completionOptions = new CompletionOptions(false, List.of("."));
        result.setCompletionProvider(completionOptions);
        result.setDefinitionProvider(true);
        result.setDocumentFormattingProvider(true);
        var documentLinkOptions = new DocumentLinkOptions(false);
        result.setDocumentLinkProvider(documentLinkOptions);
        result.setDocumentSymbolProvider(true);
        var commands = List.of(
            PREVIEW_CONFIG,
            PREVIEW_DAG,
            PREVIEW_WORKSPACE
        );
        var executeCommandOptions = new ExecuteCommandOptions(commands);
        result.setExecuteCommandProvider(executeCommandOptions);
        result.setHoverProvider(true);
        result.setReferencesProvider(true);
        var semanticTokensOptions = new SemanticTokensWithRegistrationOptions(
            new SemanticTokensLegend(
                SemanticTokensVisitor.TOKEN_TYPES,
                Collections.emptyList()
            ),
            true,
            false);
        result.setSemanticTokensProvider(semanticTokensOptions);
        result.setRenameProvider(true);
        result.setWorkspaceSymbolProvider(true);
        return result;
    }

    @Override
    public void initialized(InitializedParams params) {
        engine().submit(new Event.Initialized(params));
    }

    @Override
    public void setTrace(SetTraceParams params) {
        System.err.println("trace " + params.getValue());
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownReceived = true;
        return engine().shutdown();
    }

    @Override
    public void exit() {
        environment.exiter().exit(shutdownReceived ? 0 : 1);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    // -- LanguageClientAware

    @Override
    public void connect(LanguageClient client) {
        if( engine != null )
            throw new IllegalStateException("Language server is already connected to a client");
        log.initialize(client);
        engine = new Engine(environment, client, debounce, stateFactory);
    }

    // -- TextDocumentService

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        log.debug("textDocument/didOpen " + params.getTextDocument().getUri());
        engine().submit(new Event.DidOpen(params));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        log.debug("textDocument/didChange " + params.getTextDocument().getUri());
        engine().submit(new Event.DidChange(params));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        log.debug("textDocument/didClose " + params.getTextDocument().getUri());
        engine().submit(new Event.DidClose(params));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        log.debug("textDocument/didSave " + params.getTextDocument().getUri());
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/prepareCallHierarchy", textDocument.getUri(), null, (ctx) ->
            ctx.project().scripts()
                .map(ast -> ClientUris.items(new ScriptCallHierarchyProvider(ast).prepare(textDocument, params.getPosition()), ctx::clientUri))
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
        var item = ClientUris.canonical(params.getItem());
        if( !isScript(item.getUri()) )
            return CompletableFuture.completedFuture(Collections.emptyList());
        return request("callHierarchy/incomingCalls", item.getUri(), null, Need.PROJECT, true, null, (ctx) ->
            ctx.project().scripts()
                .map(ast -> ClientUris.incoming(new ScriptCallHierarchyProvider(ast, ctx.cancelChecker()).incomingCalls(item), ctx::clientUri))
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) {
        var item = ClientUris.canonical(params.getItem());
        return fileRequest("callHierarchy/outgoingCalls", item.getUri(), null, (ctx) ->
            ctx.project().scripts()
                .map(ast -> ClientUris.outgoing(new ScriptCallHierarchyProvider(ast).outgoingCalls(item), ctx::clientUri))
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        // the actions depend on the call paths from the entry workflows
        return request("textDocument/codeAction", textDocument.getUri(), null, Need.FILE_AND_DEPENDENTS, false, Collections.emptyList(), (ctx) ->
            ctx.project().scripts()
                .map(ast -> new ScriptCodeActionProvider(ast).codeAction(textDocument, params.getRange()).stream()
                    .peek(action -> ClientUris.command(action.getCommand(), ctx::clientUri))
                    .map(action -> Either.<Command,CodeAction>forRight(action))
                    .toList())
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/codeLens", textDocument.getUri(), Collections.emptyList(), (ctx) ->
            ctx.project().scripts()
                .<List<? extends CodeLens>>map(ast -> ClientUris.codeLenses(new ScriptCodeLensProvider(ast).codeLens(textDocument), ctx::clientUri))
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        var position = params.getPosition();
        return fileRequest("textDocument/completion", textDocument.getUri(), Either.forLeft(Collections.emptyList()), (ctx) -> {
            var configuration = ctx.configuration();
            var project = ctx.project();
            var uri = URI.create(textDocument.getUri());
            var scripts = project.scripts();
            if( scripts.isPresent() ) {
                if( scripts.get().isCommentPosition(uri, position) )
                    return Either.forLeft(Collections.emptyList());
                return new ScriptCompletionProvider(scripts.get(), configuration.maxCompletionItems(), configuration.extendedCompletion()).completion(textDocument, position);
            }
            var configs = project.configView();
            if( configs.isCommentPosition(uri, position) )
                return Either.forLeft(Collections.emptyList());
            return new ConfigCompletionProvider(configs, configuration.maxCompletionItems()).completion(textDocument, position);
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/definition", textDocument.getUri(), Either.forLeft(Collections.emptyList()), (ctx) ->
            ctx.project().scripts()
                .map(ast -> ClientUris.definition(new ScriptDefinitionProvider(ast).definition(textDocument, params.getPosition()), ctx::clientUri))
                .orElse(Either.forLeft(Collections.emptyList())));
    }

    @Override
    public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/documentLink", textDocument.getUri(), Collections.emptyList(), (ctx) -> {
            var project = ctx.project();
            return project.scripts()
                .map(ast -> new ScriptLinkProvider(ast).documentLink(textDocument))
                .orElseGet(() -> new ConfigLinkProvider(project.configView()).documentLink(textDocument));
        });
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/documentSymbol", textDocument.getUri(), Collections.emptyList(), (ctx) ->
            ctx.project().scripts()
                .map(ast -> new ScriptSymbolProvider(ast).documentSymbol(textDocument))
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/formatting", textDocument.getUri(), Collections.emptyList(), (ctx) -> {
            var configuration = ctx.configuration();
            var options = new FormattingOptions(
                params.getOptions().getTabSize(),
                params.getOptions().isInsertSpaces(),
                configuration.harshilAlignment(),
                configuration.maheshForm(),
                configuration.sortDeclarations()
            );
            var project = ctx.project();
            var uri = URI.create(textDocument.getUri());
            return project.scripts()
                .<List<? extends TextEdit>>map(ast -> new ScriptFormattingProvider(ast).formatting(uri, options))
                .orElseGet(() -> new ConfigFormattingProvider(project.configView()).formatting(uri, options));
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/hover", textDocument.getUri(), null, (ctx) -> {
            var project = ctx.project();
            return project.scripts()
                .map(ast -> new ScriptHoverProvider(ast).hover(textDocument, params.getPosition()))
                .orElseGet(() -> new ConfigHoverProvider(project.configView()).hover(textDocument, params.getPosition()));
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        if( !isScript(textDocument.getUri()) )
            return CompletableFuture.completedFuture(Collections.emptyList());
        return request("textDocument/references", textDocument.getUri(), null, Need.PROJECT, true, Collections.emptyList(), (ctx) ->
            ctx.project().scripts()
                .<List<? extends Location>>map(ast -> ClientUris.locations(new ScriptReferenceProvider(ast, ctx.cancelChecker()).references(textDocument, params.getPosition(), params.getContext().isIncludeDeclaration()), ctx::clientUri))
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        if( !isScript(textDocument.getUri()) )
            return CompletableFuture.completedFuture(null);
        return request("textDocument/rename", textDocument.getUri(), null, Need.PROJECT, true, null, (ctx) ->
            ctx.project().scripts()
                .map(ast -> ClientUris.edit(new ScriptReferenceProvider(ast, ctx.cancelChecker()).rename(textDocument, params.getPosition(), params.getNewName()), ctx::clientUri))
                .orElse(null));
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        var textDocument = ClientUris.canonical(params.getTextDocument());
        return fileRequest("textDocument/semanticTokens/full", textDocument.getUri(), null, (ctx) -> {
            var project = ctx.project();
            return project.scripts()
                .map(ast -> new ScriptSemanticTokensProvider(ast).semanticTokensFull(textDocument))
                .orElseGet(() -> new ConfigSemanticTokensProvider(project.configView()).semanticTokensFull(textDocument));
        });
    }

    // -- WorkspaceService

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        log.debug("workspace/didChangeConfiguration " + params.getSettings());
        engine().submit(new Event.ConfigChanged(params));
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        log.debug("workspace/didChangeWatchedFiles " + params);
        engine().submit(new Event.WatchedFiles(params));
    }

    @Override
    public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        log.debug("workspace/didChangeWorkspaceFolders " + params);
        engine().submit(new Event.FoldersChanged(params));
    }

    @Override
    public void didCreateFiles(CreateFilesParams params) {
        log.debug("workspace/didCreateFiles " + params);
    }

    @Override
    public void didDeleteFiles(DeleteFilesParams params) {
        log.debug("workspace/didDeleteFiles " + params);
    }

    @Override
    public void didRenameFiles(RenameFilesParams params) {
        log.debug("workspace/didRenameFiles " + params);
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        var command = params.getCommand();
        var arguments = params.getArguments() != null ? params.getArguments() : List.<Object>of();
        if( PREVIEW_DAG.equals(command) && arguments.size() == 2 ) {
            var uri = ClientUris.canonical(JsonUtils.getString(arguments.get(0)));
            var name = JsonUtils.getString(arguments.get(1));
            return fileRequest(PREVIEW_DAG, uri, null, (ctx) -> {
                var configuration = ctx.configuration();
                return ctx.project().scripts()
                    .map(ast -> new ScriptCodeLensProvider(ast).previewDag(uri, name, configuration.dagDirection(), configuration.dagVerbose()))
                    .orElse(null);
            });
        }
        if( PREVIEW_CONFIG.equals(command) && arguments.size() == 4 ) {
            var uri = ClientUris.canonical(JsonUtils.getString(arguments.get(0)));
            var name = JsonUtils.getString(arguments.get(1));
            var profiles = JsonUtils.getStringArray(arguments.get(2));
            var qualifiedName = JsonUtils.getString(arguments.get(3));
            return request(PREVIEW_CONFIG, uri, null, Need.PROJECT_CONFIGS, false, null, (ctx) -> {
                var project = ctx.project();
                return project.scripts()
                    .map(ast -> new ConfigPreviewProvider(ast, project.configView(), ctx.cancelChecker()).previewConfig(uri, name, profiles, qualifiedName))
                    .orElse(null);
            });
        }
        if( PREVIEW_WORKSPACE.equals(command) && arguments.size() == 1 ) {
            var name = JsonUtils.getString(arguments.get(0));
            if( name == null )
                return CompletableFuture.completedFuture(null);
            return request(PREVIEW_WORKSPACE, null, name, Need.PROJECT, false, null, (ctx) ->
                new WorkspacePreviewProvider(ctx.project().scriptView(), ctx.cancelChecker()).preview());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        var query = params.getQuery();
        return request("workspace/symbol", null, null, Need.ALL, false, Either.forRight(Collections.emptyList()), (ctx) -> {
            // a file included from another folder is in both projects
            var result = new LinkedHashSet<WorkspaceSymbol>();
            for( var project : ctx.allProjects() )
                result.addAll(new ScriptSymbolProvider(project.scriptView(), ctx.cancelChecker()).symbol(query));
            return Either.forRight(ClientUris.symbols(List.copyOf(result), ctx::clientUri));
        });
    }

    // -- INTERNAL

    /**
     * Whether a request URI is a script. Project-wide requests that
     * only apply to scripts don't need to wait for the index otherwise.
     *
     * @param uri
     */
    private static boolean isScript(String uri) {
        return Uris.canonical(uri)
            .flatMap(u -> FileKind.of(Path.of(u)))
            .filter(kind -> kind == FileKind.SCRIPT)
            .isPresent();
    }

    private <T> CompletableFuture<T> fileRequest(String method, String uri, T emptyValue, RequestBody<T> body) {
        return request(method, uri, null, Need.FILE, false, emptyValue, body);
    }

    private <T> CompletableFuture<T> request(String method, String uri, String projectName, Need need, boolean positionBased, T emptyValue, RequestBody<T> body) {
        log.debug(method + " " + (uri != null ? uri : projectName != null ? projectName : ""));
        if( need != Need.ALL && uri == null && projectName == null )
            return CompletableFuture.completedFuture(emptyValue);
        return engine().request(new RequestSpec(method, uri, projectName, need, positionBased), emptyValue, body);
    }

}

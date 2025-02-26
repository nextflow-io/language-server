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
package nextflow.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import nextflow.lsp.file.PathUtils;
import nextflow.lsp.util.Logger;
import nextflow.lsp.services.LanguageService;
import nextflow.lsp.services.SemanticTokensVisitor;
import nextflow.lsp.services.config.ConfigService;
import nextflow.lsp.services.script.ScriptService;
import nextflow.script.formatter.FormattingOptions;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
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
import org.eclipse.lsp4j.RenameFilesParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.ReferenceParams;
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
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
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

    private static final String DEFAULT_WORKSPACE_FOLDER_NAME = "";

    private static Logger log = Logger.getInstance();

    private LanguageClient client = null;

    private Map<String, String> workspaceRoots = new HashMap<>();
    private Map<String, LanguageService> scriptServices = new HashMap<>();
    private Map<String, LanguageService> configServices = new HashMap<>();

    private List<String> excludePatterns;
    private boolean harshilAlignment;
    private boolean maheshForm;
    private boolean paranoidWarnings;

    // -- LanguageServer

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        addWorkspaceFolder(DEFAULT_WORKSPACE_FOLDER_NAME, null);

        var workspaceFolders = params.getWorkspaceFolders();
        if( workspaceFolders != null && !workspaceFolders.isEmpty() ) {
            for( var workspaceFolder : workspaceFolders ) {
                var name = workspaceFolder.getName();
                var uri = workspaceFolder.getUri();
                addWorkspaceFolder(name, uri);
            }
        }

        var serverCapabilities = new ServerCapabilities();
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);

        var workspaceFoldersOptions = new WorkspaceFoldersOptions();
        workspaceFoldersOptions.setSupported(true);
        workspaceFoldersOptions.setChangeNotifications(true);
        var workspaceCapabilities = new WorkspaceServerCapabilities(workspaceFoldersOptions);
        serverCapabilities.setWorkspace(workspaceCapabilities);

        serverCapabilities.setCallHierarchyProvider(true);
        var codeLensOptions = new CodeLensOptions(false);
        serverCapabilities.setCodeLensProvider(codeLensOptions);
        var completionOptions = new CompletionOptions(false, List.of("."));
        serverCapabilities.setCompletionProvider(completionOptions);
        serverCapabilities.setDefinitionProvider(true);
        serverCapabilities.setDocumentFormattingProvider(true);
        var documentLinkOptions = new DocumentLinkOptions(false);
        serverCapabilities.setDocumentLinkProvider(documentLinkOptions);
        serverCapabilities.setDocumentSymbolProvider(true);
        var executeCommandOptions = new ExecuteCommandOptions(List.of("nextflow.server.previewDag"));
        serverCapabilities.setExecuteCommandProvider(executeCommandOptions);
        serverCapabilities.setHoverProvider(true);
        serverCapabilities.setReferencesProvider(true);
        var semanticTokensOptions = new SemanticTokensWithRegistrationOptions(
            new SemanticTokensLegend(
                SemanticTokensVisitor.TOKEN_TYPES,
                Collections.emptyList()
            ),
            true,
            false);
        serverCapabilities.setSemanticTokensProvider(semanticTokensOptions);
        serverCapabilities.setRenameProvider(true);
        serverCapabilities.setWorkspaceSymbolProvider(true);

        var initializeResult = new InitializeResult(serverCapabilities);
        return CompletableFuture.completedFuture(initializeResult);
    }

    @Override
    public void setTrace(SetTraceParams params) {
        System.err.println("trace " + params.getValue());
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(new Object());
    }

    @Override
    public void exit() {
        System.exit(0);
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
        this.client = client;
        log.initialize(client);
    }

    // -- TextDocumentService

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var uri = params.getTextDocument().getUri();
        log.debug("textDocument/didOpen " + relativePath(uri));

        var service = getLanguageService(uri);
        if( service != null )
            service.didOpen(params);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var uri = params.getTextDocument().getUri();
        log.debug("textDocument/didChange " + relativePath(uri));

        var service = getLanguageService(uri);
        if( service != null )
            service.didChange(params);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        var uri = params.getTextDocument().getUri();
        log.debug("textDocument/didClose " + relativePath(uri));

        var service = getLanguageService(uri);
        if( service != null )
            service.didClose(params);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        var uri = params.getTextDocument().getUri();
        log.debug("textDocument/didSave " + relativePath(uri));
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            log.debug("textDocument/prepareCallHierarchy " + relativePath(uri));
            var service = getLanguageService(uri);
            if( service == null )
                return null;
            return service.prepareCallHierarchy(params);
        });
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var item = params.getItem();
            var uri = item.getUri();
            log.debug("textDocument/callHierarchyIncomingCalls " + relativePath(uri));
            var service = getLanguageService(uri);
            if( service == null )
                return null;
            return service.callHierarchyIncomingCalls(item);
        });
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var item = params.getItem();
            var uri = item.getUri();
            log.debug("textDocument/callHierarchyOutgoingCalls " + relativePath(uri));
            var service = getLanguageService(uri);
            if( service == null )
                return null;
            return service.callHierarchyOutgoingCalls(item);
        });
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            log.debug("textDocument/codeLens " + relativePath(uri));
            var service = getLanguageService(uri);
            if( service == null )
                return Collections.emptyList();
            return service.codeLens(params);
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            var position = params.getPosition();
            log.debug(String.format("textDocument/completion %s [ %d, %d ]", relativePath(uri), position.getLine(), position.getCharacter()));
            var service = getLanguageService(uri);
            if( service == null )
                return Either.forLeft(Collections.emptyList());
            return service.completion(params);
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            var position = params.getPosition();
            log.debug(String.format("textDocument/definition %s [ %d, %d ]", relativePath(uri), position.getLine(), position.getCharacter()));
            var service = getLanguageService(uri);
            if( service == null )
                return Either.forLeft(Collections.emptyList());
            return service.definition(params);
        });
    }

    @Override
    public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            log.debug("textDocument/documentLink " + relativePath(uri));
            var service = getLanguageService(uri);
            if( service == null )
                return Collections.emptyList();
            return service.documentLink(params);
        });
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            log.debug("textDocument/symbol " + relativePath(uri));
            var service = getLanguageService(uri);
            if( service == null )
                return Collections.emptyList();
            return service.documentSymbol(params);
        });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            var options = new FormattingOptions(
                params.getOptions().getTabSize(),
                params.getOptions().isInsertSpaces(),
                harshilAlignment,
                maheshForm
            );
            log.debug(String.format("textDocument/formatting %s %s %d", relativePath(uri), options.insertSpaces() ? "spaces" : "tabs", options.tabSize()));
            var service = getLanguageService(uri);
            if( service == null )
                return Collections.emptyList();
            return service.formatting(URI.create(uri), options);
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            var service = getLanguageService(uri);
            if( service == null )
                return null;
            return service.hover(params);
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            var position = params.getPosition();
            log.debug(String.format("textDocument/references %s [ %d, %d ]", relativePath(uri), position.getLine(), position.getCharacter()));
            var service = getLanguageService(uri);
            if( service == null )
                return Collections.emptyList();
            return service.references(params);
        });
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            var position = params.getPosition();
            log.debug(String.format("textDocument/rename %s [ %d, %d ] -> %s", relativePath(uri), position.getLine(), position.getCharacter(), params.getNewName()));
            var service = getLanguageService(uri);
            if( service == null )
                return null;
            return service.rename(params);
        });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var uri = params.getTextDocument().getUri();
            log.debug("textDocument/semanticTokens/full " + relativePath(uri));
            var service = getLanguageService(uri);
            if( service == null )
                return null;
            return service.semanticTokensFull(params);
        });
    }

    // --- WorkspaceService

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        log.debug("workspace/didChangeConfiguration " + params.getSettings());

        var shouldInitialize = false;

        var debug = getJsonBoolean(params.getSettings(), "nextflow.debug");
        if( debug != null )
            Logger.setDebugEnabled(debug);

        var excludePatterns = getJsonStringArray(params.getSettings(), "nextflow.files.exclude");
        if( !DefaultGroovyMethods.equals(this.excludePatterns, excludePatterns) ) {
            this.excludePatterns = excludePatterns;
            shouldInitialize = true;
        }

        var harshilAlignment = getJsonBoolean(params.getSettings(), "nextflow.formatting.harshilAlignment");
        if( harshilAlignment != null )
            this.harshilAlignment = harshilAlignment;

        var maheshForm = getJsonBoolean(params.getSettings(), "nextflow.formatting.maheshForm");
        if( maheshForm != null )
            this.maheshForm = maheshForm;
    
        var paranoidWarnings = getJsonBoolean(params.getSettings(), "nextflow.paranoidWarnings");
        if( paranoidWarnings != null && this.paranoidWarnings != paranoidWarnings ) {
            this.paranoidWarnings = paranoidWarnings;
            shouldInitialize = true;
        }

        if( shouldInitialize ) {
            var progressToken = "initialize";
            progressCreate(progressToken);
            progressBegin(progressToken, "Initializing workspace...");

            var count = 0;
            var total = workspaceRoots.keySet().size() - 1;
            for( var name : workspaceRoots.keySet() ) {
                if( DEFAULT_WORKSPACE_FOLDER_NAME.equals(name) )
                    continue;
                var progressMessage = String.format("Initializing workspace: %s (%d / %d)", name, count + 1, total);
                progressUpdate(progressToken, progressMessage, count * 100 / total);
                count++;

                var uri = workspaceRoots.get(name);
                scriptServices.get(name).initialize(uri, this.excludePatterns, this.paranoidWarnings);
                configServices.get(name).initialize(uri, this.excludePatterns, this.paranoidWarnings);
            }

            progressEnd(progressToken);
        }
    }

    private void progressCreate(String token) {
        client.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token)));
    }

    private void progressBegin(String token, String message) {
        var progress = new WorkDoneProgressBegin();
        progress.setMessage(message);
        progress.setPercentage(0);
        client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progress)));
    }

    private void progressUpdate(String token, String message, int percentage) {
        var progress = new WorkDoneProgressReport();
        progress.setMessage(message);
        progress.setPercentage(percentage);
        client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progress)));
    }

    private void progressEnd(String token) {
        var progress = new WorkDoneProgressEnd();
        client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progress)));
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        log.debug("workspace/didChangeWatchedFiles " + params);
    }

    @Override
    public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        var event = params.getEvent();
        for( var workspaceFolder : event.getRemoved() ) {
            var name = workspaceFolder.getName();
            log.debug("workspace/didChangeWorkspaceFolders remove " + name);
            workspaceRoots.remove(name);
            scriptServices.remove(name).clearDiagnostics();
            configServices.remove(name).clearDiagnostics();
        }
        for( var workspaceFolder : event.getAdded() ) {
            var name = workspaceFolder.getName();
            var uri = workspaceFolder.getUri();
            log.debug("workspace/didChangeWorkspaceFolders add " + name + " " + uri);
            addWorkspaceFolder(name, uri);
            scriptServices.get(name).initialize(uri, excludePatterns, paranoidWarnings);
            configServices.get(name).initialize(uri, excludePatterns, paranoidWarnings);
        }
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
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            var command = params.getCommand();
            var arguments = params.getArguments();
            if( !"nextflow.server.previewDag".equals(command) || arguments.size() != 2 )
                return null;
            var uri = getJsonString(arguments.get(0));
            log.debug(String.format("textDocument/executeCommand %s %s", command, arguments.toString()));
            var service = getLanguageService(uri);
            if( service == null )
                return null;
            return service.executeCommand(command, arguments);
        });
    }

    private String getJsonString(Object json) {
        return json instanceof JsonPrimitive jp ? jp.getAsString() : null;
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            cancelChecker.checkCanceled();
            log.debug("workspace/symbol " + params.getQuery());
            var result = new ArrayList<WorkspaceSymbol>();
            for( var scriptService : scriptServices.values() )
                result.addAll(scriptService.symbol(params));
            return Either.forRight(result);
        });
    }

    // -- INTERNAL

    private void addWorkspaceFolder(String name, String uri) {
        workspaceRoots.put(name, uri);

        var scriptService = new ScriptService();
        scriptService.connect(client);
        scriptServices.put(name, scriptService);

        var configService = new ConfigService();
        configService.connect(client);
        configServices.put(name, configService);
    }

    private String relativePath(String uri) {
        return workspaceRoots.entrySet().stream()
            .filter((entry) -> entry.getValue() != null && uri.startsWith(entry.getValue()))
            .findFirst()
            .map((entry) -> {
                var name = entry.getKey();
                var root = entry.getValue();
                var prefix = (DEFAULT_WORKSPACE_FOLDER_NAME.equals(name)) ? "" : name + ":";
                return uri.replace(root, prefix);
            })
            .orElse(uri);
    }

    private LanguageService getLanguageService(String uri) {
        var service = Optional.ofNullable(getLanguageService0(uri, scriptServices)).orElse(getLanguageService0(uri, configServices));
        if( service == null ) {
            log.debug("No language service found for file: " + uri);
            return null;
        }
        var path = Path.of(URI.create(uri));
        if( PathUtils.isPathExcluded(path, excludePatterns) )
            return null;
        return service;
    }

    private LanguageService getLanguageService0(String uri, Map<String, LanguageService> services) {
        var service = workspaceRoots.entrySet().stream()
            .filter((entry) -> entry.getValue() != null && uri.startsWith(entry.getValue()))
            .findFirst()
            .map((entry) -> services.get(entry.getKey()))
            .orElse(services.get(DEFAULT_WORKSPACE_FOLDER_NAME));
        if( service == null || !service.matchesFile(uri) )
            return null;
        return service;
    }

    private List<String> getJsonStringArray(Object json, String path) {
        var value = getJsonElement(json, path);
        if( value == null || !value.isJsonArray() )
            return null;
        var result = new ArrayList<String>();
        for( var el : value.getAsJsonArray() ) {
            try {
                result.add(el.getAsJsonPrimitive().getAsString());
            }
            catch( AssertionError e ) {
                continue;
            }
        }
        return result;
    }

    private Boolean getJsonBoolean(Object json, String path) {
        var value = getJsonElement(json, path);
        if( value == null || !value.isJsonPrimitive() )
            return null;
        var result = value.getAsJsonPrimitive();
        if( !result.isBoolean() )
            return null;
        return result.getAsBoolean();
    }

    private JsonElement getJsonElement(Object json, String path) {
        if( !(json instanceof JsonObject) )
            return null;

        JsonObject object = (JsonObject) json;
        var names = path.split("\\.");
        for( int i = 0; i < names.length - 1; i++ ) {
            var scope = names[i];
            if( !object.has(scope) || !object.get(scope).isJsonObject() )
                return null;
            object = object.get(scope).getAsJsonObject();
        }

        var property = names[names.length - 1];
        if( !object.has(property) )
            return null;
        return object.get(property);
    }

}

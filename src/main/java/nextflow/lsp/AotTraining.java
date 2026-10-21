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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Training workload for the AOT cache (e.g. when launched with
 * -XX:AOTCacheOutput=FILE), run by the bundle launcher.
 *
 * Runs the server and a client in the same JVM, connected through
 * pipes, so that the JSON-RPC layer is trained as well. The client
 * indexes the given workspace, then opens, edits and queries every
 * script and config file. Plugin specs are served by a fake registry
 * on the loopback, so that training does not need the network.
 *
 * Edits are compiled immediately by following each change with a
 * completion request (which calls updateNow()), instead of waiting
 * for the debounce.
 *
 * Usage: AotTraining WORKSPACE [PASSES]
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class AotTraining {

    private static final long TIMEOUT_SECONDS = 300;

    private static final Set<String> EXCLUDE = Set.of(".git", ".nf-test", "work");

    // insert text at the first line, or remove the first line (null)
    private static final String[] EDITS = {
        "// train edit\n",
        null,
        // syntax error, to exercise error recovery and reporting
        "x = (\n",
        null,
    };

    private static final Map<String, List<Pattern>> PATTERNS = Map.of(
        ".nf", patterns("include \\{\\s*(\\w+)", "\\b([A-Z][A-Z0-9_]{3,})\\s*\\(", "\\b(ch_\\w+)\\.", "\\b(params\\.\\w+)", "\\b(meta\\.\\w+)", "\\b(task\\.\\w+)"),
        ".config", patterns("\\b(params\\.\\w+)", "\\b(withName:\\s*\\S+)", "\\b(process)\\s*\\{", "\\b(ext\\.\\w+)", "\\b(task\\.\\w+)")
    );

    private static final Map<String, Pattern> DOT_PATTERNS = Map.of(
        ".nf", Pattern.compile("\\b(?:ch_\\w+|params|meta)\\."),
        ".config", Pattern.compile("\\b(?:params|process|task)\\.")
    );

    // plugin release served for every plugin, with a few definitions of each kind
    private static final String PLUGIN_RELEASE = """
        {"pluginRelease": {"version": "0.0.0", "spec": "{\\"definitions\\": [\
        {\\"type\\": \\"ConfigScope\\", \\"spec\\": {\\"name\\": \\"validation\\", \\"description\\": \\"Validation.\\", \\"children\\": [\
        {\\"type\\": \\"ConfigOption\\", \\"spec\\": {\\"name\\": \\"defaultIgnoreParams\\", \\"type\\": \\"Set\\", \\"description\\": \\"Ignored params.\\"}},\
        {\\"type\\": \\"ConfigOption\\", \\"spec\\": {\\"name\\": \\"monochromeLogs\\", \\"type\\": \\"Boolean\\", \\"description\\": \\"Monochrome logs.\\"}}]}},\
        {\\"type\\": \\"Function\\", \\"spec\\": {\\"name\\": \\"samplesheetToList\\", \\"parameters\\": [{\\"name\\": \\"arg0\\", \\"type\\": \\"Path\\"}, {\\"name\\": \\"arg1\\", \\"type\\": \\"Path\\"}], \\"returnType\\": \\"List\\", \\"description\\": null}},\
        {\\"type\\": \\"Function\\", \\"spec\\": {\\"name\\": \\"validateParameters\\", \\"parameters\\": [], \\"returnType\\": \\"void\\", \\"description\\": null}},\
        {\\"type\\": \\"Function\\", \\"spec\\": {\\"name\\": \\"paramsHelp\\", \\"parameters\\": [{\\"name\\": \\"arg0\\", \\"type\\": \\"String\\"}], \\"returnType\\": \\"String\\", \\"description\\": null}},\
        {\\"type\\": \\"Function\\", \\"spec\\": {\\"name\\": \\"paramsSummaryMap\\", \\"parameters\\": [{\\"name\\": \\"arg0\\", \\"type\\": \\"WorkflowMetadata\\"}], \\"returnType\\": \\"Map\\", \\"description\\": null}},\
        {\\"type\\": \\"Function\\", \\"spec\\": {\\"name\\": \\"paramsSummaryLog\\", \\"parameters\\": [{\\"name\\": \\"arg0\\", \\"type\\": \\"WorkflowMetadata\\"}], \\"returnType\\": \\"String\\", \\"description\\": null}}]}"}}
        """;

    public static void main(String[] args) throws Exception {
        if( args.length < 1 || args.length > 2 ) {
            System.err.println("usage: AotTraining WORKSPACE [PASSES]");
            System.exit(2);
        }
        // e.g. a NoClassDefFoundError for a JDK module missing from the runtime,
        // which is only reported on stderr (by the server or lsp4j)
        var stderr = new ErrorScanner(System.err);
        System.setErr(new PrintStream(stderr, true));

        var workspace = Path.of(args[0]).toRealPath();
        var passes = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int rc;
        try( var registry = serve(PLUGIN_RELEASE.getBytes(StandardCharsets.UTF_8)) ) {
            rc = new AotTraining(workspace, "http://127.0.0.1:" + registry.getLocalPort() + "/api/").run(passes);
        }
        catch( TrainingException e ) {
            System.err.println("FAILED: " + e.getMessage());
            rc = 1;
        }
        System.err.flush();
        if( rc == 0 && stderr.firstError != null ) {
            System.err.println("FAILED: stderr contains an exception: " + stderr.firstError);
            rc = 1;
        }
        // the JVM writes the AOT cache on exit
        System.exit(rc);
    }

    private final Path workspace;

    private final Map<String, Object> settings;

    private final Client client = new Client();

    private LanguageServer server;

    private int version = 1;

    private int failed = 0;

    private AotTraining(Path workspace, String registryUrl) {
        this.workspace = workspace;
        // settings shape as read by NextflowLanguageServer.didChangeConfiguration()
        this.settings = Map.of("nextflow", Map.of(
            "debug", false,
            "errorReportingMode", "warnings",
            "files", Map.of("exclude", EXCLUDE.stream().sorted().toList()),
            "completion", Map.of("extended", false, "maxItems", 100),
            "formatting", Map.of("harshilAlignment", false, "maheshForm", false, "sortDeclarations", false),
            "pluginRegistryUrl", registryUrl
        ));
    }

    private int run(int passes) throws Exception {
        var start = System.nanoTime();
        var files = workspaceFiles();
        if( files.isEmpty() )
            throw new TrainingException("no .nf or .config files in " + workspace);

        connect();
        var rootUri = uri(workspace);
        var name = workspace.getFileName().toString();
        var init = new InitializeParams();
        init.setProcessId((int) ProcessHandle.current().pid());
        init.setRootUri(rootUri);
        init.setWorkspaceFolders(List.of(new WorkspaceFolder(rootUri, name)));
        init.setCapabilities(clientCapabilities());
        await("initialize", server.initialize(init));
        server.initialized(new InitializedParams());
        server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(settings));
        // scans and compiles the entire workspace before returning
        var indexed = await("previewWorkspace", server.getWorkspaceService().executeCommand(
            new ExecuteCommandParams("nextflow.server.previewWorkspace", List.of(name))));
        if( indexed == null )
            throw new TrainingException("previewWorkspace returned an empty result");
        if( client.diagnostics.isEmpty() )
            throw new TrainingException("no diagnostics published during workspace indexing");
        System.err.printf("indexed %d files after %d ms%n", files.size(), millis(start));

        for( int i = 0; i < passes; i++ ) {
            for( int j = 0; j < files.size(); j++ )
                exercise(files.get(j), j == 0);
            for( var query : List.of("FASTQ", "ALIGN", "main", "") )
                request(() -> server.getWorkspaceService().symbol(new WorkspaceSymbolParams(query)));
            System.err.printf("pass %d done after %d ms%n", i + 1, millis(start));
        }

        await("shutdown", server.shutdown());
        System.err.printf("done after %d ms (%d requests without result)%n", millis(start), failed);
        if( !client.errors.isEmpty() )
            throw new TrainingException(String.format("server reported %d error(s), first: %s", client.errors.size(), client.errors.get(0)));
        return 0;
    }

    private void connect() throws IOException {
        var toServer = Pipe.open();
        var toClient = Pipe.open();
        var languageServer = new NextflowLanguageServer();
        var serverLauncher = Launcher.createLauncher(
            languageServer, LanguageClient.class,
            Channels.newInputStream(toServer.source()), Channels.newOutputStream(toClient.sink()));
        var clientLauncher = Launcher.createLauncher(
            client, LanguageServer.class,
            Channels.newInputStream(toClient.source()), Channels.newOutputStream(toServer.sink()));
        languageServer.connect(serverLauncher.getRemoteProxy());
        server = clientLauncher.getRemoteProxy();
        serverLauncher.startListening();
        clientLauncher.startListening();
    }

    private void exercise(Path path, boolean debounce) throws Exception {
        var text = Files.readString(path);
        var uri = uri(path);
        var doc = new TextDocumentIdentifier(uri);
        var documents = server.getTextDocumentService();

        var open = new DidOpenTextDocumentParams(new TextDocumentItem(uri, "nextflow", version, text));
        if( debounce ) {
            var count = client.diagnosticsCount(uri);
            documents.didOpen(open);
            client.awaitDiagnostics(uri, count);
        }
        else {
            flush(uri, () -> documents.didOpen(open));
        }

        for( var insert : EDITS ) {
            version++;
            var change = insert != null
                ? new TextDocumentContentChangeEvent(range(0, 0, 0, 0), insert)
                : new TextDocumentContentChangeEvent(range(0, 0, 1, 0), "");
            var params = new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, version), List.of(change));
            flush(uri, () -> documents.didChange(params));
        }

        var suffix = suffix(path);
        var positions = new ArrayList<Position>();
        for( var pattern : PATTERNS.get(suffix) ) {
            var m = pattern.matcher(text);
            for( int k = 0; k < 3 && m.find(); k++ )
                positions.add(position(text, m.start(1) + 2));
        }
        var dotPositions = new ArrayList<Position>();
        var m = DOT_PATTERNS.get(suffix).matcher(text);
        for( int k = 0; k < 3 && m.find(); k++ )
            dotPositions.add(position(text, m.end()));

        request(() -> documents.documentSymbol(new DocumentSymbolParams(doc)));
        request(() -> documents.formatting(new DocumentFormattingParams(doc, new FormattingOptions(4, true))));
        request(() -> documents.semanticTokensFull(new SemanticTokensParams(doc)));
        request(() -> documents.documentLink(new DocumentLinkParams(doc)));
        request(() -> documents.codeLens(new CodeLensParams(doc)));
        for( var p : positions ) {
            request(() -> documents.hover(new HoverParams(doc, p)));
            request(() -> documents.definition(new DefinitionParams(doc, p)));
            request(() -> documents.references(new ReferenceParams(doc, p, new ReferenceContext(true))));
            request(() -> documents.rename(new RenameParams(doc, p, "RENAMED_X")));
            var items = request(() -> documents.prepareCallHierarchy(new CallHierarchyPrepareParams(doc, p)));
            if( items != null && !items.isEmpty() ) {
                request(() -> documents.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items.get(0))));
                request(() -> documents.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items.get(0))));
            }
            request(() -> documents.completion(new CompletionParams(doc, p)));
        }
        for( var p : dotPositions )
            request(() -> documents.completion(new CompletionParams(doc, p)));

        // compiled by the next flush (or the debounce)
        documents.didClose(new DidCloseTextDocumentParams(doc));
        version++;
    }

    /**
     * Send a notification and compile it immediately.
     */
    private void flush(String uri, Runnable notify) throws Exception {
        var count = client.diagnosticsCount(uri);
        notify.run();
        request(() -> server.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(uri), new Position(0, 0))));
        client.awaitDiagnostics(uri, count);
    }

    private <T> T request(Supplier<CompletableFuture<T>> request) throws Exception {
        try {
            return request.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch( ExecutionException e ) {
            // some positions resolve nothing; only the server's work matters here
            failed++;
            return null;
        }
        catch( TimeoutException e ) {
            throw new TrainingException("no response after " + TIMEOUT_SECONDS + "s");
        }
    }

    private static <T> T await(String method, Future<T> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch( ExecutionException e ) {
            throw new TrainingException(method + ": " + e.getCause());
        }
        catch( TimeoutException e ) {
            throw new TrainingException(method + ": no response after " + TIMEOUT_SECONDS + "s");
        }
    }

    private List<Path> workspaceFiles() throws IOException {
        // same files as ScriptService/ConfigService.matchesFile()
        try( var stream = Files.walk(workspace) ) {
            return stream
                .filter(p -> PATTERNS.containsKey(suffix(p)) && !p.getFileName().toString().equals("nf-test.config") && Files.isRegularFile(p))
                .filter(p -> {
                    for( var part : workspace.relativize(p) )
                        if( EXCLUDE.contains(part.toString()) )
                            return false;
                    return true;
                })
                .sorted()
                .toList();
        }
    }

    private static ClientCapabilities clientCapabilities() {
        var text = new TextDocumentClientCapabilities();
        text.setSynchronization(new SynchronizationCapabilities(false, false, true));
        text.setHover(new HoverCapabilities(List.of("markdown", "plaintext"), false));
        text.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(true)));
        text.setDefinition(new DefinitionCapabilities());
        text.setReferences(new ReferencesCapabilities());
        var symbols = new DocumentSymbolCapabilities();
        symbols.setHierarchicalDocumentSymbolSupport(true);
        text.setDocumentSymbol(symbols);
        text.setFormatting(new FormattingCapabilities());
        text.setRename(new RenameCapabilities());
        text.setCallHierarchy(new CallHierarchyCapabilities());
        text.setPublishDiagnostics(new PublishDiagnosticsCapabilities());
        var requests = new SemanticTokensClientCapabilitiesRequests(true);
        text.setSemanticTokens(new SemanticTokensCapabilities(requests, List.of(), List.of(), List.of("relative")));

        var workspace = new WorkspaceClientCapabilities();
        workspace.setWorkspaceFolders(true);
        workspace.setConfiguration(true);
        workspace.setSymbol(new SymbolCapabilities());
        var window = new WindowClientCapabilities();
        window.setWorkDoneProgress(true);
        return new ClientCapabilities(workspace, text, window, null);
    }

    private static String uri(Path path) {
        // Path.toUri() adds a trailing slash to directories
        var uri = path.toUri().toString();
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private static String suffix(Path path) {
        var name = path.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static Position position(String text, int index) {
        int line = 0;
        for( int i = text.indexOf('\n'); i >= 0 && i < index; i = text.indexOf('\n', i + 1) )
            line++;
        var start = text.lastIndexOf('\n', index - 1) + 1;
        return new Position(line, index - start);
    }

    private static Range range(int startLine, int startChar, int endLine, int endChar) {
        return new Range(new Position(startLine, startChar), new Position(endLine, endChar));
    }

    private static List<Pattern> patterns(String... regexes) {
        return Arrays.stream(regexes).map(Pattern::compile).toList();
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * Answers every request with the given body, over HTTP/1.1,
     * one request per connection.
     */
    private static ServerSocket serve(byte[] body) throws IOException {
        var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            while( !server.isClosed() ) {
                try( var socket = server.accept() ) {
                    var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    for( var line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine() ) {}
                    var out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.flush();
                }
                catch( IOException e ) {
                    // closed
                }
            }
        });
        return server;
    }

    /**
     * Client that records diagnostics and errors reported by the server.
     */
    private static class Client implements LanguageClient {

        final Map<String, Integer> diagnostics = new HashMap<>();

        final List<String> errors = new ArrayList<>();

        synchronized int diagnosticsCount(String uri) {
            return diagnostics.getOrDefault(uri, 0);
        }

        /**
         * Wait until more than `count` diagnostics have been published for `uri`.
         */
        synchronized void awaitDiagnostics(String uri, int count) throws InterruptedException, TrainingException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while( diagnostics.getOrDefault(uri, 0) <= count ) {
                var remaining = deadline - System.nanoTime();
                if( remaining <= 0 )
                    throw new TrainingException("no diagnostics published after " + TIMEOUT_SECONDS + "s: " + uri);
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        @Override
        public synchronized void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnostics.merge(params.getUri(), 1, Integer::sum);
            notifyAll();
        }

        @Override
        public void logMessage(MessageParams params) {
            message(params);
        }

        @Override
        public void showMessage(MessageParams params) {
            message(params);
        }

        private synchronized void message(MessageParams params) {
            if( params.getType() == MessageType.Error )
                errors.add(params.getMessage());
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void notifyProgress(ProgressParams params) {
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Forwards output and records the first line that reports an exception.
     */
    private static class ErrorScanner extends OutputStream {

        private static final Pattern ERROR = Pattern.compile("(Exception|Error)\\b");

        private final PrintStream target;

        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        volatile String firstError;

        ErrorScanner(PrintStream target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int b) {
            target.write(b);
            if( b != '\n' ) {
                line.write(b);
                return;
            }
            var text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if( firstError == null && !text.startsWith("FAILED") && ERROR.matcher(text).find() )
                firstError = text;
        }

        @Override
        public synchronized void flush() {
            target.flush();
        }
    }

    private static class TrainingException extends Exception {
        TrainingException(String message) {
            super(message);
        }
    }
}

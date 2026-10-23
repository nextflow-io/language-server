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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import nextflow.lsp.engine.Engine;
import nextflow.lsp.engine.EngineHarness;
import nextflow.lsp.engine.FixtureRegistry;
import nextflow.lsp.engine.PluginRegistryClient;
import nextflow.lsp.engine.QueuedExecutor;
import nextflow.lsp.engine.TestEnvironment;
import nextflow.lsp.engine.Workspace;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;

/**
 * Drives the real language server through its lsp4j interface, with
 * the engine running on the test thread and a fake clock, so that
 * tests control exactly when tasks run and timers fire.
 *
 * Paths are relative to the {@link TestWorkspace} root, so test
 * classes must use `@ExtendWith(TestWorkspace.class)`, and the server
 * must be used from the test thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class TestServer implements AutoCloseable {

    public static final class Options {
        /** Workspace folders, relative to the workspace root. */
        public List<String> folders = List.of("");
        /** Send the first folder as the (deprecated) root URI instead of as a workspace folder. */
        public boolean rootUri;
        public boolean progress;
        public boolean watchers;
        public boolean initialized = true;
        public boolean settle = true;
        /** Settings sent with didChangeConfiguration after initialized, as dotted keys. */
        public Map<String, Object> settings = Map.of();
        public Duration debounce = Engine.DEFAULT_DEBOUNCE;
        public int maxSliceFiles = 64;
        public PluginRegistryClient registry = new FixtureRegistry();
        public TestEnvironment environment;
        public TestLanguageClient client = new TestLanguageClient();

        public Options folders(String... values) {
            folders = List.of(values);
            return this;
        }
    }

    private final Options options;

    private final TestEnvironment env;

    private final NextflowLanguageServer server;

    private final EngineHarness harness;

    private final Map<String, Integer> versions = new HashMap<>();

    private TestServer(Options options) {
        this.options = options;
        var registry = options.registry;
        this.env = options.environment != null
            ? options.environment
            : new TestEnvironment().withRegistry((url) -> registry);
        this.server = new NextflowLanguageServer(env, options.debounce, Workspace.factory(options.maxSliceFiles));
        server.connect(options.client);
        this.harness = EngineHarness.attach(server.engine(), env, options.client);
    }

    /**
     * Start a server with the workspace root as its only folder, and
     * settle it.
     */
    public static TestServer start() {
        return start((options) -> {});
    }

    public static TestServer start(Consumer<Options> configure) {
        var options = new Options();
        configure.accept(options);
        var result = new TestServer(options);
        result.initialize();
        if( options.initialized ) {
            result.server.initialized(new InitializedParams());
            if( !options.settings.isEmpty() )
                result.configure(options.settings);
        }
        if( options.settle )
            result.settle();
        return result;
    }

    @SuppressWarnings("deprecation")
    private void initialize() {
        var params = new InitializeParams();
        var capabilities = new ClientCapabilities();
        var window = new WindowClientCapabilities();
        window.setWorkDoneProgress(options.progress);
        capabilities.setWindow(window);
        var workspace = new WorkspaceClientCapabilities();
        workspace.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities(options.watchers));
        capabilities.setWorkspace(workspace);
        params.setCapabilities(capabilities);
        var folders = new ArrayList<WorkspaceFolder>();
        for( var folder : options.folders )
            folders.add(folder(folder));
        if( options.rootUri && !folders.isEmpty() )
            params.setRootUri(folders.get(0).getUri());
        else
            params.setWorkspaceFolders(folders);
        server.initialize(params).join();
    }

    private WorkspaceFolder folder(String path) {
        var root = path(path);
        try {
            Files.createDirectories(root);
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
        return new WorkspaceFolder(root.toUri().toString(), path.isEmpty() ? "root" : path);
    }

    public NextflowLanguageServer server() {
        return server;
    }

    public TestEnvironment env() {
        return env;
    }

    public TestLanguageClient client() {
        return options.client;
    }

    // -- files

    public Path path(String path) {
        return TestWorkspace.root().resolve(path);
    }

    public String uri(String path) {
        return path(path).toUri().toString();
    }

    public TextDocumentIdentifier document(String path) {
        return new TextDocumentIdentifier(uri(path));
    }

    /**
     * Write a file to disk, without notifying the server.
     */
    public void write(String path, String text) {
        var file = path(path);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.stripIndent());
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Write a file to disk and send the watcher event.
     */
    public void writeAndNotify(String path, String text) {
        var exists = Files.exists(path(path));
        write(path, text);
        notify(path, exists ? FileChangeType.Changed : FileChangeType.Created);
    }

    /**
     * Delete a file from disk and send the watcher event.
     */
    public void delete(String path) {
        try {
            Files.deleteIfExists(path(path));
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
        notify(path, FileChangeType.Deleted);
    }

    public void notify(String path, FileChangeType type) {
        server.didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of(new FileEvent(uri(path), type))));
    }

    // -- documents

    /**
     * Open a document (version 1), with its indentation stripped.
     */
    public void open(String path, String text) {
        openVerbatim(path, text.stripIndent());
    }

    /**
     * Open a document with exactly the given text.
     */
    public void openVerbatim(String path, String text) {
        var uri = uri(path);
        versions.put(uri, 1);
        server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "nextflow", 1, text)));
    }

    /**
     * Replace the whole text of an open document.
     */
    public void change(String path, String text) {
        sendChange(path, new TextDocumentContentChangeEvent(text.stripIndent()));
    }

    public void change(String path, Range range, String text) {
        sendChange(path, new TextDocumentContentChangeEvent(range, text));
    }

    private void sendChange(String path, TextDocumentContentChangeEvent change) {
        var uri = uri(path);
        var version = versions.merge(uri, 1, Integer::sum);
        server.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, version), List.of(change)));
    }

    public void close(String path) {
        var uri = uri(path);
        versions.remove(uri);
        server.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
    }

    // -- workspace

    /**
     * Send a configuration change. Keys are dotted setting names,
     * e.g. "nextflow.errorReportingMode".
     */
    public void configure(Map<String, Object> settings) {
        var json = new JsonObject();
        var gson = new Gson();
        for( var setting : settings.entrySet() ) {
            var names = setting.getKey().split("\\.");
            var object = json;
            for( int i = 0; i < names.length - 1; i++ ) {
                if( !object.has(names[i]) )
                    object.add(names[i], new JsonObject());
                object = object.getAsJsonObject(names[i]);
            }
            object.add(names[names.length - 1], gson.toJsonTree(setting.getValue()));
        }
        server.didChangeConfiguration(new DidChangeConfigurationParams(json));
    }

    public void addFolder(String path) {
        var event = new WorkspaceFoldersChangeEvent(List.of(folder(path)), List.of());
        server.didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(event));
    }

    public void removeFolder(String path) {
        var event = new WorkspaceFoldersChangeEvent(List.of(), List.of(folder(path)));
        server.didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(event));
    }

    // -- requests

    /**
     * Send a request without running the engine.
     */
    public <T> CompletableFuture<T> send(Function<NextflowLanguageServer, CompletableFuture<T>> call) {
        return call.apply(server);
    }

    /**
     * Send a request, run the engine until it completes, and return
     * its result.
     */
    public <T> T call(Function<NextflowLanguageServer, CompletableFuture<T>> call) {
        var future = send(call);
        harness.runUntil(future);
        try {
            return future.join();
        }
        catch( CompletionException e ) {
            if( e.getCause() instanceof RuntimeException re )
                throw re;
            throw e;
        }
    }

    // -- engine

    /**
     * Run tasks and fire timers until the engine is idle.
     */
    public void settle() {
        harness.settle();
    }

    /**
     * Settle the engine while running the IO tasks held by the given
     * executor.
     *
     * @param io
     */
    public void settle(QueuedExecutor io) {
        var executor = env.manualExecutor();
        for( int i = 0; i < 100_000; i++ ) {
            io.runAll();
            executor.runPending();
            if( isIdle() && io.size() == 0 )
                return;
            if( io.size() == 0 && !executor.advanceToNextTimer() )
                throw new IllegalStateException("Engine is not idle, but has no pending work");
        }
        throw new IllegalStateException("Engine did not settle");
    }

    /**
     * Run every task that is due, without moving the clock.
     */
    public int runPending() {
        return harness.runPending();
    }

    /**
     * Move the clock forward, running tasks as they become due.
     */
    public void advance(Duration duration) {
        harness.advance(duration);
    }

    public boolean isIdle() {
        return server.engine().isIdle();
    }

    // -- diagnostics

    /**
     * Get the most recently published diagnostics of a file.
     */
    public List<Diagnostic> diagnostics(String path) {
        return client().getDiagnostics(uri(path));
    }

    /**
     * Get the publishes of a file, in order.
     */
    public List<List<Diagnostic>> publishes(String path) {
        var uri = uri(path);
        synchronized (client().getPublishLog()) {
            return client().getPublishLog().stream()
                .filter(p -> p.getUri().equals(uri))
                .map(p -> p.getDiagnostics())
                .toList();
        }
    }

    @Override
    public void close() {
        harness.close();
    }

}

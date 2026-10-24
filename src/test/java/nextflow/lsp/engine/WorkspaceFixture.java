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
package nextflow.lsp.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;

/**
 * Drives an engine with the real {@link Workspace} on the test
 * thread. Paths are relative to the {@link TestWorkspace} root.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class WorkspaceFixture implements AutoCloseable {

    static final class Options {
        List<String> folders = List.of("");
        boolean progress;
        boolean watchers;
        boolean initialized = true;
        boolean settle = true;
        int maxSliceFiles = Workspace.DEFAULT_MAX_SLICE_FILES;
        Duration debounce = Engine.DEFAULT_DEBOUNCE;
        TestEnvironment environment = new TestEnvironment();
        TestLanguageClient client = new TestLanguageClient();

        Options folders(String... values) {
            folders = List.of(values);
            return this;
        }
    }

    private final EngineHarness harness;

    private Workspace workspace;

    private WorkspaceFixture(Options options) {
        this.harness = EngineHarness.start(
            (engine) -> workspace = (Workspace) Workspace.factory(options.maxSliceFiles).create(engine),
            (o) -> o.environment(options.environment).client(options.client).debounce(options.debounce));
    }

    static WorkspaceFixture start() {
        return start((options) -> {});
    }

    static WorkspaceFixture start(Consumer<Options> configure) {
        var options = new Options();
        configure.accept(options);
        var fixture = new WorkspaceFixture(options);
        fixture.initialize(options);
        if( options.initialized )
            fixture.initialized();
        if( options.settle )
            fixture.settle();
        return fixture;
    }

    private void initialize(Options options) {
        var params = new InitializeParams();
        var capabilities = new ClientCapabilities();
        var window = new WindowClientCapabilities();
        window.setWorkDoneProgress(options.progress);
        capabilities.setWindow(window);
        var workspaceCapabilities = new WorkspaceClientCapabilities();
        workspaceCapabilities.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities(options.watchers));
        capabilities.setWorkspace(workspaceCapabilities);
        params.setCapabilities(capabilities);
        var folders = new ArrayList<WorkspaceFolder>();
        for( var folder : options.folders )
            folders.add(folder(folder));
        params.setWorkspaceFolders(folders);
        engine().submit(new Event.Init(params));
    }

    void initialized() {
        engine().submit(new Event.Initialized(new InitializedParams()));
    }

    private static WorkspaceFolder folder(String path) {
        var root = TestWorkspace.root().resolve(path);
        try {
            Files.createDirectories(root);
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
        return new WorkspaceFolder(root.toUri().toString(), path.isEmpty() ? "root" : path);
    }

    EngineHarness harness() {
        return harness;
    }

    Engine engine() {
        return harness.engine();
    }

    Workspace workspace() {
        return workspace;
    }

    TestEnvironment env() {
        return harness.env();
    }

    TestLanguageClient client() {
        return harness.client();
    }

    // -- files

    String uri(String path) {
        return harness.uri(path);
    }

    Path path(String path) {
        return TestWorkspace.root().resolve(path);
    }

    void write(String path, String text) {
        harness.write(path, text.stripIndent());
    }

    /**
     * Write a file to disk and send the watcher event.
     */
    void writeAndNotify(String path, String text) {
        var exists = Files.exists(path(path));
        write(path, text);
        notify(path, exists ? FileChangeType.Changed : FileChangeType.Created);
    }

    /**
     * Delete a file from disk and send the watcher event.
     */
    void delete(String path) {
        try {
            Files.deleteIfExists(path(path));
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
        notify(path, FileChangeType.Deleted);
    }

    void notify(String path, FileChangeType type) {
        engine().submit(new Event.WatchedFiles(new DidChangeWatchedFilesParams(List.of(new FileEvent(uri(path), type)))));
    }

    // -- documents

    void open(String path, String text) {
        harness.open(path, text);
    }

    void change(String path, String text) {
        harness.change(path, text.stripIndent());
    }

    void close(String path) {
        harness.close(path);
    }

    // -- workspace events

    /**
     * Send a configuration change. Keys are dotted setting names,
     * e.g. "nextflow.errorReportingMode".
     */
    void configure(Map<String, Object> settings) {
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
        engine().submit(new Event.ConfigChanged(new DidChangeConfigurationParams(json)));
    }

    void addFolder(String path) {
        var event = new WorkspaceFoldersChangeEvent(List.of(folder(path)), List.of());
        engine().submit(new Event.FoldersChanged(new DidChangeWorkspaceFoldersParams(event)));
    }

    void removeFolder(String path) {
        var event = new WorkspaceFoldersChangeEvent(List.of(), List.of(folder(path)));
        engine().submit(new Event.FoldersChanged(new DidChangeWorkspaceFoldersParams(event)));
    }

    // -- requests

    <T> CompletableFuture<T> send(Need need, String path, T emptyValue, Function<RequestContext, T> body) {
        return harness.send(new RequestSpec("test/" + need, path != null ? uri(path) : null, null, need, false), emptyValue, body);
    }

    <T> T call(Need need, String path, T emptyValue, Function<RequestContext, T> body) {
        return harness.call(new RequestSpec("test/" + need, path != null ? uri(path) : null, null, need, false), emptyValue, body);
    }

    /**
     * Get the resolved target of the first include of a script.
     *
     * @param path
     */
    URI includeTarget(String path) {
        return call(Need.FILE, path, null, (context) -> {
            var scripts = context.project().scriptView();
            var include = scripts.getIncludeNodes(URI.create(uri(path))).get(0);
            return scripts.getURI(include.entries.get(0).getTarget());
        });
    }

    // -- parses

    /**
     * Get the number of parses of each file so far, summed over the
     * projects that contain it.
     */
    Map<String, Integer> parses() {
        return EngineHarness.parses(workspace);
    }

    /**
     * Get the number of parses of each file since the given counts,
     * omitting the files that were not parsed.
     *
     * @param before
     */
    Map<String, Integer> parsesSince(Map<String, Integer> before) {
        return EngineHarness.parsesSince(workspace, before);
    }

    // -- engine

    void settle() {
        harness.settle();
    }

    int runPending() {
        return harness.runPending();
    }

    void advance(Duration duration) {
        harness.advance(duration);
    }

    /**
     * Settle the engine while running the IO tasks held by the given
     * executor.
     */
    void settle(QueuedExecutor io) {
        var executor = env().manualExecutor();
        for( int i = 0; i < 10_000; i++ ) {
            io.runAll();
            executor.runPending();
            if( engine().isIdle() && io.size() == 0 )
                return;
            if( io.size() == 0 && !executor.advanceToNextTimer() )
                throw new IllegalStateException("Engine is not idle, but has no pending work");
        }
        throw new IllegalStateException("Engine did not settle");
    }

    // -- diagnostics

    List<Diagnostic> diagnostics(String path) {
        return client().getDiagnostics(uri(path));
    }

    List<String> messages(String path) {
        return diagnostics(path).stream().map(Diagnostic::getMessage).toList();
    }

    /**
     * Get the publishes for a file, in order.
     */
    List<List<Diagnostic>> publishes(String path) {
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

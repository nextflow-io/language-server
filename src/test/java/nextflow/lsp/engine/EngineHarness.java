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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import nextflow.lsp.TestLanguageClient;
import nextflow.lsp.TestUtils;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;

/**
 * Drives an engine on the test thread with a fake clock, so that
 * tests control exactly which tasks run and when timers fire.
 *
 * Paths are relative to the {@link TestWorkspace} root.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class EngineHarness {

    /**
     * Upper bound on settle iterations, to turn a livelock into a failure.
     */
    private static final int MAX_STEPS = 100_000;

    private final TestEnvironment env;

    private final TestLanguageClient client;

    private final Engine engine;

    private final Map<String, Integer> versions = new HashMap<>();

    private EngineHarness(TestEnvironment env, TestLanguageClient client, Engine engine) {
        this.env = env;
        this.client = client;
        this.engine = engine;
    }

    /**
     * Drive an engine that was created elsewhere (e.g. by the
     * language server) with the given test environment.
     *
     * @param engine
     * @param env
     * @param client
     */
    public static EngineHarness attach(Engine engine, TestEnvironment env, TestLanguageClient client) {
        return new EngineHarness(env, client, engine);
    }

    public static EngineHarness start(EngineState.Factory stateFactory) {
        return start(stateFactory, new TestEnvironment());
    }

    public static EngineHarness start(EngineState.Factory stateFactory, TestEnvironment env) {
        var client = new TestLanguageClient();
        return new EngineHarness(env, client, new Engine(env, client, Engine.DEFAULT_DEBOUNCE, stateFactory));
    }

    public Engine engine() {
        return engine;
    }

    public TestEnvironment env() {
        return env;
    }

    public TestLanguageClient client() {
        return client;
    }

    // -- workspace files

    public String uri(String path) {
        return TestUtils.getUri(path);
    }

    // -- documents

    public void open(String path, String text) {
        var uri = uri(path);
        versions.put(uri, 1);
        engine.submit(new Event.DidOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(uri, "nextflow", 1, text.stripIndent()))));
    }

    /**
     * Replace the whole text of an open document.
     *
     * @param path
     * @param text
     */
    public void change(String path, String text) {
        sendChange(path, new TextDocumentContentChangeEvent(text));
    }

    public void change(String path, Range range, String text) {
        sendChange(path, new TextDocumentContentChangeEvent(range, text));
    }

    private void sendChange(String path, TextDocumentContentChangeEvent change) {
        var uri = uri(path);
        var version = versions.merge(uri, 1, Integer::sum);
        engine.submit(new Event.DidChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, version),
            List.of(change))));
    }

    public void close(String path) {
        var uri = uri(path);
        versions.remove(uri);
        engine.submit(new Event.DidClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri))));
    }

    // -- requests

    public <T> CompletableFuture<T> send(RequestSpec spec, T emptyValue, Function<RequestContext, T> body) {
        return engine.request(spec, emptyValue, body);
    }

    /**
     * Send a request, run the engine until it completes, and return
     * its result. Fails if the engine goes idle first.
     */
    public <T> T call(RequestSpec spec, T emptyValue, Function<RequestContext, T> body) {
        return join(send(spec, emptyValue, body));
    }

    /**
     * Run the engine until a request completes, and return its
     * result. Fails if the engine goes idle first.
     *
     * @param future
     */
    public <T> T join(CompletableFuture<T> future) {
        runUntil(future);
        try {
            return future.join();
        }
        catch( CompletionException e ) {
            if( e.getCause() instanceof RuntimeException re )
                throw re;
            throw e;
        }
    }

    /**
     * Run tasks and fire timers until the future is done.
     *
     * @param future
     */
    private void runUntil(CompletableFuture<?> future) {
        var executor = env.manualExecutor();
        for( int i = 0; i < MAX_STEPS; i++ ) {
            executor.runPending();
            if( future.isDone() )
                return;
            if( !executor.advanceToNextTimer() )
                throw new IllegalStateException("Engine has no pending work, but the request is not done");
        }
        throw new IllegalStateException("Request did not complete after " + MAX_STEPS + " steps");
    }

    // -- time

    /**
     * Run every task that is due, without moving the clock.
     */
    public void runPending() {
        env.manualExecutor().runPending();
    }

    /**
     * Move the clock forward, running tasks as they become due.
     *
     * @param duration
     */
    public void advance(Duration duration) {
        env.manualExecutor().advance(duration);
    }

    /**
     * Run tasks and fire timers until the engine is idle.
     */
    public void settle() {
        settle(new QueuedExecutor());
    }

    /**
     * Settle the engine while running the IO tasks held by the given
     * executor.
     *
     * @param io
     */
    public void settle(QueuedExecutor io) {
        var executor = env.manualExecutor();
        for( int i = 0; i < MAX_STEPS; i++ ) {
            io.runAll();
            executor.runPending();
            if( engine.isIdle() && io.size() == 0 )
                return;
            if( io.size() == 0 && !executor.advanceToNextTimer() )
                throw new IllegalStateException("Engine is not idle, but has no pending work");
        }
        throw new IllegalStateException("Engine did not settle after " + MAX_STEPS + " steps");
    }

    // -- parses

    /**
     * Get the number of parses of each file so far, summed over the
     * projects that parsed it, including entries that were since
     * removed.
     *
     * @param workspace
     */
    public static Map<String, Integer> parses(Workspace workspace) {
        var result = new TreeMap<String, Integer>();
        workspace.parses.forEach((uri, count) -> result.put(relativize(uri), count));
        return result;
    }

    /**
     * Get the number of projects that hold an entry for each file.
     *
     * @param workspace
     */
    public static Map<String, Integer> entries(Workspace workspace) {
        var result = new TreeMap<String, Integer>();
        for( var project : workspace.projects() ) {
            for( var entry : project.entries() )
                result.merge(relativize(entry.uri), 1, Integer::sum);
        }
        return result;
    }

    private static String relativize(URI uri) {
        return TestWorkspace.root().relativize(Path.of(uri)).toString().replace('\\', '/');
    }

    /**
     * Get the number of parses of each file since the given counts,
     * omitting the files that were not parsed.
     *
     * @param workspace
     * @param before
     */
    public static Map<String, Integer> parsesSince(Workspace workspace, Map<String, Integer> before) {
        var result = new TreeMap<String, Integer>();
        parses(workspace).forEach((path, count) -> {
            var delta = count - before.getOrDefault(path, 0);
            if( delta != 0 )
                result.put(path, delta);
        });
        return result;
    }

}

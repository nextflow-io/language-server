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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Single-threaded owner of all language service state.
 *
 * Events and requests are tasks in one FIFO mailbox (the engine
 * executor), in the order they are submitted. A request runs at its
 * position in that order, after building what it needs, so it
 * observes every earlier event and no later one. Requests that need
 * a whole project are parked until the project is indexed and fresh.
 * Background work runs in short slices between mailbox tasks.
 *
 * Every task contains its own errors: an exception is logged and
 * the engine continues, while a VirtualMachineError terminates the
 * process with status 1 so that the client can restart the server.
 *
 * {@link #submit}, {@link #request}, {@link #shutdown} and
 * {@link #isIdle} can be called from any thread. Everything else
 * must be called on the engine thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class Engine {

    public static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(500);

    /**
     * Age after which a parked request is answered as soon as its
     * projects are indexed, even if background work keeps them stale.
     */
    static final Duration STARVATION_TIMEOUT = Duration.ofSeconds(2);

    private final EngineEnvironment env;

    private final SerialExecutor executor;

    private final LanguageClient client;

    private final Duration debounce;

    private final DocumentStore documents;

    private final EngineState state;

    // updated from any thread, for isIdle()
    private final AtomicInteger queuedTasks = new AtomicInteger();

    private final AtomicInteger ioInFlight = new AtomicInteger();

    // engine-confined (volatile only so that isIdle() can read them)
    private volatile Cancellable background;

    private volatile Cancellable starvationTimer;

    private final List<RequestTask<?>> parked = new ArrayList<>();

    private boolean shutdown;

    public Engine(EngineEnvironment env, LanguageClient client, Duration debounce, EngineState.Factory stateFactory) {
        this.env = env;
        this.executor = env.engineExecutor();
        this.client = client;
        this.debounce = debounce;
        this.documents = new DocumentStore(executor);
        this.state = stateFactory.create(this);
    }

    // -- public API (any thread)

    /**
     * Queue an event.
     *
     * @param event
     */
    public void submit(Event event) {
        enqueue(event.getClass().getSimpleName(), () -> onEvent(event));
    }

    /**
     * Queue a request.
     *
     * The returned future is completed on the engine thread: with the
     * result of the body, with the empty value if the request can't be
     * routed, or exceptionally if the body fails. Cancelling the future
     * before the body starts means the body never runs.
     *
     * @param spec
     * @param emptyValue
     * @param body
     */
    public <T> CompletableFuture<T> request(RequestSpec spec, T emptyValue, RequestBody<T> body) {
        var task = new RequestTask<>(spec, emptyValue, body);
        task.future.whenComplete((result, error) -> {
            if( task.future.isCancelled() )
                enqueue("drop " + spec.method(), () -> dropParked(task));
        });
        if( !enqueue(spec.method(), () -> onRequest(task)) )
            fail(task, ResponseErrorCode.InvalidRequest, "Server is stopped");
        return task.future;
    }

    /**
     * Queue the shutdown: stop background work, fail parked requests
     * with InvalidRequest and shut down the pools. Requests that
     * arrive afterwards fail with InvalidRequest.
     */
    public CompletableFuture<Object> shutdown() {
        var result = new CompletableFuture<Object>();
        var queued = enqueue("shutdown", () -> {
            try {
                onShutdown();
            }
            finally {
                result.complete(null);
            }
        });
        if( !queued )
            result.complete(null);
        return result;
    }

    /**
     * Whether the engine has nothing to do: no queued tasks, no
     * background work or timers, and no IO in flight.
     */
    public boolean isIdle() {
        return queuedTasks.get() == 0
            && ioInFlight.get() == 0
            && background == null
            && starvationTimer == null;
    }

    // -- API for the engine state (engine thread)

    EngineEnvironment environment() {
        return env;
    }

    LanguageClient client() {
        return client;
    }

    Duration debounce() {
        return debounce;
    }

    DocumentStore documents() {
        checkEngineThread();
        return documents;
    }

    /**
     * Throw an IllegalStateException unless called on the engine thread.
     */
    public void checkEngineThread() {
        EngineThread.check(executor);
    }

    /**
     * Schedule background work after the debounce delay.
     */
    public void scheduleBackground() {
        scheduleBackground(debounce);
    }

    /**
     * Schedule background work after the given delay, replacing any
     * pending background work. The delay is ignored while requests
     * are parked, since they need the background work to finish.
     *
     * @param delay
     */
    public void scheduleBackground(Duration delay) {
        checkEngineThread();
        if( shutdown )
            return;
        if( !parked.isEmpty() )
            delay = Duration.ZERO;
        if( background != null )
            background.cancel();
        background = executor.schedule(delay, contained("background", this::runBackground));
    }

    /**
     * Run blocking work on the IO pool and submit the resulting event,
     * if any. An exception is logged and produces no event.
     *
     * @param description
     * @param work
     */
    public void submitIo(String description, Callable<Event> work) {
        ioInFlight.incrementAndGet();
        try {
            env.ioExecutor().execute(() -> {
                try {
                    var event = work.call();
                    if( event != null )
                        submit(event);
                }
                catch( InterruptedException e ) {
                    Thread.currentThread().interrupt();
                }
                catch( VirtualMachineError e ) {
                    fatal(description, e);
                    throw e;
                }
                catch( Throwable e ) {
                    logError("Unexpected error in " + description, e);
                }
                finally {
                    ioInFlight.decrementAndGet();
                }
            });
        }
        catch( RejectedExecutionException e ) {
            ioInFlight.decrementAndGet();
            if( !shutdown )
                logError("Failed to start " + description, e);
        }
    }

    int parkedCount() {
        checkEngineThread();
        return parked.size();
    }

    // -- mailbox

    private boolean enqueue(String description, Runnable action) {
        queuedTasks.incrementAndGet();
        var task = contained(description, action);
        try {
            executor.execute(() -> {
                try {
                    task.run();
                }
                finally {
                    queuedTasks.decrementAndGet();
                }
            });
            return true;
        }
        catch( RejectedExecutionException e ) {
            queuedTasks.decrementAndGet();
            System.err.println("Engine is stopped -- dropping task: " + description);
            return false;
        }
    }

    private Runnable contained(String description, Runnable action) {
        return () -> {
            try {
                action.run();
            }
            catch( VirtualMachineError e ) {
                fatal(description, e);
                throw e;
            }
            catch( Throwable e ) {
                logError("Unexpected error in " + description, e);
            }
        };
    }

    private void fatal(String description, VirtualMachineError e) {
        try {
            logError("Fatal error in " + description + " -- exiting", e);
        }
        catch( Throwable ignored ) {
        }
        env.exiter().exit(1);
    }

    private void log(MessageType type, String message) {
        System.err.println(message);
        if( client != null )
            client.logMessage(new MessageParams(type, message));
    }

    private void logError(String message, Throwable e) {
        var trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        System.err.println(message);
        e.printStackTrace();
        if( client != null )
            client.logMessage(new MessageParams(MessageType.Error, message + "\n" + trace));
    }

    // -- events

    private void onEvent(Event event) {
        if( shutdown )
            return;
        if( event instanceof Event.DidOpen e ) {
            if( documents.open(e.params()).isEmpty() )
                return;
        }
        else if( event instanceof Event.DidChange e ) {
            if( documents.change(e.params()).isEmpty() ) {
                log(MessageType.Warning, "Ignoring change of a document that is not open: " + e.params().getTextDocument().getUri());
                return;
            }
        }
        else if( event instanceof Event.DidClose e ) {
            var uri = Uris.canonical(e.params().getTextDocument().getUri());
            if( uri.isEmpty() || documents.close(uri.get()).isEmpty() )
                return;
        }
        state.apply(event);
    }

    private void onShutdown() {
        if( shutdown )
            return;
        shutdown = true;
        if( background != null ) {
            background.cancel();
            background = null;
        }
        cancelStarvationTimer();
        var pending = new ArrayList<>(parked);
        parked.clear();
        for( var task : pending )
            fail(task, ResponseErrorCode.InvalidRequest, "Server is shutting down");
        try {
            state.shutdown();
        }
        finally {
            env.compilePool().shutdownNow();
            if( env.ioExecutor() instanceof ExecutorService io )
                io.shutdownNow();
        }
    }

    // -- requests

    private void onRequest(RequestTask<?> task) {
        if( task.future.isDone() )
            return;
        if( shutdown ) {
            fail(task, ResponseErrorCode.InvalidRequest, "Server is shutting down");
            return;
        }
        task.arrival = env.clock().instant();
        task.versionAtArrival = documentVersion(task.spec.uri());
        guarded(task, () -> dispatch(task));
    }

    private void dispatch(RequestTask<?> task) {
        var route = state.route(task.spec);
        if( route.isEmpty() ) {
            task.completeEmpty();
            return;
        }
        var r = route.get();
        switch( task.spec.need() ) {
            case NONE -> run(task, r);
            case FILE, PROJECT_CONFIGS -> {
                r.prepare();
                run(task, r);
            }
            case PROJECT, ALL -> {
                if( r.isSatisfied() )
                    run(task, r);
                else
                    park(task);
            }
        }
    }

    private void park(RequestTask<?> task) {
        parked.add(task);
        scheduleBackground(Duration.ZERO);
        if( starvationTimer == null )
            starvationTimer = executor.schedule(STARVATION_TIMEOUT, contained("parked requests", this::onStarvationTimer));
    }

    private void onStarvationTimer() {
        starvationTimer = null;
        resumeParked();
        if( !parked.isEmpty() && !shutdown )
            starvationTimer = executor.schedule(STARVATION_TIMEOUT, contained("parked requests", this::onStarvationTimer));
    }

    private void cancelStarvationTimer() {
        if( starvationTimer != null ) {
            starvationTimer.cancel();
            starvationTimer = null;
        }
    }

    private void dropParked(RequestTask<?> task) {
        parked.remove(task);
        if( parked.isEmpty() )
            cancelStarvationTimer();
    }

    /**
     * Resume every parked request that is now satisfied, in FIFO order.
     */
    private void resumeParked() {
        if( parked.isEmpty() )
            return;
        var now = env.clock().instant();
        var pending = new ArrayList<>(parked);
        parked.clear();
        for( var task : pending ) {
            if( task.future.isDone() )
                continue;
            guarded(task, () -> {
                if( !tryResume(task, now) )
                    parked.add(task);
            });
        }
        if( parked.isEmpty() )
            cancelStarvationTimer();
    }

    private boolean tryResume(RequestTask<?> task, Instant now) {
        var route = state.route(task.spec);
        if( route.isEmpty() ) {
            task.completeEmpty();
            return true;
        }
        var r = route.get();
        var satisfied = r.isSatisfied();
        if( !satisfied ) {
            var starving = Duration.between(task.arrival, now).compareTo(STARVATION_TIMEOUT) >= 0;
            if( !starving || !r.isIndexed() )
                return false;
            r.prepare();
        }
        if( task.spec.positionBased() && !Objects.equals(documentVersion(task.spec.uri()), task.versionAtArrival) ) {
            fail(task, ResponseErrorCode.ContentModified, "Content modified");
            return true;
        }
        run(task, r);
        return true;
    }

    private <T> void run(RequestTask<T> task, EngineState.Route route) {
        if( task.future.isDone() )
            return;
        var context = route.context(cancelChecker(task.future));
        T result;
        try {
            result = task.body.apply(context);
        }
        catch( VirtualMachineError e ) {
            throw e;
        }
        catch( Throwable e ) {
            failExceptionally(task, e);
            return;
        }
        task.future.complete(result);
    }

    /**
     * Run a step of a request, and fail the request if the step throws.
     *
     * @param task
     * @param action
     */
    private void guarded(RequestTask<?> task, Runnable action) {
        try {
            action.run();
        }
        catch( VirtualMachineError e ) {
            throw e;
        }
        catch( Throwable e ) {
            failExceptionally(task, e);
        }
    }

    private void failExceptionally(RequestTask<?> task, Throwable e) {
        if( task.future.isCancelled() )
            return;
        if( !(e instanceof CancellationException) && !(e instanceof ResponseErrorException) )
            logError("Unexpected error in " + task.spec.method(), e);
        task.future.completeExceptionally(e);
    }

    private static void fail(RequestTask<?> task, ResponseErrorCode code, String message) {
        task.future.completeExceptionally(new ResponseErrorException(new ResponseError(code, message, null)));
    }

    private static CancelChecker cancelChecker(CompletableFuture<?> future) {
        return () -> {
            if( future.isCancelled() )
                throw new CancellationException();
        };
    }

    private Integer documentVersion(String uri) {
        return Uris.canonical(uri)
            .map(documents::version)
            .orElse(null);
    }

    // -- background

    private void runBackground() {
        background = null;
        var more = false;
        try {
            more = state.runSlice();
        }
        catch( VirtualMachineError e ) {
            throw e;
        }
        catch( Throwable e ) {
            logError("Unexpected error in background work", e);
        }
        resumeParked();
        if( more )
            scheduleBackground(Duration.ZERO);
    }

    private static final class RequestTask<T> {
        final RequestSpec spec;
        final T emptyValue;
        final RequestBody<T> body;
        final CompletableFuture<T> future = new CompletableFuture<>();
        Instant arrival;
        Integer versionAtArrival;

        RequestTask(RequestSpec spec, T emptyValue, RequestBody<T> body) {
            this.spec = spec;
            this.emptyValue = emptyValue;
            this.body = body;
        }

        void completeEmpty() {
            future.complete(emptyValue);
        }
    }

}

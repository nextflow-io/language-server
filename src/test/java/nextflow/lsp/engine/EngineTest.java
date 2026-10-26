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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.thrownOnAnotherThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Engine ordering, parking, background scheduling, cancellation and
 * error containment, against a scripted state.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class EngineTest {

    private FakeState state;

    private EngineHarness harness;

    private EngineHarness start() {
        return start(new TestEnvironment());
    }

    private EngineHarness start(TestEnvironment env) {
        harness = EngineHarness.start((engine) -> state = new FakeState(engine), env);
        return harness;
    }

    private RequestSpec spec(String method, Need need) {
        return new RequestSpec(method, harness.uri("main.nf"), null, need, false);
    }

    private RequestSpec positionSpec(String method, Need need) {
        return new RequestSpec(method, harness.uri("main.nf"), null, need, true);
    }

    private <T> Function<RequestContext, T> body(String method, T result) {
        return (ctx) -> {
            state.log.add("body " + method);
            return result;
        };
    }

    private CompletableFuture<String> send(String method, Need need) {
        return harness.send(spec(method, need), "empty", body(method, method));
    }

    private static Throwable failure(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        try {
            future.get();
            throw new AssertionError("expected failure");
        }
        catch( ExecutionException e ) {
            return e.getCause();
        }
        catch( InterruptedException e ) {
            throw new AssertionError(e);
        }
    }

    private static int errorCode(CompletableFuture<?> future) {
        var cause = failure(future);
        assertThat(cause).isInstanceOf(ResponseErrorException.class);
        return ((ResponseErrorException) cause).getResponseError().getCode();
    }

    private List<String> errorLogs() {
        return harness.client().getLogMessages().stream()
            .filter(m -> m.getType() == MessageType.Error)
            .map(m -> m.getMessage())
            .toList();
    }

    // -- ordering

    @Test
    void runEventsAndRequestsInSubmissionOrder() {
        var h = start();
        h.open("main.nf", "a");
        var first = send("first", Need.FILE);
        h.change("main.nf", "b");
        var second = send("second", Need.FILE);

        // nothing runs on the submitting thread
        assertThat(state.log).isEmpty();
        assertThat(first).isNotDone();

        h.runPending();
        assertThat(state.log).containsExactly(
            "event DidOpen",
            "route first", "prepare first", "body first",
            "event DidChange",
            "route second", "prepare second", "body second");
        assertThat(first).isCompletedWithValue("first");
        assertThat(second).isCompletedWithValue("second");
    }

    @Test
    void requestObservesEarlierEditsAndNoLaterOnes() {
        var h = start();
        var uri = Uris.canonical(h.uri("main.nf")).get();
        h.open("main.nf", "hello");
        h.change("main.nf", "hello world");
        var result = h.send(spec("read", Need.FILE), null, (ctx) -> h.engine().documents().get(uri).get().text());
        h.change("main.nf", "goodbye");

        h.runPending();
        assertThat(result).isCompletedWithValue("hello world");
        assertThat(h.engine().documents().get(uri).get().text()).isEqualTo("goodbye");
    }

    @Test
    void doNotDeliverEventsForUnknownOrNonFileDocuments() {
        var h = start();
        h.engine().submit(new Event.DidOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem("untitled:Untitled-1", "nextflow", 1, "x"))));
        h.change("ghost.nf", "x");
        h.close("ghost.nf");

        h.runPending();
        assertThat(state.log).isEmpty();
        assertThat(h.client().getLogMessages())
            .anyMatch(m -> m.getType() == MessageType.Warning && m.getMessage().contains("ghost.nf"));
    }

    @Test
    void returnEmptyValueForUnroutableRequest() {
        var h = start();
        state.unroutable.add(h.uri("main.nf"));
        var result = send("hover", Need.FILE);

        h.runPending();
        assertThat(result).isCompletedWithValue("empty");
        assertThat(state.log).containsExactly("route hover");
    }

    // -- parking and background

    @Test
    void parkProjectRequestUntilBackgroundWorkIndexesIt() {
        var h = start();
        state.indexed = false;
        state.pendingSlices = 3;
        var result = send("references", Need.PROJECT);

        h.env().manualExecutor().runNext();
        assertThat(result).isNotDone();
        assertThat(state.log).containsExactly("route references");

        // parking schedules background work without debounce
        h.runPending();
        assertThat(state.slices).isEqualTo(3);
        assertThat(result).isCompletedWithValue("references");
        assertThat(h.engine().isIdle()).isTrue();
    }

    @Test
    void buildAndRunIndexedProjectRequestImmediately() {
        var h = start();
        var result = send("symbol", Need.ALL);

        h.runPending();
        assertThat(result).isCompletedWithValue("symbol");
        assertThat(state.log).containsExactly("route symbol", "prepare symbol", "body symbol");
        assertThat(state.slices).isZero();
    }

    @Test
    void resumeParkedRequestsInArrivalOrder() {
        var h = start();
        state.indexed = false;
        state.pendingSlices = 2;
        var first = send("first", Need.PROJECT);
        var second = send("second", Need.ALL);

        h.runPending();
        assertThat(first).isCompletedWithValue("first");
        assertThat(second).isCompletedWithValue("second");
        assertThat(state.log.indexOf("body first")).isLessThan(state.log.indexOf("body second"));
    }

    @Test
    void yieldToQueuedTasksBetweenBackgroundSlices() {
        var h = start();
        h.open("main.nf", "x");
        h.runPending();
        state.indexed = false;
        state.pendingSlices = 4;
        var parked = send("references", Need.PROJECT);
        h.env().manualExecutor().runNext();

        // a request and an edit arrive while background work is queued
        var hover = send("hover", Need.FILE);
        h.change("main.nf", "y");
        h.runPending();

        // parked requests are re-routed after every slice
        assertThat(state.log).filteredOn(entry -> !entry.startsWith("route")).containsExactly(
            "event DidOpen",
            "slice",
            "prepare hover", "body hover",
            "event DidChange",
            "slice", "slice", "slice",
            "prepare references", "body references");
        assertThat(hover).isCompletedWithValue("hover");
        assertThat(parked).isCompletedWithValue("references");
    }

    @Test
    void debounceBackgroundWorkAfterEdits() {
        var h = start();
        h.open("main.nf", "a");
        h.runPending();
        assertThat(state.slices).isZero();

        // each edit restarts the debounce
        h.advance(Engine.DEFAULT_DEBOUNCE.minusMillis(1));
        h.change("main.nf", "b");
        h.advance(Engine.DEFAULT_DEBOUNCE.minusMillis(1));
        assertThat(state.slices).isZero();

        h.advance(Duration.ofMillis(1));
        assertThat(state.slices).isEqualTo(1);
        assertThat(h.engine().isIdle()).isTrue();
    }

    @Test
    void requestDoesNotWaitForDebounce() {
        var h = start();
        h.open("main.nf", "a");
        var result = send("hover", Need.FILE);

        h.runPending();
        assertThat(result).isCompletedWithValue("hover");
        assertThat(state.slices).isZero();
    }

    @Test
    void ignoreDebounceWhileRequestsAreParked() {
        var h = start();
        h.open("main.nf", "a");
        state.indexed = false;
        state.pendingSlices = 1;
        var result = send("references", Need.PROJECT);
        h.change("main.nf", "b");

        h.runPending();
        assertThat(result).isCompletedWithValue("references");
    }

    @Test
    void failParkedPositionBasedRequestWhenDocumentChanged() {
        var h = start();
        h.open("main.nf", "a");
        h.runPending();
        state.indexed = false;
        state.pendingSlices = 2;
        var result = h.send(positionSpec("rename", Need.PROJECT), null, body("rename", "edit"));
        h.env().manualExecutor().runNext();
        assertThat(result).isNotDone();

        h.change("main.nf", "b");
        h.runPending();
        assertThat(errorCode(result)).isEqualTo(ResponseErrorCode.ContentModified.getValue());
        assertThat(state.log).doesNotContain("body rename");
    }

    @Test
    void answerParkedRequestThatIsNotPositionBasedAfterDocumentChanged() {
        var h = start();
        h.open("main.nf", "a");
        h.runPending();
        state.indexed = false;
        state.pendingSlices = 2;
        var result = send("symbol", Need.ALL);
        h.env().manualExecutor().runNext();

        h.change("main.nf", "b");
        h.runPending();
        assertThat(result).isCompletedWithValue("symbol");
    }

    @Test
    void answerParkedPositionBasedRequestWhenDocumentDidNotChange() {
        var h = start();
        h.open("main.nf", "a");
        h.runPending();
        state.indexed = false;
        state.pendingSlices = 2;
        var result = h.send(positionSpec("references", Need.PROJECT), null, body("references", "refs"));

        h.runPending();
        assertThat(result).isCompletedWithValue("refs");
    }

    @Test
    void resumeParkedRequestAfterEventThatDoesNotScheduleBackgroundWork() {
        var h = start();
        state.indexed = false;
        var result = send("references", Need.PROJECT);
        h.runPending();
        assertThat(result).isNotDone();

        // the fake state doesn't schedule background work for this event
        state.indexed = true;
        h.engine().submit(new Event.WatchedFiles(new DidChangeWatchedFilesParams(List.of())));
        h.runPending();
        assertThat(result).isCompletedWithValue("references");
    }

    @Test
    void resumeParkedRequestAfterEventThatThrows() {
        var h = start();
        state.indexed = false;
        var result = send("references", Need.PROJECT);
        h.runPending();

        state.indexed = true;
        state.applyError = new IllegalStateException("bad event");
        h.engine().submit(new Event.WatchedFiles(new DidChangeWatchedFilesParams(List.of())));
        h.runPending();
        assertThat(result).isCompletedWithValue("references");
    }

    // -- cancellation

    @Test
    void doNoWorkForRequestCancelledBeforeItRuns() {
        var h = start();
        var result = send("hover", Need.FILE);
        result.cancel(true);

        h.runPending();
        assertThat(result).isCancelled();
        assertThat(state.log).isEmpty();
        assertThat(h.engine().isIdle()).isTrue();
    }

    @Test
    void doNotRunBodyOfRequestCancelledWhilePreparing() {
        var h = start();
        var result = send("hover", Need.FILE);
        state.onPrepare = () -> result.cancel(true);

        h.runPending();
        assertThat(result).isCancelled();
        assertThat(state.log).contains("prepare hover").doesNotContain("body hover");
    }

    @Test
    void dropParkedRequestWhenCancelled() {
        var h = start();
        state.indexed = false;
        var result = send("references", Need.PROJECT);
        h.runPending();
        assertThat(state.log).contains("route references").doesNotContain("body references");

        result.cancel(true);
        h.runPending();

        // the body never runs, even once the project is ready
        state.indexed = true;
        h.open("main.nf", "a");
        h.settle();
        assertThat(state.log).doesNotContain("body references");
    }

    @Test
    void cancelCheckerThrowsOnceRequestIsCancelled() {
        var h = start();
        var future = new CompletableFuture<CompletableFuture<String>>();
        var checks = new ArrayList<Boolean>();
        var result = h.send(spec("references", Need.FILE), "empty", (ctx) -> {
            ctx.cancelChecker().checkCanceled();
            checks.add(true);
            // simulate $/cancelRequest arriving while the body runs
            future.join().cancel(true);
            ctx.cancelChecker().checkCanceled();
            checks.add(true);
            return "done";
        });
        future.complete(result);

        h.runPending();
        assertThat(checks).hasSize(1);
        assertThat(result).isCancelled();
        assertThat(errorLogs()).isEmpty();
    }

    // -- error containment

    @Test
    void failOnlyTheRequestWhoseBodyThrows() {
        var h = start();
        var failing = h.send(spec("hover", Need.FILE), null, (ctx) -> {
            throw new IllegalStateException("boom");
        });
        var next = send("next", Need.FILE);

        h.runPending();
        assertThat(failure(failing)).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(next).isCompletedWithValue("next");
        assertThat(errorLogs()).anyMatch(m -> m.contains("hover") && m.contains("boom"));
    }

    @Test
    void releaseRequestWhenRouteOrPrepareThrows() {
        var h = start();
        state.prepareError = new IllegalStateException("prepare failed");
        var prepare = send("hover", Need.FILE);
        h.runPending();
        state.prepareError = null;

        state.routeError = new IllegalStateException("route failed");
        var route = send("definition", Need.FILE);
        h.runPending();
        state.routeError = null;

        assertThat(failure(prepare)).hasMessage("prepare failed");
        assertThat(failure(route)).hasMessage("route failed");
        assertThat(state.log).doesNotContain("body hover", "body definition");

        var next = send("next", Need.FILE);
        h.runPending();
        assertThat(next).isCompletedWithValue("next");
    }

    @Test
    void containFailingEvent() {
        var h = start();
        state.applyError = new IllegalStateException("bad event");
        h.open("main.nf", "a");
        h.runPending();
        state.applyError = null;
        h.change("main.nf", "b");
        h.runPending();

        assertThat(state.log).containsExactly("event DidOpen", "event DidChange");
        assertThat(errorLogs()).anyMatch(m -> m.contains("DidOpen") && m.contains("bad event"));
    }

    @Test
    void containFailingBackgroundSlice() {
        var h = start();
        state.sliceError = new IllegalStateException("slice failed");
        h.open("main.nf", "a");
        h.settle();
        // retried once
        assertThat(state.slices).isEqualTo(2);
        assertThat(errorLogs()).anyMatch(m -> m.contains("background") && m.contains("slice failed"));

        state.sliceError = null;
        var result = send("hover", Need.FILE);
        h.runPending();
        assertThat(result).isCompletedWithValue("hover");
    }

    @Test
    void releaseParkedRequestsWhenBackgroundWorkKeepsFailing() {
        var h = start();
        state.indexed = false;
        state.sliceError = new IllegalStateException("slice failed");
        var parked = send("references", Need.PROJECT);
        h.settle();
        assertThat(errorCode(parked)).isEqualTo(ResponseErrorCode.InternalError.getValue());
        assertThat(state.log).doesNotContain("body references");
        assertThat(h.engine().isIdle()).isTrue();

        // background work resumes on the next request
        state.sliceError = null;
        state.pendingSlices = 1;
        var later = send("references", Need.PROJECT);
        h.settle();
        assertThat(later).isCompletedWithValue("references");
    }

    @Test
    void exitWithStatusOneOnVirtualMachineError() {
        var h = start();
        h.send(spec("hover", Need.FILE), null, (ctx) -> {
            throw new InternalError("fatal");
        });

        assertThatThrownBy(h::runPending).isInstanceOf(InternalError.class);
        assertThat(h.env().exits()).containsExactly(1);
    }

    // -- lifecycle

    @Test
    void failParkedAndLaterRequestsAfterShutdown() {
        var h = start();
        state.indexed = false;
        var parked = send("references", Need.PROJECT);
        h.runPending();

        var shutdown = h.engine().shutdown();
        var later = send("hover", Need.FILE);
        h.open("main.nf", "a");
        h.runPending();

        assertThat(shutdown).isCompletedWithValue(null);
        assertThat(errorCode(parked)).isEqualTo(ResponseErrorCode.InvalidRequest.getValue());
        assertThat(errorCode(later)).isEqualTo(ResponseErrorCode.InvalidRequest.getValue());
        assertThat(state.log).doesNotContain("event DidOpen", "route hover");
        assertThat(h.env().readPool().isShutdown()).isTrue();
        assertThat(h.env().manualExecutor().isShutdown()).isTrue();
        assertThat(h.engine().isIdle()).isTrue();
    }

    // -- IO and idleness

    @Test
    void deliverIoResultAsEvent() {
        var io = new ArrayList<Runnable>();
        var h = start(new TestEnvironment().withIoExecutor(io::add));
        assertThat(h.engine().isIdle()).isTrue();

        h.engine().submitIo("walk", () -> new Event.Initialized(new InitializedParams()));
        h.engine().submitIo("failing walk", () -> {
            throw new IOException("disk failed");
        });
        assertThat(h.engine().isIdle()).isFalse();

        io.forEach(Runnable::run);
        assertThat(h.engine().isIdle()).isFalse();
        h.runPending();
        assertThat(state.log).containsExactly("event Initialized");
        assertThat(errorLogs()).anyMatch(m -> m.contains("failing walk") && m.contains("disk failed"));
        assertThat(h.engine().isIdle()).isTrue();
    }

    @Test
    void reportIdleOnlyWhenNoWorkIsPending() {
        var h = start();
        assertThat(h.engine().isIdle()).isTrue();

        h.open("main.nf", "a");
        assertThat(h.engine().isIdle()).isFalse();

        // the debounced background task is still pending
        h.runPending();
        assertThat(h.engine().isIdle()).isFalse();

        h.settle();
        assertThat(h.engine().isIdle()).isTrue();
        assertThat(state.slices).isEqualTo(1);
    }

    @Test
    void rejectEngineStateAccessFromOtherThreads() throws Exception {
        var h = start();
        assertThat(thrownOnAnotherThread(() -> h.engine().checkEngineThread())).isInstanceOf(IllegalStateException.class);
        h.engine().checkEngineThread();
    }

}

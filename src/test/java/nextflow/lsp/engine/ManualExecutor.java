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

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.RejectedExecutionException;

/**
 * Serial executor driven by the test thread, with timers on a fake
 * clock, so that engine tests need no sleeps.
 *
 * Tasks and timers share one queue ordered by due time and then by
 * submission, like ScheduledThreadPoolExecutor. Tasks run only when
 * the test calls {@link #runNext}, {@link #runPending} or
 * {@link #advance}. The engine thread is the thread that created
 * the executor. Tasks can be submitted from any thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class ManualExecutor implements SerialExecutor {

    private static final class Timer {
        final Instant due;
        final long seq;
        final Runnable task;

        Timer(Instant due, long seq, Runnable task) {
            this.due = due;
            this.seq = seq;
            this.task = task;
        }
    }

    private final FakeClock clock;

    private final Thread owner = Thread.currentThread();

    private final PriorityQueue<Timer> queue = new PriorityQueue<>(
        Comparator.<Timer, Instant>comparing(t -> t.due).thenComparingLong(t -> t.seq));

    private long seq;

    private boolean shutdown;

    public ManualExecutor(FakeClock clock) {
        this.clock = clock;
    }

    @Override
    public void execute(Runnable task) {
        schedule(Duration.ZERO, task);
    }

    @Override
    public synchronized Cancellable schedule(Duration delay, Runnable task) {
        if( shutdown )
            throw new RejectedExecutionException("executor is shut down");
        var timer = new Timer(clock.instant().plus(delay.isNegative() ? Duration.ZERO : delay), seq++, task);
        queue.add(timer);
        return () -> {
            synchronized (this) {
                queue.remove(timer);
            }
        };
    }

    @Override
    public boolean inEngineThread() {
        return Thread.currentThread() == owner;
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
        var now = clock.instant();
        queue.removeIf(timer -> timer.due.isAfter(now));
    }

    public synchronized boolean isShutdown() {
        return shutdown;
    }

    /**
     * Run the next task that is due, if any.
     *
     * @return true if a task was run
     */
    public boolean runNext() {
        checkOwner();
        // let the JUnit timeout stop a test whose engine never goes idle
        if( Thread.currentThread().isInterrupted() )
            throw new IllegalStateException("Interrupted while running engine tasks");
        Timer next;
        synchronized (this) {
            next = queue.peek();
            if( next == null || next.due.isAfter(clock.instant()) )
                return false;
            queue.poll();
        }
        next.task.run();
        return true;
    }

    /**
     * Run tasks until no task is due, without moving the clock.
     *
     * @return the number of tasks that were run
     */
    public int runPending() {
        var count = 0;
        while( runNext() )
            count++;
        return count;
    }

    /**
     * Move the clock forward by the given duration, running each task
     * when it becomes due, in order.
     *
     * @param duration
     */
    public void advance(Duration duration) {
        var target = clock.instant().plus(duration);
        runPending();
        while( true ) {
            var due = nextDue();
            if( due.isEmpty() || due.get().isAfter(target) )
                break;
            clock.set(due.get());
            runPending();
        }
        clock.set(target);
        runPending();
    }

    /**
     * Move the clock to the next timer, if it is in the future.
     *
     * @return false if there is no pending task
     */
    public boolean advanceToNextTimer() {
        var due = nextDue();
        if( due.isEmpty() )
            return false;
        if( due.get().isAfter(clock.instant()) )
            clock.set(due.get());
        return true;
    }

    private synchronized Optional<Instant> nextDue() {
        var next = queue.peek();
        return next != null ? Optional.of(next.due) : Optional.empty();
    }

    private void checkOwner() {
        if( !inEngineThread() )
            throw new IllegalStateException("ManualExecutor must be driven by the thread that created it");
    }

}

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
package nextflow.lsp.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executor service that debounces incoming tasks, so
 * that a task is executed only after not being triggered
 * for a given delay.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class DebouncingExecutor {
    private final long delayMillis;
    private final Runnable action;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

    public DebouncingExecutor(long delayMillis, Runnable action) {
        this.delayMillis = delayMillis;
        this.action = action;
    }

    /**
     * Schedule the action after the configured delay, cancelling
     * the currently scheduled task if present.
     */
    public synchronized void executeLater() {
        cancelExisting();

        var future = scheduler.schedule(() -> {
            action.run();
            futureRef.set(null);
        }, delayMillis, TimeUnit.MILLISECONDS);

        futureRef.set(future);
    }

    /**
     * Execute the action immediately, cancelling the currently
     * scheduled task if present.
     */
    public synchronized void executeNow() {
        cancelExisting();
        action.run();
    }

    private void cancelExisting() {
        var existing = futureRef.getAndSet(null);
        if( existing != null && !existing.isDone() )
            existing.cancel(false);
    }

    /**
     * Call this method to shut down the executor when no longer needed.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}

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
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Serial executor backed by a single daemon thread.
 *
 * The underlying executor orders tasks by due time and then by
 * submission, so plain tasks and due timers run in FIFO order.
 *
 * NOTE: the executor captures exceptions in the task futures, so
 * tasks must contain their own errors (see Engine).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class ThreadSerialExecutor implements SerialExecutor {

    private final ScheduledThreadPoolExecutor executor;

    private volatile Thread thread;

    ThreadSerialExecutor(String name) {
        executor = new ScheduledThreadPoolExecutor(1, (runnable) -> {
            var result = new Thread(runnable, name);
            result.setDaemon(true);
            thread = result;
            return result;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        var future = executor.schedule(task, Math.max(0, delay.toNanos()), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public boolean inEngineThread() {
        return Thread.currentThread() == thread;
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }

}

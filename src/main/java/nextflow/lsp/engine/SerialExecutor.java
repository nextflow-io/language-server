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

/**
 * Single logical thread that runs tasks in FIFO order, with timers.
 *
 * A task submitted with {@link #execute} runs after every task that
 * was submitted before it, and a timer that becomes due is queued
 * behind the tasks that are already due.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface SerialExecutor {

    /**
     * Queue a task. Can be called from any thread.
     *
     * @param task
     */
    void execute(Runnable task);

    /**
     * Queue a task after the given delay. Can be called from any thread.
     *
     * @param delay
     * @param task
     */
    Cancellable schedule(Duration delay, Runnable task);

    /**
     * Whether the calling thread is the thread that runs the tasks.
     */
    boolean inEngineThread();

    void shutdown();

}

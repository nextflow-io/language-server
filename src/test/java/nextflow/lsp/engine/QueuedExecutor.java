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

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * Executor that queues tasks until the test runs them, so that
 * tests can hold IO (registry fetches, walks) in flight.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class QueuedExecutor implements Executor {

    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable task) {
        queue.add(task);
    }

    synchronized int size() {
        return queue.size();
    }

    /**
     * Run the queued tasks, including tasks queued while running.
     */
    int runAll() {
        var count = 0;
        while( true ) {
            Runnable next;
            synchronized (this) {
                next = queue.poll();
            }
            if( next == null )
                return count;
            next.run();
            count++;
        }
    }

}

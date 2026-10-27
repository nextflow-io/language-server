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
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import nextflow.lsp.TestLanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production engine thread and environment.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Timeout(60) // hang guard
class ThreadSerialExecutorTest {

    @Test
    void runTasksAndDueTimersInSubmissionOrder() throws Exception {
        var executor = new ThreadSerialExecutor("test-engine");
        try {
            var order = Collections.synchronizedList(new ArrayList<Integer>());
            var threads = Collections.synchronizedList(new ArrayList<Boolean>());
            var cancelled = executor.schedule(Duration.ofHours(1), () -> order.add(-1));
            for( int i = 0; i < 100; i++ ) {
                var n = i;
                Runnable task = () -> {
                    order.add(n);
                    threads.add(executor.inEngineThread());
                };
                if( i % 2 == 0 )
                    executor.execute(task);
                else
                    executor.schedule(Duration.ZERO, task);
            }
            cancelled.cancel();
            var done = new CompletableFuture<Void>();
            executor.execute(() -> done.complete(null));
            done.get(30, TimeUnit.SECONDS);

            assertThat(order).hasSize(100).isSorted();
            assertThat(threads).containsOnly(true);
            assertThat(executor.inEngineThread()).as("in engine thread").isFalse();
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    void stopTheEngineThreadOnShutdown() throws Exception {
        var env = new DefaultEngineEnvironment();
        try {
            var engine = new Engine(env, new TestLanguageClient(), Engine.DEFAULT_DEBOUNCE, FakeState::new);
            var spec = new RequestSpec("test", null, null, Need.FILE, false);
            var thread = engine.request(spec, null, (ctx) -> Thread.currentThread()).get(30, TimeUnit.SECONDS);
            var shutdown = engine.shutdown();
            // a request queued behind the shutdown is still completed, with an error
            var later = engine.request(spec, null, (ctx) -> Thread.currentThread());
            shutdown.get(30, TimeUnit.SECONDS);
            assertThat(later).failsWithin(30, TimeUnit.SECONDS);
            thread.join(30_000);
            assertThat(thread.isAlive()).as("engine thread is alive").isFalse();
        }
        finally {
            env.engineExecutor().shutdown();
        }
    }

    @Test
    void runRequestsOnTheEngineThreadOfTheSystemEnvironment() throws Exception {
        var env = new DefaultEngineEnvironment();
        try {
            var engine = new Engine(env, new TestLanguageClient(), Engine.DEFAULT_DEBOUNCE, FakeState::new);
            var spec = new RequestSpec("test", null, null, Need.FILE, false);
            var result = engine.request(spec, null, (ctx) -> env.engineExecutor().inEngineThread() && Thread.currentThread().isDaemon());

            assertThat(result.get(30, TimeUnit.SECONDS)).as("ran on a daemon engine thread").isTrue();
            engine.shutdown().get(30, TimeUnit.SECONDS);
        }
        finally {
            env.engineExecutor().shutdown();
        }
    }

}

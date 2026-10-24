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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the production environment.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class DefaultEngineEnvironmentTest {

    @Test
    void runWalksWhileRegistryRequestsAreBlocked() throws Exception {
        var env = new DefaultEngineEnvironment();
        var release = new CountDownLatch(1);
        try {
            // more blocked registry requests than there are IO threads
            for( int i = 0; i < 16; i++ ) {
                env.fetchExecutor().execute(() -> {
                    try {
                        release.await();
                    }
                    catch( InterruptedException e ) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            var walk = new CompletableFuture<Boolean>();
            env.ioExecutor().execute(() -> walk.complete(true));
            assertThat(walk.get(10, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            release.countDown();
            env.engineExecutor().shutdown();
            env.compilePool().shutdownNow();
            ((ExecutorService) env.ioExecutor()).shutdownNow();
            ((ExecutorService) env.fetchExecutor()).shutdownNow();
        }
    }

}

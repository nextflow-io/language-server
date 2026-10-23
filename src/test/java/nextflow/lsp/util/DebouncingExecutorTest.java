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

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class DebouncingExecutorTest {

    /**
     * Run a probe on the executor's own single-thread scheduler after the
     * given delay. Scheduled tasks run in order of due time, so the probe
     * runs after every task that was scheduled before it with the same delay.
     */
    static <T> T probeAfter(DebouncingExecutor debouncer, long delayMillis, Callable<T> probe) throws Exception {
        var field = DebouncingExecutor.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        var scheduler = (ScheduledExecutorService) field.get(debouncer);
        return scheduler.schedule(probe, delayMillis, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
    }

    @Test
    void delayAndDeduplicateRapidSubmissions() throws Exception {
        var counter = new AtomicInteger();
        var debouncer = new DebouncingExecutor(50, counter::incrementAndGet);

        try {
            // executeLater is called rapidly multiple times
            debouncer.executeLater();
            debouncer.executeLater();
            debouncer.executeLater();

            // action is only run once after the last delay
            assertThat(probeAfter(debouncer, 50, counter::get)).isEqualTo(1);
        }
        finally {
            debouncer.shutdown();
        }
    }

    @Test
    void executeNowRunsActionImmediatelyAndCancelsPendingTask() throws Exception {
        var counter = new AtomicInteger();
        var debouncer = new DebouncingExecutor(50, counter::incrementAndGet);

        try {
            debouncer.executeLater();
            debouncer.executeNow();

            // action runs immediately
            assertThat(counter.get()).isEqualTo(1);

            // pending task is cancelled
            assertThat(probeAfter(debouncer, 50, counter::get)).isEqualTo(1);
        }
        finally {
            debouncer.shutdown();
        }
    }

    @Test
    void multipleExecuteNowCallsRunActionEachTime() {
        var executions = new AtomicInteger();
        var debouncer = new DebouncingExecutor(100, executions::incrementAndGet);

        try {
            debouncer.executeNow();
            debouncer.executeNow();
            debouncer.executeNow();

            assertThat(executions.get()).isEqualTo(3);
        }
        finally {
            debouncer.shutdown();
        }
    }

    /**
     * The deadlocked threads are blocked on monitors, which can't be
     * interrupted, so executeNow() runs on a daemon thread that the
     * test abandons if it doesn't finish.
     */
    @Test
    @Timeout(10)
    void executeNowDoesNotDeadlockWithScheduledActionThatCallsExecuteLater() throws InterruptedException {
        // an action that reschedules itself while holding a lock, like LanguageService.update()
        var lock = new Object();
        var runs = new AtomicInteger();
        var timerHoldsLock = new CountDownLatch(1);
        var callerInAction = new CountDownLatch(1);
        var debouncerRef = new AtomicReference<DebouncingExecutor>();
        var debouncer = new DebouncingExecutor(10, () -> {
            if( runs.incrementAndGet() == 1 ) {
                synchronized (lock) {
                    timerHoldsLock.countDown();
                    try {
                        callerInAction.await(5, TimeUnit.SECONDS);
                    }
                    catch( InterruptedException e ) {
                        throw new RuntimeException(e);
                    }
                    debouncerRef.get().executeLater();
                }
            }
            else {
                callerInAction.countDown();
                synchronized (lock) {}
            }
        });
        debouncerRef.set(debouncer);

        try {
            // executeNow is called while the scheduled action holds the lock
            debouncer.executeLater();
            timerHoldsLock.await(5, TimeUnit.SECONDS);
            var caller = new Thread(debouncer::executeNow);
            caller.setDaemon(true);
            caller.start();
            caller.join(5_000);

            assertThat(caller.isAlive()).as("executeNow() deadlocked").isFalse();
        }
        finally {
            debouncer.shutdown();
        }
    }
}

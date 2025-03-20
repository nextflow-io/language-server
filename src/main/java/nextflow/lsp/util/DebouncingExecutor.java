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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Executor service that debounces incoming tasks, so
 * that a task is executed only after not being triggered
 * for a given time period.
 *
 * see: https://stackoverflow.com/questions/4742210/implementing-debounce-in-java/20978973
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class DebouncingExecutor<T> {
    private int delayMillis;
    private Consumer<T> action;
    private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    private ConcurrentHashMap<T, DelayedTask> delayedTasks = new ConcurrentHashMap<>();

    public DebouncingExecutor(int delayMillis, Consumer<T> action) {
        this.delayMillis = delayMillis;
        this.action = action;
    }

    public void submit(T key) {
        var newTask = new DelayedTask(key);

        // try until new task was added, or existing task was extended
        DelayedTask oldTask;
        do {
            oldTask = delayedTasks.putIfAbsent(key, newTask);
            if( oldTask == null )
                executor.schedule(newTask, delayMillis, TimeUnit.MILLISECONDS);
        } while( oldTask != null && !oldTask.extend() );
    }

    public void executeNow(T key) {
        var task = delayedTasks.get(key);
        if( task != null )
            task.cancel();
        action.accept(key);
    }

    public void shutdownNow() {
        executor.shutdownNow();
    }

    private class DelayedTask implements Runnable {
        private T key;
        private long dueTime;
        private Object lock = new Object();

        public DelayedTask(T key) {
            this.key = key;
            extend();
        }

        public boolean extend() {
            synchronized (lock) {
                if( dueTime < 0 )
                    return false;
                dueTime = System.currentTimeMillis() + delayMillis;
                return true;
            }
        }

        public void cancel() {
            synchronized (lock) {
                dueTime = -1;
                delayedTasks.remove(key);
            }
        }

        public void run() {
            synchronized (lock) {
                var remaining = dueTime - System.currentTimeMillis();
                if( remaining > 0 ) {
                    // re-schedule task
                    executor.schedule(this, remaining, TimeUnit.MILLISECONDS);
                }
                else if( dueTime != -1 ) {
                    // mark task as terminated and invoke callback
                    dueTime = -1;
                    try {
                        action.accept(key);
                    }
                    catch( Exception e ) {
                        System.err.println("exception while invoking debounce callback: " + e.toString());
                        e.printStackTrace(System.err);
                    }
                    finally {
                        delayedTasks.remove(key);
                    }
                }
            }
        }
    }

}

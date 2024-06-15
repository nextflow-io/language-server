package nextflow.lsp.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic

/**
 * Executor service that debounces incoming tasks, so
 * that a task is executed only after not being triggered
 * for a given time period.
 *
 * see: https://stackoverflow.com/questions/4742210/implementing-debounce-in-java/20978973
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class DebouncingExecutor <T> {
    private int delayMillis
    private Closure onComplete
    private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1)
    private ConcurrentHashMap<T, DelayedTask> delayedTasks = new ConcurrentHashMap<>()

    DebouncingExecutor(int delayMillis, Closure onComplete) {
        this.delayMillis = delayMillis
        this.onComplete = onComplete
    }

    void submit(T key) {
        final newTask = new DelayedTask(key)

        // try until new task was added, or existing task was extended
        DelayedTask oldTask
        do {
            oldTask = delayedTasks.putIfAbsent(key, newTask)
            if( oldTask == null )
                executor.schedule(newTask, delayMillis, TimeUnit.MILLISECONDS)
        } while( oldTask != null && !oldTask.extend() )
    }

    void executeNow(T key) {
        final task = delayedTasks.get(key)
        if( task )
            task.cancel()
        onComplete.call(key)
    }

    void shutdownNow() {
        executor.shutdownNow()
    }

    private class DelayedTask implements Runnable {
        private T key
        private long dueTime
        private Object lock = new Object()

        DelayedTask(T key) {
            this.key = key
            extend()
        }

        boolean extend() {
            synchronized (lock) {
                if( dueTime < 0 )
                    return false
                dueTime = System.currentTimeMillis() + delayMillis
                return true
            }
        }

        void cancel() {
            synchronized (lock) {
                dueTime = -1
                delayedTasks.remove(key)
            }
        }

        void run() {
            synchronized (lock) {
                final remaining = dueTime - System.currentTimeMillis()
                if( remaining > 0 ) {
                    // re-schedule task
                    executor.schedule(this, remaining, TimeUnit.MILLISECONDS)
                }
                else if( dueTime != -1 ) {
                    // mark task as terminated and invoke callback
                    dueTime = -1
                    try {
                        onComplete.call(key)
                    }
                    catch( Exception e ) {
                        System.err.println "exception while invoking debounce callback: ${e}"
                        e.printStackTrace(System.err)
                    }
                    finally {
                        delayedTasks.remove(key)
                    }
                }
            }
        }
    }

}

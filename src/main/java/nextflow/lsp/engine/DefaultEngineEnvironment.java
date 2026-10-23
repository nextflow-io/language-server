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

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production environment: a dedicated engine thread, a compile pool
 * with one thread per spare core, and a small IO pool. Every thread
 * is a daemon thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class DefaultEngineEnvironment implements EngineEnvironment {

    private static final int IO_THREADS = 4;

    private final SerialExecutor engineExecutor = new ThreadSerialExecutor("nextflow-engine");

    private final ExecutorService compilePool = new ForkJoinPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        null,
        false);

    private final ThreadPoolExecutor ioExecutor;

    DefaultEngineEnvironment() {
        ioExecutor = new ThreadPoolExecutor(
            IO_THREADS, IO_THREADS,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            daemonThreads("nextflow-io"));
        ioExecutor.allowCoreThreadTimeOut(true);
    }

    private static ThreadFactory daemonThreads(String prefix) {
        var count = new AtomicInteger();
        return (runnable) -> {
            var thread = new Thread(runnable, prefix + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public SerialExecutor engineExecutor() {
        return engineExecutor;
    }

    @Override
    public ExecutorService compilePool() {
        return compilePool;
    }

    @Override
    public Executor ioExecutor() {
        return ioExecutor;
    }

    @Override
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public FileAccess files() {
        return FileAccess.system();
    }

    @Override
    public PluginRegistryClient registry(String registryUrl) {
        return new HttpPluginRegistryClient(registryUrl);
    }

    @Override
    public Exiter exiter() {
        return System::exit;
    }

}

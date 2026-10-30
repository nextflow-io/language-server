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
import java.util.function.IntConsumer;

/**
 * Production environment: a dedicated engine thread, a read pool
 * with one thread per spare core up to a small cap, and small IO
 * and fetch pools.
 * Every thread is a daemon thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class DefaultEngineEnvironment implements EngineEnvironment {

    private static final int MAX_READ_THREADS = 4;

    private static final int IO_THREADS = 4;

    private static final int FETCH_THREADS = 2;

    private final SerialExecutor engineExecutor = new ThreadSerialExecutor("nextflow-engine");

    private final ExecutorService readPool = new ForkJoinPool(
        Math.min(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), MAX_READ_THREADS),
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        null,
        false);

    private final ThreadPoolExecutor ioExecutor;

    private final ThreadPoolExecutor fetchExecutor;

    private final FileAccess files;

    public DefaultEngineEnvironment() {
        this(FileAccess.system());
    }

    /**
     * Create a production environment with the given file access,
     * for testing.
     *
     * @param files
     */
    public DefaultEngineEnvironment(FileAccess files) {
        this.files = files;
        this.ioExecutor = pool(IO_THREADS, "nextflow-io");
        this.fetchExecutor = pool(FETCH_THREADS, "nextflow-fetch");
    }

    private static ThreadPoolExecutor pool(int threads, String prefix) {
        var result = new ThreadPoolExecutor(
            threads, threads,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            daemonThreads(prefix));
        result.allowCoreThreadTimeOut(true);
        return result;
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
    public ExecutorService readPool() {
        return readPool;
    }

    @Override
    public Executor ioExecutor() {
        return ioExecutor;
    }

    @Override
    public Executor fetchExecutor() {
        return fetchExecutor;
    }

    @Override
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public FileAccess files() {
        return files;
    }

    @Override
    public PluginRegistryClient registry(String registryUrl) {
        return new LocalPluginRegistryClient(
            LocalPluginRegistryClient.pluginsDir(System.getenv()),
            new HttpPluginRegistryClient(registryUrl));
    }

    @Override
    public IntConsumer exiter() {
        return System::exit;
    }

}

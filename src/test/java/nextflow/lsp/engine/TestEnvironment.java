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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Deterministic engine environment: the engine runs on the test
 * thread through a {@link ManualExecutor} with a {@link FakeClock},
 * and the read pool and IO executor run tasks on the calling
 * thread. The file system is real by default.
 *
 * Must be created on the test thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class TestEnvironment implements EngineEnvironment {

    private final FakeClock clock = new FakeClock();

    private final ManualExecutor executor = new ManualExecutor(clock);

    private ExecutorService readPool = new DirectExecutorService();

    private Executor ioExecutor = new DirectExecutorService();

    // the IO executor if not set
    private Executor fetchExecutor;

    private FileAccess files = FileAccess.system();

    private Function<String, PluginRegistryClient> registry = (url) -> (name, version) -> new PluginRegistryClient.NotFound();

    private final List<Integer> exits = Collections.synchronizedList(new ArrayList<>());

    public TestEnvironment withReadPool(ExecutorService readPool) {
        this.readPool = readPool;
        return this;
    }

    public TestEnvironment withIoExecutor(Executor ioExecutor) {
        this.ioExecutor = ioExecutor;
        return this;
    }

    public TestEnvironment withFetchExecutor(Executor fetchExecutor) {
        this.fetchExecutor = fetchExecutor;
        return this;
    }

    public TestEnvironment withFiles(FileAccess files) {
        this.files = files;
        return this;
    }

    public TestEnvironment withRegistry(Function<String, PluginRegistryClient> registry) {
        this.registry = registry;
        return this;
    }

    public FakeClock fakeClock() {
        return clock;
    }

    public ManualExecutor manualExecutor() {
        return executor;
    }

    /**
     * The exit statuses passed to the exiter, in order.
     */
    public List<Integer> exits() {
        return exits;
    }

    @Override
    public SerialExecutor engineExecutor() {
        return executor;
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
        return fetchExecutor != null ? fetchExecutor : ioExecutor;
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public FileAccess files() {
        return files;
    }

    @Override
    public PluginRegistryClient registry(String registryUrl) {
        return registry.apply(registryUrl);
    }

    @Override
    public IntConsumer exiter() {
        return exits::add;
    }

}

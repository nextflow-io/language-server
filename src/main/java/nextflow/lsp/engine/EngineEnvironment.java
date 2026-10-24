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
import java.util.function.IntConsumer;

/**
 * Runtime services used by the engine, so that tests can replace
 * threads, time, the file system and the plugin registry.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface EngineEnvironment {

    /**
     * The engine thread, which owns all language service state.
     */
    SerialExecutor engineExecutor();

    /**
     * Pool for reading files in parallel.
     */
    ExecutorService readPool();

    /**
     * Pool for blocking file I/O (directory walks). Results return
     * to the engine as events.
     */
    Executor ioExecutor();

    /**
     * Pool for plugin registry requests, separate from the IO pool so
     * that slow requests don't delay walks. Results return to the
     * engine as events.
     */
    Executor fetchExecutor();

    Clock clock();

    FileAccess files();

    /**
     * Get a client for the plugin registry at the given URL.
     *
     * @param registryUrl
     */
    PluginRegistryClient registry(String registryUrl);

    /**
     * Terminates the process with the given status (injectable for tests).
     */
    IntConsumer exiter();

    /**
     * Create the production environment, with daemon threads.
     */
    static EngineEnvironment system() {
        return new DefaultEngineEnvironment();
    }

}

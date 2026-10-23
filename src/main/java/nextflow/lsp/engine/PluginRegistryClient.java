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

import nextflow.lsp.spec.PluginSpec;

/**
 * Transport for fetching plugin specs from a plugin registry.
 * Called on the IO pool, so it may block.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@FunctionalInterface
public interface PluginRegistryClient {

    sealed interface Result permits Found, NotFound, Failed {}

    record Found(PluginSpec spec) implements Result {}

    /**
     * The registry has no such plugin or release.
     */
    record NotFound() implements Result {}

    /**
     * The request failed (network error or unexpected response)
     * and may be retried later.
     */
    record Failed(String reason) implements Result {}

    /**
     * Fetch the spec of a plugin release, or the latest release
     * if the version is null.
     *
     * @param name
     * @param version
     */
    Result fetch(String name, String version) throws InterruptedException;

}

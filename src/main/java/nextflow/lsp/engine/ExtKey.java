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

import java.nio.file.Path;

import nextflow.lsp.spec.PluginRef;

/**
 * An input of a build that is not a managed file. Each key has a
 * stamp that changes when the input changes, so that an entry
 * built against an older stamp is stale.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
sealed interface ExtKey {

    /**
     * The `lib` directory of a project.
     */
    record Lib(String projectId) implements ExtKey {}

    /**
     * The `nextflow_schema.json` file in a directory.
     */
    record Schema(Path dir) implements ExtKey {}

    /**
     * The spec of a plugin release.
     */
    record Plugin(PluginRef ref) implements ExtKey {}

}

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
package nextflow.lsp.ast;

import java.util.Optional;

/**
 * Read-only view of a project for request bodies. Valid only during
 * the request body and only on the engine thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface ProjectView {

    /**
     * The workspace folder name, or "" for the default project.
     */
    String name();

    /**
     * The scripts of the project, if the request file is a script,
     * or for project-wide requests.
     */
    Optional<ScriptView> scripts();

    /**
     * The scripts of the project, regardless of the request file.
     */
    ScriptView scriptView();

    /**
     * The config files of the project, regardless of the request file.
     */
    ConfigView configView();

}

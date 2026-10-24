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

/**
 * Minimum freshness of the state that a request is answered from.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public enum Need {
    /** The request file and its transitive dependencies are built first. */
    FILE,
    /** Like FILE, plus the files that depend on the request file. */
    FILE_AND_DEPENDENTS,
    /** Like FILE, plus every config file of the project. */
    PROJECT_CONFIGS,
    /** Parked until the project is indexed and fresh. */
    PROJECT,
    /** Parked until every project is indexed and fresh. */
    ALL
}

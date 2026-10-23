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

import java.net.URI;

import nextflow.lsp.ast.ProjectView;

/**
 * The project of a request, as seen by the request body: the
 * project itself plus the request file, if any.
 *
 * Valid only during the request body and only on the engine thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class ProjectHandle implements ProjectView {

    private final Engine engine;

    private final Project project;

    private final URI uri;

    private final FileKind kind;

    ProjectHandle(Engine engine, Project project, URI uri, FileKind kind) {
        this.engine = engine;
        this.project = project;
        this.uri = uri;
        this.kind = kind;
    }

    @Override
    public String name() {
        engine.checkEngineThread();
        return project.name();
    }

    /**
     * The project, for building views.
     */
    public Project project() {
        engine.checkEngineThread();
        return project;
    }

    /**
     * The canonical URI of the request file, or null for
     * project-wide requests.
     */
    public URI requestUri() {
        return uri;
    }

    /**
     * The kind of the request file, or null for project-wide requests.
     */
    public FileKind requestKind() {
        return kind;
    }

    /**
     * The entry of the request file, or null if there is none.
     */
    public FileEntry requestEntry() {
        engine.checkEngineThread();
        return uri != null ? project.entry(uri) : null;
    }

}

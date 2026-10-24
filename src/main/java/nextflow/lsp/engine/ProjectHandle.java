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

import java.util.Optional;

import nextflow.lsp.ast.ConfigView;
import nextflow.lsp.ast.ProjectView;
import nextflow.lsp.ast.ScriptView;

/**
 * The project of a request, as seen by the request body.
 *
 * Valid only during the request body and only on the engine thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class ProjectHandle implements ProjectView {

    private final Engine engine;

    private final Project project;

    private final FileKind kind;

    private ScriptView scriptView;

    private ConfigView configView;

    ProjectHandle(Engine engine, Project project, FileKind kind) {
        this.engine = engine;
        this.project = project;
        this.kind = kind;
    }

    @Override
    public String name() {
        engine.checkEngineThread();
        return project.name();
    }

    @Override
    public Optional<ScriptView> scripts() {
        return kind == null || kind == FileKind.SCRIPT ? Optional.of(scriptView()) : Optional.empty();
    }

    @Override
    public ScriptView scriptView() {
        engine.checkEngineThread();
        if( scriptView == null )
            scriptView = new ScriptEntryView(engine, project);
        return scriptView;
    }

    @Override
    public ConfigView configView() {
        engine.checkEngineThread();
        if( configView == null )
            configView = new ConfigEntryView(engine, project);
        return configView;
    }

}

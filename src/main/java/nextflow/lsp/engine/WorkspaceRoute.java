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
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import nextflow.lsp.ast.ProjectView;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

/**
 * The route of a request in the workspace: the projects that it
 * needs, and the file that it is about, if any.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class WorkspaceRoute implements EngineState.Route {

    private final Workspace workspace;

    private final Engine engine;

    private final Need need;

    private final List<Project> targets;

    private final Project project;

    private final URI uri;

    private final FileKind kind;

    WorkspaceRoute(Workspace workspace, Need need, List<Project> targets, Project project, URI uri, FileKind kind) {
        this.workspace = workspace;
        this.engine = workspace.engine();
        this.need = need;
        this.targets = targets;
        this.project = project;
        this.uri = uri;
        this.kind = kind;
    }

    @Override
    public void prepare(CancelChecker cancelChecker) {
        engine.checkEngineThread();
        try {
            switch( need ) {
                case FILE -> prepareFile(false, cancelChecker);
                case PROJECT_CONFIGS -> prepareFile(true, cancelChecker);
                case PROJECT, ALL -> targets.forEach(p -> workspace.buildAll(p, cancelChecker));
            }
        }
        finally {
            workspace.updateProgress();
        }
    }

    private void prepareFile(boolean configs, CancelChecker cancelChecker) {
        if( project == null )
            return;
        var roots = new ArrayList<FileEntry>();
        if( configs ) {
            // the main config is loaded on demand while the folder is walked
            if( project.root != null )
                loadOnDemand(project, project.root.resolve(Builder.MAIN_CONFIG).toUri());
            for( var entry : project.entries() ) {
                if( entry.kind == FileKind.CONFIG )
                    roots.add(entry);
            }
        }
        if( uri != null ) {
            var entry = loadOnDemand(project, uri);
            if( entry != null && !roots.contains(entry) )
                roots.add(entry);
        }
        workspace.buildClosure(project, roots, cancelChecker);
        workspace.markReadyIfDone(project);
    }

    /**
     * Get the entry of a file, and create it if it is not known
     * yet. The build removes it if the file doesn't exist.
     */
    private FileEntry loadOnDemand(Project project, URI uri) {
        var entry = project.entry(uri);
        if( entry == null ) {
            entry = project.getOrCreate(uri, project.isDefault() ? FileEntry.Role.DEPENDENCY : FileEntry.Role.WORKSPACE);
            Workspace.noteAdded(project, uri);
        }
        return entry;
    }

    @Override
    public boolean isIndexed() {
        return targets.stream().allMatch(Project::isIndexed);
    }

    @Override
    public RequestContext context(CancelChecker cancelChecker) {
        var configuration = workspace.configuration();
        var view = project != null ? new ProjectHandle(engine, project, kind) : null;
        var all = workspace.projects().stream()
            .map(p -> (ProjectView) new ProjectHandle(engine, p, null))
            .toList();
        UnaryOperator<String> clientUri = (uri) -> Uris.canonical(uri)
            .flatMap(engine.documents()::get)
            .map(DocumentStore.OpenDocument::clientUri)
            .orElse(uri);
        return new RequestContext(configuration, view, all, cancelChecker, clientUri);
    }

}

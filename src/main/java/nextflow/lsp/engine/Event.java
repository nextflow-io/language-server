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
import java.util.List;

import nextflow.lsp.spec.PluginRef;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;

/**
 * Input to the engine: a client notification, or the result of
 * background I/O.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public sealed interface Event {

    record Init(InitializeParams params) implements Event {}

    record Initialized(InitializedParams params) implements Event {}

    record DidOpen(DidOpenTextDocumentParams params) implements Event {}

    record DidChange(DidChangeTextDocumentParams params) implements Event {}

    record DidClose(DidCloseTextDocumentParams params) implements Event {}

    record WatchedFiles(DidChangeWatchedFilesParams params) implements Event {}

    record ConfigChanged(DidChangeConfigurationParams params) implements Event {}

    record FoldersChanged(DidChangeWorkspaceFoldersParams params) implements Event {}

    /**
     * A directory walk finished. Either files or error is set.
     */
    record WalkDone(Project project, long generation, List<Path> files, Exception error) implements Event {}

    /**
     * A plugin spec fetch finished. The registry URL identifies the
     * registry the fetch was made against, so that a result from a
     * previous registry is ignored.
     */
    record PluginFetched(String registryUrl, PluginRef ref, PluginRegistryClient.Result result) implements Event {}

    /**
     * The client responded to window/workDoneProgress/create.
     * The error is set if the request failed.
     */
    record ProgressCreated(String token, Throwable error) implements Event {}

}

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

package nextflow.lsp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.services.LanguageClient

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class TestLanguageClient implements LanguageClient {

    private final Map<String,List<Diagnostic>> diagnostics = new ConcurrentHashMap<>()

    private final List<Object> progress = Collections.synchronizedList(new ArrayList<>())

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return null
    }

    @Override
    public void showMessage(MessageParams messageParams) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        diagnostics.put(params.getUri(), params.getDiagnostics())
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        progress.add(params)
        return CompletableFuture.completedFuture(null)
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        progress.add(params)
    }

    /**
     * Get the progress requests and notifications sent by the server.
     */
    List<Object> getProgress() {
        return progress
    }

    @Override
    public void logMessage(MessageParams message) {
        System.err.println(message.getMessage())
    }

    /**
     * Get the most recently published diagnostics for a given file.
     *
     * @param uri
     */
    List<Diagnostic> getDiagnostics(String uri) {
        return diagnostics.getOrDefault(uri, Collections.emptyList())
    }

    /**
     * Get the most recently published diagnostics for all files.
     */
    Map<String,List<Diagnostic>> getAllDiagnostics() {
        return diagnostics
    }

    /**
     * Forget all captured diagnostics.
     */
    void resetDiagnostics() {
        diagnostics.clear()
    }
}

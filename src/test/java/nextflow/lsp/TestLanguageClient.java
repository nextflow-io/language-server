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

package nextflow.lsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Language client that records what the server sends.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class TestLanguageClient implements LanguageClient {

    private final Map<String,List<Diagnostic>> diagnostics = new ConcurrentHashMap<>();

    private final List<Object> progress = Collections.synchronizedList(new ArrayList<>());

    private final List<PublishDiagnosticsParams> publishLog = Collections.synchronizedList(new ArrayList<>());

    private final List<MessageParams> logMessages = Collections.synchronizedList(new ArrayList<>());

    private final List<RegistrationParams> registrations = Collections.synchronizedList(new ArrayList<>());

    private final Map<String,CompletableFuture<Void>> heldProgressCreates = Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile boolean holdProgressCreate;

    private volatile Runnable onProgressEnd = () -> {};

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return null;
    }

    @Override
    public void showMessage(MessageParams messageParams) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        diagnostics.put(params.getUri(), params.getDiagnostics());
        publishLog.add(params);
    }

    /**
     * Get every publishDiagnostics notification, in order.
     */
    public List<PublishDiagnosticsParams> getPublishLog() {
        return publishLog;
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        progress.add(params);
        if( !holdProgressCreate )
            return CompletableFuture.completedFuture(null);
        var result = new CompletableFuture<Void>();
        heldProgressCreates.put(params.getToken().getLeft(), result);
        return result;
    }

    /**
     * Hold the responses to window/workDoneProgress/create until
     * {@link #completeProgressCreate} is called.
     *
     * @param value
     */
    public void setHoldProgressCreate(boolean value) {
        holdProgressCreate = value;
    }

    /**
     * Respond to the held window/workDoneProgress/create requests.
     */
    public void completeProgressCreate() {
        List<CompletableFuture<Void>> pending;
        synchronized (heldProgressCreates) {
            pending = new ArrayList<>(heldProgressCreates.values());
            heldProgressCreates.clear();
        }
        pending.forEach(f -> f.complete(null));
    }

    /**
     * Respond to the held window/workDoneProgress/create request for
     * the given token.
     *
     * @param token
     */
    public void completeProgressCreate(String token) {
        var pending = heldProgressCreates.remove(token);
        if( pending != null )
            pending.complete(null);
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        registrations.add(params);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Get the client/registerCapability requests sent by the server.
     */
    public List<RegistrationParams> getRegistrations() {
        return registrations;
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        progress.add(params);
        if( params.getValue().getLeft() instanceof WorkDoneProgressEnd )
            onProgressEnd.run();
    }

    /**
     * Run an action whenever the server ends a progress, after the
     * end is recorded.
     *
     * @param action
     */
    public void onProgressEnd(Runnable action) {
        onProgressEnd = action;
    }

    /**
     * Get the progress requests and notifications sent by the server.
     */
    public List<Object> getProgress() {
        return progress;
    }

    @Override
    public void logMessage(MessageParams message) {
        logMessages.add(message);
        System.err.println(message.getMessage());
    }

    /**
     * Get the window/logMessage notifications sent by the server, in order.
     */
    public List<MessageParams> getLogMessages() {
        return logMessages;
    }

    /**
     * Get the most recently published diagnostics for a given file.
     *
     * @param uri
     */
    public List<Diagnostic> getDiagnostics(String uri) {
        return diagnostics.getOrDefault(uri, Collections.emptyList());
    }

    /**
     * Get the most recently published diagnostics for all files.
     */
    public Map<String,List<Diagnostic>> getAllDiagnostics() {
        return diagnostics;
    }

    /**
     * Get a snapshot of the most recently published diagnostics,
     * omitting the files that have none.
     */
    public Map<String,List<Diagnostic>> getNonEmptyDiagnostics() {
        var result = new HashMap<String,List<Diagnostic>>();
        diagnostics.forEach((uri, list) -> {
            if( !list.isEmpty() )
                result.put(uri, List.copyOf(list));
        });
        return result;
    }

    /**
     * Forget all captured diagnostics.
     */
    public void resetDiagnostics() {
        diagnostics.clear();
        publishLog.clear();
    }
}

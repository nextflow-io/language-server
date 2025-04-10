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
package nextflow.lsp.util;

import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ProgressNotification {

    private LanguageClient client;

    private String token;

    public ProgressNotification(LanguageClient client, String token) {
        this.client = client;
        this.token = token;
    }

    public void create() {
        client.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token)));
    }

    public void begin(String message) {
        var progress = new WorkDoneProgressBegin();
        progress.setMessage(message);
        progress.setPercentage(0);
        client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progress)));
    }

    public void update(String message, int percentage) {
        var progress = new WorkDoneProgressReport();
        progress.setMessage(message);
        progress.setPercentage(percentage);
        client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progress)));
    }

    public void end() {
        var progress = new WorkDoneProgressEnd();
        client.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(progress)));
    }

}

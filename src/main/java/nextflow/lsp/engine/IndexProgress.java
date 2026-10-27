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

import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Work-done progress for indexing.
 *
 * A run starts when some project starts indexing and ends only when
 * every project is indexed, so that the end of the progress means
 * that cross-file requests see the whole workspace. Each run has
 * its own token, and begin is sent only once the client has
 * acknowledged the token.
 *
 * Engine-confined.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class IndexProgress {

    static final String TOKEN_PREFIX = "nextflow/index/";

    private final Engine engine;

    private boolean supported;

    private int runs;

    // the current run, or null
    private String token;

    private boolean begun;

    private int lastDone = -1;

    private int lastTotal = -1;

    IndexProgress(Engine engine) {
        this.engine = engine;
    }

    void setSupported(boolean supported) {
        this.supported = supported;
    }

    /**
     * Update the progress with the current indexing state.
     *
     * @param indexing whether some project is indexing
     * @param done files built so far in the indexing projects
     * @param total files in the indexing projects
     */
    void update(boolean indexing, int done, int total) {
        if( indexing && token == null )
            start();
        if( token == null )
            return;
        if( !indexing ) {
            end();
            return;
        }
        if( begun && total > 0 && (done != lastDone || total != lastTotal) )
            report(done, total);
        lastDone = done;
        lastTotal = total;
    }

    /**
     * Handle the response to window/workDoneProgress/create.
     *
     * @param event
     */
    void onCreated(Event.ProgressCreated event) {
        if( !event.token().equals(token) || begun )
            return;
        if( event.error() != null ) {
            // the client refused the token -- keep it, unbegun, so
            // that this run stays silent and requests no other token
            return;
        }
        begun = true;
        var begin = new WorkDoneProgressBegin();
        begin.setTitle("Indexing");
        // the number of files is not known until the walk is done
        if( lastTotal > 0 )
            begin.setMessage(message(Math.max(lastDone, 0), lastTotal));
        begin.setPercentage(percentage(lastDone, lastTotal));
        notify(begin);
    }

    private void start() {
        if( !supported )
            return;
        var current = TOKEN_PREFIX + (++runs);
        token = current;
        begun = false;
        lastDone = -1;
        lastTotal = -1;
        engine.client().createProgress(new WorkDoneProgressCreateParams(Either.forLeft(current)))
            .whenComplete((result, error) -> engine.submit(new Event.ProgressCreated(current, error)));
    }

    private void report(int done, int total) {
        var report = new WorkDoneProgressReport();
        report.setMessage(message(done, total));
        report.setPercentage(percentage(done, total));
        notify(report);
    }

    private void end() {
        if( begun && lastTotal > 0 && lastDone != lastTotal )
            report(lastTotal, lastTotal);
        if( begun )
            notify(new WorkDoneProgressEnd());
        token = null;
        begun = false;
    }

    private void notify(WorkDoneProgressNotification notification) {
        engine.client().notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(notification)));
    }

    private static String message(int done, int total) {
        return done + " / " + total + " files";
    }

    private static Integer percentage(int done, int total) {
        return total > 0 ? Math.max(done, 0) * 100 / total : 0;
    }

}

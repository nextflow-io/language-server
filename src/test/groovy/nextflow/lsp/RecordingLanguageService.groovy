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

import java.util.concurrent.atomic.AtomicInteger

import nextflow.lsp.services.script.ScriptService

/**
 * A ScriptService that records how the update mechanics of the base
 * LanguageService are exercised: how many compile passes ran ({@code update()})
 * and how many workspace scans were performed ({@code getWorkspaceFiles()}).
 *
 * The workspace file set can be overridden so that scan behavior can be
 * tested deterministically without depending on files on disk.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class RecordingLanguageService extends ScriptService {

    private final AtomicInteger updates = new AtomicInteger()
    private final AtomicInteger scans = new AtomicInteger()

    /** When non-null, returned by getWorkspaceFiles() instead of scanning disk. */
    Set<URI> workspaceFilesOverride = null

    RecordingLanguageService(String rootUri) {
        super(rootUri)
    }

    @Override
    protected void update() {
        updates.incrementAndGet()
        super.update()
    }

    @Override
    protected Set<URI> getWorkspaceFiles() {
        scans.incrementAndGet()
        return workspaceFilesOverride != null
            ? workspaceFilesOverride
            : super.getWorkspaceFiles()
    }

    int getUpdateCount() {
        return updates.get()
    }

    int getScanCount() {
        return scans.get()
    }

}

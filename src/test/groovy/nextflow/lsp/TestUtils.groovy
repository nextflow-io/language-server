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

import java.nio.file.Files
import java.nio.file.Path

import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.lsp.services.LanguageService
import nextflow.lsp.services.config.ConfigService
import nextflow.lsp.services.script.ScriptService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class TestUtils {

    private static final Path workspaceRoot = workspaceRoot()

    private static Path workspaceRoot() {
        def workspaceRoot = Path.of(System.getProperty('user.dir')).resolve('build/test_workspace/')
        if( !Files.exists(workspaceRoot) )
            workspaceRoot.toFile().mkdirs()
        return workspaceRoot
    }

    /**
     * Get a language service instance for Nextflow config files.
     */
    static ConfigService getConfigService() {
        def service = new ConfigService(workspaceRoot.toUri().toString())
        def configuration = LanguageServerConfiguration.defaults()
        service.connect(new TestLanguageClient())
        service.initialize(configuration)
        // skip workspace scan
        open(service, getUri('nextflow.config'), '')
        service.updateNow()
        return service
    }

    /**
     * Get a language service instance for Nextflow scripts.
     */
    static ScriptService getScriptService() {
        def service = new ScriptService(workspaceRoot.toUri().toString())
        def configuration = LanguageServerConfiguration.defaults()
        service.connect(new TestLanguageClient())
        service.initialize(configuration)
        // skip workspace scan
        open(service, getUri('main.nf'), '')
        service.updateNow()
        return service
    }

    /**
     * Get the URI for a relative path.
     *
     * @param path
     */
    static String getUri(String path) {
        return workspaceRoot.resolve(path).toUri().toString()
    }

    /**
     * Open a file.
     *
     * NOTE: this operation is asynchronous due to debouncing
     *
     * @param service
     * @param uri
     * @param contents
     */
    static void open(LanguageService service, String uri, String contents) {
        def textDocumentItem = new TextDocumentItem(uri, 'nextflow', 1, contents.stripIndent())
        service.didOpen(new DidOpenTextDocumentParams(textDocumentItem))
    }

}

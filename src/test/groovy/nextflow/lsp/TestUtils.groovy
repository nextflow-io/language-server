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
import nextflow.lsp.spec.PluginSpecCache
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
     * Get the URI of the test workspace root.
     */
    static String workspaceRootUri() {
        return workspaceRoot.toUri().toString()
    }

    /**
     * Get a recording language service for testing the update mechanics of
     * the base LanguageService. The service is connected and initialized but
     * NOT scanned, with no files open.
     *
     * Recognized options:
     *   rootUri - the workspace root URI (defaults to the test workspace;
     *             pass null explicitly to exercise the no-workspace path)
     *   client  - the language client (defaults to a fresh TestLanguageClient)
     *   config  - the configuration (defaults to LanguageServerConfiguration.defaults())
     *
     * @param opts
     */
    static RecordingLanguageService recordingService(Map opts = [:]) {
        def rootUri = opts.containsKey('rootUri') ? opts.rootUri : workspaceRootUri()
        def client = opts.client ?: new TestLanguageClient()
        def configuration = opts.config ?: LanguageServerConfiguration.defaults()
        def service = new RecordingLanguageService(rootUri)
        service.connect(client)
        service.initialize(configuration, new PluginSpecCache(configuration.pluginRegistryUrl()))
        return service
    }

    /**
     * Get a language service instance for Nextflow config files.
     */
    static ConfigService getConfigService() {
        return getConfigService(new TestLanguageClient())
    }

    /**
     * Get a language service instance for Nextflow config files, connected to
     * the given client so that published diagnostics can be inspected.
     *
     * @param client
     */
    static ConfigService getConfigService(TestLanguageClient client) {
        def service = new ConfigService(workspaceRoot.toUri().toString())
        def configuration = LanguageServerConfiguration.defaults()
        service.connect(client)
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
        return getScriptService(new TestLanguageClient())
    }

    /**
     * Get a language service instance for Nextflow scripts, connected to the
     * given client so that published diagnostics can be inspected.
     *
     * @param client
     */
    static ScriptService getScriptService(TestLanguageClient client) {
        def service = new ScriptService(workspaceRoot.toUri().toString())
        def configuration = LanguageServerConfiguration.defaults()
        def pluginSpecCache = new PluginSpecCache(configuration.pluginRegistryUrl())
        service.connect(client)
        service.initialize(configuration, pluginSpecCache)
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

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.lsp.services.LanguageService;
import nextflow.lsp.services.config.ConfigService;
import nextflow.lsp.services.script.ScriptService;
import nextflow.lsp.spec.PluginSpecCache;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentItem;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class TestUtils {

    private static final Path workspaceRoot = workspaceRoot();

    /**
     * Test services don't use the debounce timer, so that a background
     * update (e.g. the deferred workspace scan) can't fire mid-test.
     * Changes are compiled by updateNow() or by a request that awaits
     * an update.
     */
    private static final long DEBOUNCE_MILLIS = LanguageService.MANUAL_UPDATES;

    private static Path workspaceRoot() {
        var workspaceRoot = Path.of(System.getProperty("user.dir")).resolve("build/test_workspace/");
        if( !Files.exists(workspaceRoot) )
            workspaceRoot.toFile().mkdirs();
        return workspaceRoot;
    }

    /**
     * Get the URI of the test workspace root.
     */
    public static String workspaceRootUri() {
        return workspaceRoot.toUri().toString();
    }

    /**
     * Get a recording language service for testing the update mechanics of
     * the base LanguageService. The service is connected and initialized but
     * NOT scanned, with no files open.
     */
    public static RecordingLanguageService recordingService() {
        return recordingService(new TestLanguageClient());
    }

    /**
     * Get a recording language service connected to the given client.
     *
     * @param client
     */
    public static RecordingLanguageService recordingService(TestLanguageClient client) {
        return recordingService(workspaceRootUri(), client, DEBOUNCE_MILLIS);
    }

    /**
     * Get a recording language service that debounces updates with the
     * given delay.
     *
     * @param debounceMillis
     */
    public static RecordingLanguageService recordingService(long debounceMillis) {
        return recordingService(workspaceRootUri(), new TestLanguageClient(), debounceMillis);
    }

    /**
     * Get a recording language service with no workspace root.
     */
    public static RecordingLanguageService recordingServiceWithoutWorkspace() {
        return recordingService(null, new TestLanguageClient(), DEBOUNCE_MILLIS);
    }

    private static RecordingLanguageService recordingService(String rootUri, TestLanguageClient client, long debounceMillis) {
        var service = new RecordingLanguageService(rootUri, debounceMillis);
        service.connect(client);
        service.initialize(LanguageServerConfiguration.defaults(), newConfigService(client));
        return service;
    }

    /**
     * Get a language service instance for Nextflow config files.
     */
    public static ConfigService getConfigService() {
        return getConfigService(new TestLanguageClient());
    }

    /**
     * Get a language service instance for Nextflow config files, connected to
     * the given client so that published diagnostics can be inspected.
     *
     * @param client
     */
    public static ConfigService getConfigService(TestLanguageClient client) {
        var service = newConfigService(client);
        // scan the (empty) workspace up front, so that later updates compile
        // changed files directly instead of re-triggering the scan
        service.updateNow();
        open(service, getUri("nextflow.config"), "");
        service.updateNow();
        return service;
    }

    /**
     * Get a language service instance for Nextflow config files which has not
     * scanned the workspace yet, for testing against files on disk.
     */
    public static ConfigService newConfigService() {
        return newConfigService(new TestLanguageClient());
    }

    /**
     * Get a language service instance for Nextflow config files which has not
     * scanned the workspace yet, connected to the given client.
     *
     * @param client
     */
    public static ConfigService newConfigService(TestLanguageClient client) {
        var service = new ConfigService(workspaceRootUri(), DEBOUNCE_MILLIS);
        service.connect(client);
        service.initialize(LanguageServerConfiguration.defaults(), new PluginSpecCache(TestPluginRegistry.url()));
        return service;
    }

    /**
     * Get a language service instance for Nextflow scripts.
     */
    public static ScriptService getScriptService() {
        return getScriptService(new TestLanguageClient());
    }

    /**
     * Get a language service instance for Nextflow scripts, connected to the
     * given client so that published diagnostics can be inspected.
     *
     * @param client
     */
    public static ScriptService getScriptService(TestLanguageClient client) {
        return getScriptService(client, newConfigService());
    }

    /**
     * Get a language service instance for Nextflow scripts, connected to the
     * given client and reading config from the given config service.
     *
     * @param client
     * @param configService
     */
    public static ScriptService getScriptService(TestLanguageClient client, ConfigService configService) {
        var service = new ScriptService(workspaceRootUri(), DEBOUNCE_MILLIS);
        service.connect(client);
        service.initialize(LanguageServerConfiguration.defaults(), configService);
        // skip workspace scan
        open(service, getUri("main.nf"), "");
        service.updateNow();
        return service;
    }

    /**
     * A script service and a config service that share a config AST cache.
     */
    public record ScriptAndConfig(ScriptService script, ConfigService config) {
    }

    /**
     * Get a script service and a config service that share a config AST cache,
     * so that script features which read the config (e.g. config preview) can
     * be tested.
     */
    public static ScriptAndConfig getScriptAndConfigServices() {
        var configService = getConfigService();
        var scriptService = getScriptService(new TestLanguageClient(), configService);
        return new ScriptAndConfig(scriptService, configService);
    }

    /**
     * Write a file into the test workspace.
     *
     * @param path
     * @param contents
     */
    public static void writeWorkspaceFile(String path, String contents) {
        var target = workspaceRoot.resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, contents.stripIndent());
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Delete a file from the test workspace.
     *
     * @param path
     */
    public static void deleteWorkspaceFile(String path) {
        deleteIfExists(workspaceRoot.resolve(path));
    }

    /**
     * Get the URI for a relative path.
     *
     * @param path
     */
    public static String getUri(String path) {
        return workspaceRoot.resolve(path).toUri().toString();
    }

    /**
     * Open a file.
     *
     * @param service
     * @param uri
     * @param contents
     */
    public static void open(LanguageService service, String uri, String contents) {
        openVerbatim(service, uri, contents.stripIndent());
    }

    /**
     * Open a file with exactly the given contents, without stripping
     * indentation or normalizing line endings as open() does.
     *
     * @param service
     * @param uri
     * @param contents
     */
    public static void openVerbatim(LanguageService service, String uri, String contents) {
        var textDocumentItem = new TextDocumentItem(uri, "nextflow", 1, contents);
        service.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
    }

    /**
     * Open a file, also writing it to disk. Tests that issue multiple
     * requests against the same file without a document change in between
     * must use this instead of open() if they trigger the deferred workspace
     * scan (an updateNow() with no pending changes): the scan re-scans from
     * disk and would evict an in-memory-only file from the AST cache.
     *
     * Delete the file with deleteOnDisk() in the test's cleanup block.
     *
     * @param service
     * @param uri
     * @param contents
     */
    public static void openOnDisk(LanguageService service, String uri, String contents) {
        try {
            Files.writeString(Path.of(URI.create(uri)), contents.stripIndent());
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
        open(service, uri, contents);
    }

    /**
     * Delete a file written by openOnDisk().
     *
     * @param uri
     */
    public static void deleteOnDisk(String uri) {
        deleteIfExists(Path.of(URI.create(uri)));
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

}

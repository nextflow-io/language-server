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
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import nextflow.script.formatter.FormattingOptions;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;

/**
 * Helpers for tests that use the {@link TestWorkspace} of the current
 * test, with or without a {@link TestServer}.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class TestUtils {

    public static final String UNUSED = "Variable was declared but not used";

    /**
     * Run an action on a new thread, and get what it threw, if anything.
     *
     * @param action
     */
    public static Throwable thrownOnAnotherThread(Runnable action) throws InterruptedException {
        var error = new AtomicReference<Throwable>();
        var thread = new Thread(action);
        thread.setUncaughtExceptionHandler((t, e) -> error.set(e));
        thread.start();
        thread.join();
        return error.get();
    }

    /**
     * Write a file into the workspace of the current test.
     *
     * @param path
     * @param contents
     */
    public static void writeWorkspaceFile(String path, String contents) {
        var target = TestWorkspace.root().resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, contents.stripIndent());
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Get the URI for a path relative to the workspace of the current test.
     *
     * @param path
     */
    public static String getUri(String path) {
        return TestWorkspace.root().resolve(path).toUri().toString();
    }

    /**
     * Execute a command in a test server.
     *
     * @param server
     * @param command
     * @param arguments
     */
    public static Object executeCommand(TestServer server, String command, List<Object> arguments) {
        return server.call(s -> s.executeCommand(new ExecuteCommandParams(command, arguments)));
    }

    /**
     * Format a document in a test server, with the formatting settings
     * of the given options.
     *
     * @param server
     * @param uri
     * @param options
     */
    public static List<? extends TextEdit> formatting(TestServer server, String uri, FormattingOptions options) {
        server.configure(Map.of(
            "nextflow.formatting.harshilAlignment", options.harshilAlignment(),
            "nextflow.formatting.maheshForm", options.maheshForm(),
            "nextflow.formatting.sortDeclarations", options.sortDeclarations()
        ));
        var params = new DocumentFormattingParams(
            new TextDocumentIdentifier(uri),
            new org.eclipse.lsp4j.FormattingOptions(options.tabSize(), options.insertSpaces()));
        return server.call(s -> s.formatting(params));
    }

}

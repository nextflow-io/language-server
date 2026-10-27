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
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Give each test its own workspace directory, so that tests can run
 * concurrently. The directory is created under `build/testWorkspaces/`
 * on first use and deleted after the test.
 *
 * Register it with `@ExtendWith(TestWorkspace.class)` on test classes
 * that use TestServer, EngineHarness, or the workspace helpers in
 * TestUtils, all of which resolve paths against it. The workspace is
 * bound to the thread that runs the test, so they must be used from
 * the test thread (e.g. not from a dynamic test that runs concurrently
 * with its factory).
 */
public class TestWorkspace implements BeforeEachCallback, AfterEachCallback {

    private static final Path PARENT = Path.of(System.getProperty("user.dir")).resolve("build/testWorkspaces");

    private static final ThreadLocal<TestWorkspace> current = new ThreadLocal<>();

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(TestWorkspace.class);

    private String prefix;

    private Path root;

    /**
     * Get the workspace root of the current test, creating it if needed.
     */
    public static Path root() {
        var workspace = current.get();
        if( workspace == null )
            throw new IllegalStateException("No test workspace is bound to thread " + Thread.currentThread().getName());
        if( workspace.root == null )
            workspace.root = createDirectory(workspace.prefix);
        return workspace.root;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        var workspace = new TestWorkspace();
        // name it after the top-level class, also for @Nested classes
        var testClass = context.getRequiredTestClass();
        while( testClass.getEnclosingClass() != null )
            testClass = testClass.getEnclosingClass();
        workspace.prefix = testClass.getSimpleName() + "-";
        // restored after the test, in case the thread was running another test
        var store = context.getStore(NAMESPACE);
        store.put("previous", current.get());
        store.put("workspace", workspace);
        current.set(workspace);
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        var store = context.getStore(NAMESPACE);
        var workspace = store.get("workspace", TestWorkspace.class);
        current.set(store.get("previous", TestWorkspace.class));
        if( workspace.root != null )
            deleteRecursively(workspace.root);
    }

    private static Path createDirectory(String prefix) {
        try {
            Files.createDirectories(PARENT);
            return Files.createTempDirectory(PARENT, prefix);
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try( var paths = Files.walk(dir) ) {
            for( var path : paths.sorted(Comparator.reverseOrder()).toList() )
                Files.deleteIfExists(path);
        }
    }

}

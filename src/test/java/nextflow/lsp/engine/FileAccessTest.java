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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import nextflow.lsp.TestWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class FileAccessTest {

    private final FileAccess files = FileAccess.system();

    private static Path write(String path, String text) throws IOException {
        var file = TestWorkspace.root().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void walkMatchingFilesInSortedOrderSkippingExcludedPaths() throws IOException {
        write("main.nf", "");
        write("nextflow.config", "");
        write("modules/b/main.nf", "");
        write("modules/a/main.nf", "");
        write("work/ab/cd/x.nf", "");
        write("README.md", "");
        write("skip.nf", "");
        var root = TestWorkspace.root();

        var result = files.walk(
            root,
            (path) -> path.getFileName().toString().equals("work") || path.getFileName().toString().equals("skip.nf"),
            (path) -> path.toString().endsWith(".nf") || path.toString().endsWith(".config"));

        assertThat(result).containsExactly(
            root.resolve("main.nf"),
            root.resolve("modules/a/main.nf"),
            root.resolve("modules/b/main.nf"),
            root.resolve("nextflow.config"));
    }

    @Test
    void neverExcludeTheRoot() throws IOException {
        var root = write("work/main.nf", "").getParent();

        var result = files.walk(root, (path) -> path.getFileName().toString().equals("work"), (path) -> true);
        assertThat(result).containsExactly(root.resolve("main.nf"));
    }

    @Test
    void readTextAndReportMissingFiles() throws IOException {
        var file = write("main.nf", "println 'héllo'");

        assertThat(files.readText(file)).isEqualTo("println 'héllo'");
        assertThat(files.exists(file)).isTrue();
        assertThat(files.exists(file.resolveSibling("missing.nf"))).isFalse();
        assertThatThrownBy(() -> files.readText(file.resolveSibling("missing.nf"))).isInstanceOf(NoSuchFileException.class);
    }

}

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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * File-system access used off the engine thread (walks and reads),
 * so that tests can inject faults.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface FileAccess {

    /**
     * Read a file as UTF-8 text. Malformed input is replaced
     * rather than rejected.
     *
     * @param path
     * @throws java.nio.file.NoSuchFileException if the file does not exist
     */
    String readText(Path path) throws IOException;

    boolean exists(Path path);

    /**
     * Get the regular files under a directory that are accepted by
     * the matcher, in sorted order. Excluded files and directories
     * (and everything under them) are skipped. The root itself is
     * never excluded. Unreadable subdirectories are skipped.
     *
     * @param root
     * @param excluded
     * @param matcher
     */
    List<Path> walk(Path root, Predicate<Path> excluded, Predicate<Path> matcher) throws IOException;

    static FileAccess system() {
        return SystemFileAccess.INSTANCE;
    }

    final class SystemFileAccess implements FileAccess {

        static final SystemFileAccess INSTANCE = new SystemFileAccess();

        private SystemFileAccess() {
        }

        @Override
        public String readText(Path path) throws IOException {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path);
        }

        @Override
        public List<Path> walk(Path root, Predicate<Path> excluded, Predicate<Path> matcher) throws IOException {
            var result = new ArrayList<Path>();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && excluded.test(dir)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if( attrs.isRegularFile() && !excluded.test(file) && matcher.test(file) )
                        result.add(file.normalize());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    if( file.equals(root) )
                        throw e;
                    return FileVisitResult.CONTINUE;
                }
            });
            result.sort(null);
            return result;
        }
    }

}

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
import java.util.List;
import java.util.stream.Stream;

import org.opentest4j.AssertionFailedError;

/**
 * Golden files: expected outputs stored next to their inputs under
 * {@code src/test/resources}. With {@code -Dgolden.update=true}
 * ({@code ./gradlew test -PupdateGolden}), the actual output is written to
 * the expected file instead of compared. Review the result with {@code git diff}.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class Golden {

    /**
     * Test resources in the source tree, relative to the project directory
     * (the test working directory).
     */
    public static final Path ROOT = Path.of("src/test/resources");

    public static final boolean UPDATE = Boolean.getBoolean("golden.update");

    private Golden() {}

    /**
     * Input files in a directory under {@link #ROOT} with the given
     * extension, sorted by name.
     */
    public static List<Path> inputs(String dir, String extension) {
        try( Stream<Path> files = Files.list(ROOT.resolve(dir)) ) {
            return files
                .filter(p -> p.getFileName().toString().endsWith(extension))
                .sorted()
                .toList();
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The file next to {@code input} with the same base name and another
     * extension.
     */
    public static Path sibling(Path input, String extension) {
        var name = input.getFileName().toString();
        return input.resolveSibling(name.substring(0, name.lastIndexOf('.')) + extension);
    }

    public static String read(Path file) {
        try {
            return Files.readString(file);
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Compare {@code actual} with the contents of {@code expectedFile}.
     */
    public static void check(Path expectedFile, String actual) {
        try {
            if( UPDATE ) {
                Files.writeString(expectedFile, actual);
                return;
            }
            if( !Files.exists(expectedFile) )
                throw new AssertionFailedError("missing " + expectedFile + ", actual output:\n" + actual + UPDATE_HINT);
            var expected = Files.readString(expectedFile);
            if( !expected.equals(actual) ) {
                // expected and actual are attached so that IDEs can show a diff
                throw new AssertionFailedError("output differs from " + expectedFile + "\n" + firstDifference(expected, actual) + UPDATE_HINT,
                    expected, actual);
            }
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String UPDATE_HINT = "\nIf the change is intended, run ./gradlew test -PupdateGolden and review the diff.";

    private static String firstDifference(String expected, String actual) {
        var e = expected.lines().toList();
        var a = actual.lines().toList();
        var i = 0;
        while( i < e.size() && i < a.size() && e.get(i).equals(a.get(i)) )
            i++;
        if( i == e.size() && i == a.size() )
            return "the line endings or the trailing newline differ";
        return "first difference at line " + (i + 1) + ":"
            + "\n  expected:     " + (i < e.size() ? e.get(i) : "<end of file>")
            + "\n  actual:       " + (i < a.size() ? a.get(i) : "<end of file>");
    }
}

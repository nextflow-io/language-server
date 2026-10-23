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

package nextflow.lsp.file;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class FileCacheTest {

    @Test
    void openAndChangeFile() {
        var fileCache = new FileCache();

        // open a file
        fileCache.didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem("file.txt", "plaintext", 1, "hello world")
        ));
        assertThat(fileCache.getContents(URI.create("file.txt"))).isEqualTo("hello world");

        // change an entire file
        fileCache.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier("file.txt", 2),
            Collections.singletonList(
                new TextDocumentContentChangeEvent("hi there")
            )
        ));
        assertThat(fileCache.getContents(URI.create("file.txt"))).isEqualTo("hi there");

        // update a file with incremental changes
        fileCache.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier("file.txt", 3),
            List.of(
                new TextDocumentContentChangeEvent(
                    new Range(new Position(0, 0), new Position(0, 2)),
                    "hello"
                ),
                new TextDocumentContentChangeEvent(
                    new Range(new Position(0, 11), new Position(0, 11)),
                    ", friend"
                )
            )
        ));
        assertThat(fileCache.getContents(URI.create("file.txt"))).isEqualTo("hello there, friend");
    }

    @Test
    void trackAndResetChangedFiles() {
        var fileCache = new FileCache();
        var a = URI.create("a.txt");
        var b = URI.create("b.txt");

        // two files are opened -> both are reported as changed
        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem("a.txt", "plaintext", 1, "a")));
        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem("b.txt", "plaintext", 1, "b")));
        assertThat(fileCache.removeChangedFiles()).containsExactlyInAnyOrder(a, b);

        // the changed set is reset after being drained
        assertThat(fileCache.removeChangedFiles()).isEmpty();

        // a file is explicitly marked changed
        fileCache.markChanged(a);
        assertThat(fileCache.removeChangedFiles()).containsExactly(a);
    }

    @Test
    void trackOpenFilesAndCloseThem() {
        var fileCache = new FileCache();
        var uri = URI.create("file.txt");

        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem("file.txt", "plaintext", 1, "hello")));
        assertThat(fileCache.isOpen(uri)).isTrue();
        assertThat(fileCache.getOpenFiles()).containsExactly(uri);

        // the file is closed -> it is no longer open and is marked changed
        fileCache.removeChangedFiles();
        fileCache.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier("file.txt")));
        assertThat(fileCache.isOpen(uri)).isFalse();
        assertThat(fileCache.getOpenFiles()).isEmpty();
        assertThat(fileCache.removeChangedFiles()).containsExactly(uri);
    }

    @Test
    void doNotMarkUnopenedFileChangedOnClose() {
        var fileCache = new FileCache();

        fileCache.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier("ghost.txt")));
        assertThat(fileCache.removeChangedFiles()).isEmpty();
    }

    @Test
    void readUnopenedFileContentsFromFilesystem() throws IOException {
        var fileCache = new FileCache();
        var file = Files.createTempFile("filecache", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, "on disk");

        // an unopened file is read from disk
        assertThat(fileCache.getContents(file.toUri())).isEqualTo("on disk");

        // a missing file returns null
        assertThat(fileCache.getContents(URI.create("file:///no/such/file.txt"))).isNull();
    }

    @Test
    void preferOpenContentsOverFilesystem() throws IOException {
        var fileCache = new FileCache();
        var file = Files.createTempFile("filecache", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, "on disk");
        var uri = file.toUri();

        // the file is opened with different in-memory contents
        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri.toString(), "plaintext", 1, "in memory")));
        assertThat(fileCache.getContents(uri)).isEqualTo("in memory");
    }

}

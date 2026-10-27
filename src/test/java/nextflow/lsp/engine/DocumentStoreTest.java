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

import java.net.URI;
import java.util.List;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static nextflow.lsp.TestUtils.thrownOnAnotherThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies the text sync of open documents: edits, line endings,
 * clamping, versions and canonical URIs.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class DocumentStoreTest {

    static final String URI_STRING = "file:///project/main.nf";

    static final URI CANONICAL = URI.create(URI_STRING);

    private final DocumentStore store = new DocumentStore(new ManualExecutor(new FakeClock()));

    private void open(String uri, int version, String text) {
        store.open(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "nextflow", version, text)));
    }

    private static DidChangeTextDocumentParams change(String uri, Integer version, TextDocumentContentChangeEvent... changes) {
        return new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, version), List.of(changes));
    }

    private static TextDocumentContentChangeEvent edit(int startLine, int startChar, int endLine, int endChar, String text) {
        return new TextDocumentContentChangeEvent(new Range(new Position(startLine, startChar), new Position(endLine, endChar)), text);
    }

    private static TextDocumentContentChangeEvent replace(String text) {
        return new TextDocumentContentChangeEvent(text);
    }

    private String text() {
        return store.get(CANONICAL).orElseThrow().text();
    }

    @Test
    void replaceTheTextOnFullContentChange() {
        open(URI_STRING, 1, "hello world");
        assertThat(text()).isEqualTo("hello world");

        store.change(change(URI_STRING, 2, replace("hi there")));
        assertThat(text()).isEqualTo("hi there");
        assertThat(store.version(CANONICAL)).isEqualTo(2);
    }

    @Test
    void applyIncrementalChangesInArrayOrder() {
        open(URI_STRING, 1, "hi there");

        // the second range refers to the text after the first change
        store.change(change(URI_STRING, 2,
            edit(0, 0, 0, 2, "hello"),
            edit(0, 11, 0, 11, ", friend")));
        assertThat(text()).isEqualTo("hello there, friend");
    }

    @Test
    void applyFullAndIncrementalChangesInOneNotification() {
        open(URI_STRING, 1, "old");

        store.change(change(URI_STRING, 2,
            edit(0, 0, 0, 3, "older"),
            replace("abc"),
            edit(0, 3, 0, 3, "d")));
        assertThat(text()).isEqualTo("abcd");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        LF   | 1 | 0 | 1 | 1 | X | a@nX@nc
        CRLF | 1 | 0 | 1 | 1 | X | a@r@nX@r@nc
        CR   | 1 | 0 | 1 | 1 | X | a@rX@rc
        CRLF | 2 | 0 | 2 | 0 | X | a@r@nb@r@nXc
        CR   | 0 | 1 | 1 | 0 | X | aXb@rc
        CRLF | 0 | 1 | 1 | 0 | X | aXb@r@nc
        """)
    void supportEveryLineTerminator(String terminator, int startLine, int startChar, int endLine, int endChar, String newText, String expected) {
        var eol = switch( terminator ) {
            case "LF" -> "\n";
            case "CRLF" -> "\r\n";
            default -> "\r";
        };
        open(URI_STRING, 1, String.join(eol, "a", "b", "c"));

        store.change(change(URI_STRING, 2, edit(startLine, startChar, endLine, endChar, newText)));
        assertThat(text()).isEqualTo(expected.replace("@n", "\n").replace("@r", "\r"));
    }

    @Test
    void clampCharacterPastLineEndToLineEnd() {
        open(URI_STRING, 1, "ab\r\ncd");

        // never between "\r" and "\n"
        store.change(change(URI_STRING, 2, edit(0, 10, 0, 99, "X")));
        assertThat(text()).isEqualTo("abX\r\ncd");
    }

    @Test
    void clampLinePastEndToDocumentEnd() {
        open(URI_STRING, 1, "ab\ncd");

        store.change(change(URI_STRING, 2, edit(5, 3, 9, 0, "X")));
        assertThat(text()).isEqualTo("ab\ncdX");

        // a range that starts in the document and ends past it
        store.change(change(URI_STRING, 3, edit(1, 1, 7, 7, "")));
        assertThat(text()).isEqualTo("ab\nc");
    }

    @Test
    void clampNegativePositionsToDocumentStart() {
        open(URI_STRING, 1, "ab");

        store.change(change(URI_STRING, 2, edit(-1, -5, 0, -1, "X")));
        assertThat(text()).isEqualTo("Xab");
    }

    @Test
    void treatReversedRangeAsEmpty() {
        open(URI_STRING, 1, "abcdef");

        store.change(change(URI_STRING, 2, edit(0, 3, 0, 1, "X")));
        assertThat(text()).isEqualTo("abcXdef");
    }

    @Test
    void countCharactersInUtf16CodeUnits() {
        // the emoji is one code point but two UTF-16 code units
        open(URI_STRING, 1, "a😀b");

        store.change(change(URI_STRING, 2, edit(0, 3, 0, 3, "X")));
        assertThat(text()).isEqualTo("a😀Xb");
    }

    @Test
    void insertIntoEmptyDocument() {
        open(URI_STRING, 1, "");

        store.change(change(URI_STRING, 2, edit(0, 0, 0, 0, "x"), edit(0, 1, 0, 1, "\ny")));
        assertThat(text()).isEqualTo("x\ny");
    }

    @Test
    void ignoreChangeOfUnknownDocument() {
        var result = store.change(change(URI_STRING, 2, edit(0, 0, 0, 0, "x")));

        assertThat(result).as("applied").isFalse();
        assertThat(store.get(CANONICAL)).isEmpty();
        assertThat(store.uris()).isEmpty();
    }

    @Test
    void ignoreCloseOfUnknownDocument() {
        assertThat(store.close(CANONICAL)).as("closed").isFalse();

        open(URI_STRING, 1, "x");
        assertThat(store.close(CANONICAL)).as("closed").isTrue();
        assertThat(store.isOpen(CANONICAL)).as("open").isFalse();
        assertThat(store.version(CANONICAL)).isNull();
    }

    @Test
    void ignoreNonFileUris() {
        open("untitled:Untitled-1", 1, "x");
        open("git:/project/main.nf?ref=HEAD", 1, "x");

        assertThat(store.uris()).isEmpty();
    }

    @Test
    void recordTheVersionOfEachChangeAndKeepItWhenMissing() {
        open(URI_STRING, 3, "x");
        assertThat(store.version(CANONICAL)).isEqualTo(3);

        store.change(change(URI_STRING, 7, replace("y")));
        assertThat(store.version(CANONICAL)).isEqualTo(7);

        // a missing version keeps the previous one
        store.change(change(URI_STRING, null, replace("z")));
        assertThat(store.version(CANONICAL)).isEqualTo(7);
        assertThat(text()).isEqualTo("z");
    }

    /**
     * Reopening without a close is a client bug, tolerated by taking the new text.
     */
    @Test
    void replaceDocumentWhenReopened() {
        open(URI_STRING, 1, "first");
        open(URI_STRING, 1, "second");

        assertThat(text()).isEqualTo("second");
        assertThat(store.uris()).containsExactly(CANONICAL);
    }

    @Test
    void keyDocumentsByCanonicalUri() {
        open("file:///project/sub/../my%20dir/./main.nf", 1, "x");
        var canonical = URI.create("file:///project/my%20dir/main.nf");

        var document = store.get(canonical).orElseThrow();
        assertThat(document.uri()).isEqualTo(canonical);
        assertThat(document.clientUri()).isEqualTo("file:///project/sub/../my%20dir/./main.nf");

        store.change(change("file:///project/my%20dir/main.nf", 2, replace("y")));
        assertThat(store.get(canonical).orElseThrow().text()).isEqualTo("y");
        assertThat(store.uris()).containsExactly(canonical);
    }

    @Test
    void listUrisInSortedOrder() {
        open("file:///project/c.nf", 1, "");
        open("file:///project/a.nf", 1, "");
        open("file:///project/b.nf", 1, "");

        assertThat(store.uris()).extracting(URI::getPath).containsExactly("/project/a.nf", "/project/b.nf", "/project/c.nf");
    }

    @Test
    void rejectAccessFromOtherThreads() throws InterruptedException {
        assertThat(thrownOnAnotherThread(() -> store.get(CANONICAL))).isInstanceOf(IllegalStateException.class);
    }

}

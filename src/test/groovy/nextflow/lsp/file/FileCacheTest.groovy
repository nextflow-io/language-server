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

package nextflow.lsp.file

import java.nio.file.Files

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class FileCacheTest extends Specification {

    def test() {
        given:
        def fileCache = new FileCache()

        when: 'should open a file'
        fileCache.didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem('file.txt', 'plaintext', 1, 'hello world')
        ))
        then:
        'hello world' == fileCache.getContents(URI.create('file.txt'))

        when: 'should change an entire file'
        fileCache.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier('file.txt', 2),
            Collections.singletonList(
                new TextDocumentContentChangeEvent('hi there')
            )
        ))
        then:
        'hi there' == fileCache.getContents(URI.create('file.txt'))

        when: 'should update a file with incremental changes'
        fileCache.didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier('file.txt', 3),
            List.of(
                new TextDocumentContentChangeEvent(
                    new Range(new Position(0, 0), new Position(0, 2)),
                    'hello'
                ),
                new TextDocumentContentChangeEvent(
                    new Range(new Position(0, 11), new Position(0, 11)),
                    ', friend'
                )
            )
        ))
        then:
        'hello there, friend' == fileCache.getContents(URI.create('file.txt'))
    }

    def 'should track and reset the set of changed files' () {
        given:
        def fileCache = new FileCache()
        def a = URI.create('a.txt')
        def b = URI.create('b.txt')

        when: 'two files are opened'
        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem('a.txt', 'plaintext', 1, 'a')))
        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem('b.txt', 'plaintext', 1, 'b')))
        then: 'both are reported as changed'
        fileCache.removeChangedFiles() == [a, b] as Set

        and: 'the changed set is reset after being drained'
        fileCache.removeChangedFiles().isEmpty()

        when: 'a file is explicitly marked changed'
        fileCache.markChanged(a)
        then:
        fileCache.removeChangedFiles() == [a] as Set
    }

    def 'should track open files and close them' () {
        given:
        def fileCache = new FileCache()
        def uri = URI.create('file.txt')

        when:
        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem('file.txt', 'plaintext', 1, 'hello')))
        then:
        fileCache.isOpen(uri)
        fileCache.getOpenFiles() == [uri] as Set

        when: 'the file is closed'
        fileCache.removeChangedFiles()
        fileCache.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier('file.txt')))
        then: 'it is no longer open and is marked changed'
        !fileCache.isOpen(uri)
        fileCache.getOpenFiles().isEmpty()
        fileCache.removeChangedFiles() == [uri] as Set
    }

    def 'should not mark an unopened file changed on close' () {
        given:
        def fileCache = new FileCache()

        when: 'closing a file that was never opened'
        fileCache.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier('ghost.txt')))
        then:
        fileCache.removeChangedFiles().isEmpty()
    }

    def 'should read unopened file contents from the filesystem' () {
        given:
        def fileCache = new FileCache()
        def file = Files.createTempFile('filecache', '.txt')
        file.toFile().deleteOnExit()
        Files.writeString(file, 'on disk')

        expect: 'an unopened file is read from disk'
        fileCache.getContents(file.toUri()) == 'on disk'

        and: 'a missing file returns null'
        fileCache.getContents(URI.create('file:///no/such/file.txt')) == null
    }

    def 'should prefer open contents over the filesystem' () {
        given:
        def fileCache = new FileCache()
        def file = Files.createTempFile('filecache', '.txt')
        file.toFile().deleteOnExit()
        Files.writeString(file, 'on disk')
        def uri = file.toUri()

        when: 'the file is opened with different in-memory contents'
        fileCache.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri.toString(), 'plaintext', 1, 'in memory')))
        then:
        fileCache.getContents(uri) == 'in memory'
    }

}

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

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
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
                    new Range(new Position(0, 8), new Position(0, 8)),
                    ', friend'
                )
            )
        ))
        then:
        'hello there, friend' == fileCache.getContents(URI.create('file.txt'))
    }

}

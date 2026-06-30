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

package nextflow.lsp.services.script

import nextflow.lsp.services.SemanticTokensVisitor
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptSemanticTokensTest extends Specification {

    /**
     * Decode the LSP delta-encoded token stream into absolute
     * [line, character, length, typeName] tuples.
     */
    static List<List> decode(List<Integer> data) {
        def types = SemanticTokensVisitor.TOKEN_TYPES
        def result = []
        int line = 0
        int ch = 0
        for( int i = 0; i < data.size(); i += 5 ) {
            int deltaLine = data[i]
            int deltaChar = data[i + 1]
            if( deltaLine > 0 ) {
                line += deltaLine
                ch = deltaChar
            }
            else {
                ch += deltaChar
            }
            result << [line, ch, data[i + 2], types[data[i + 3]]]
        }
        return result
    }

    List<List> getTokens(ScriptService service, String uri) {
        def st = service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri)))
        return st != null ? decode(st.getData()) : null
    }

    def 'should emit a function token for an included name' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            include { HELLO } from './module.nf'

            workflow {
                HELLO()
            }
            ''')
        service.updateNow()
        def tokens = getTokens(service, uri)
        then: 'the imported name "HELLO" is highlighted as a function at column 10'
        tokens.contains([0, 10, 5, 'function'])
        and: 'the token stream is well-formed (groups of 5 ints)'
        service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).getData().size() % 5 == 0
    }

    def 'should return null for a file that has no AST' () {
        given:
        def service = getScriptService()

        expect:
        getTokens(service, getUri('not-open.nf')) == null
    }

}

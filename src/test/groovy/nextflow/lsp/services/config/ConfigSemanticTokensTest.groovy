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

package nextflow.lsp.services.config

import nextflow.lsp.services.SemanticTokensVisitor
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigSemanticTokensTest extends Specification {

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

    List<List> getTokens(ConfigService service, String uri) {
        def st = service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri)))
        return st != null ? decode(st.getData()) : null
    }

    def 'should tokenize the value expression of a config assignment' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, '''\
            process.clusterOptions = "--account=${params.account}"
            ''')
        service.updateNow()
        def tokens = getTokens(service, uri)
        then: 'the GString literal, interpolated variable, and property are highlighted'
        tokens == [
            [0, 26, 10, 'string'],
            [0, 38, 6, 'variable'],
            [0, 45, 7, 'property'],
        ]
    }

    def 'should not tokenize assignment keys or plain literal values' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, '''\
            includeConfig 'extra.config'
            process.cpus = 2
            ''')
        service.updateNow()
        def tokens = getTokens(service, uri)
        then:
        tokens.isEmpty()
    }

    def 'should return null for a file that has no AST' () {
        given:
        def service = getConfigService()

        expect:
        getTokens(service, getUri('not-open.config')) == null
    }

}

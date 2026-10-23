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

package nextflow.lsp.services.script;

import java.util.ArrayList;
import java.util.List;

import nextflow.lsp.TestWorkspace;
import nextflow.lsp.services.SemanticTokensVisitor;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ScriptSemanticTokensTest {

    record Token(int line, int character, int length, String type) {}

    /**
     * Decode the LSP delta-encoded token stream into absolute tokens.
     */
    static List<Token> decode(List<Integer> data) {
        var types = SemanticTokensVisitor.TOKEN_TYPES;
        var result = new ArrayList<Token>();
        int line = 0;
        int ch = 0;
        for( int i = 0; i < data.size(); i += 5 ) {
            int deltaLine = data.get(i);
            int deltaChar = data.get(i + 1);
            if( deltaLine > 0 ) {
                line += deltaLine;
                ch = deltaChar;
            }
            else {
                ch += deltaChar;
            }
            result.add(new Token(line, ch, data.get(i + 2), types.get(data.get(i + 3))));
        }
        return result;
    }

    static List<Token> getTokens(ScriptService service, String uri) {
        var st = service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri)));
        return st != null ? decode(st.getData()) : null;
    }

    @Test
    void emitFunctionTokenForIncludedName() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
            include { HELLO } from './module.nf'

            workflow {
                HELLO()
            }
            """);
        service.updateNow();
        var tokens = getTokens(service, uri);

        // the imported name "HELLO" is highlighted as a function at column 10
        assertThat(tokens).contains(new Token(0, 10, 5, "function"));
        // the token stream is well-formed (groups of 5 ints)
        assertThat(service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).getData().size() % 5).isEqualTo(0);
    }

    @Test
    void noNegativeValuesForGStringWithInterpolatedValues() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
            workflow {
                def x = 1
                println "${x}${x}"
            }
            """);
        service.updateNow();
        var data = service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).getData();

        assertThat(data).allMatch(it -> it >= 0);
    }

    @Test
    void returnNullForFileThatHasNoAst() {
        var service = getScriptService();

        assertThat(getTokens(service, getUri("not-open.nf"))).isNull();
    }

}

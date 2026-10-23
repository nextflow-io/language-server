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

package nextflow.lsp.services.config;

import java.util.ArrayList;
import java.util.List;

import nextflow.lsp.services.SemanticTokensVisitor;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigSemanticTokensTest {

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

    static List<Token> getTokens(ConfigService service, String uri) {
        var st = service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri)));
        return st != null ? decode(st.getData()) : null;
    }

    @Test
    void tokenizeValueExpressionOfConfigAssignment() {
        var service = getConfigService();
        var uri = getUri("nextflow.config");

        open(service, uri, """
            process.clusterOptions = "--account=${params.account}"
            """);
        service.updateNow();
        var tokens = getTokens(service, uri);

        // the GString literal, interpolated variable, and property are highlighted
        assertThat(tokens).containsExactly(
            new Token(0, 26, 10, "string"),
            new Token(0, 38, 6, "variable"),
            new Token(0, 45, 7, "property")
        );
    }

    @Test
    void noTokensForAssignmentKeysOrPlainLiteralValues() {
        var service = getConfigService();
        var uri = getUri("nextflow.config");

        open(service, uri, """
            includeConfig 'extra.config'
            process.cpus = 2
            """);
        service.updateNow();
        var tokens = getTokens(service, uri);

        assertThat(tokens).isEmpty();
    }

    @Test
    void returnNullForFileThatHasNoAst() {
        var service = getConfigService();

        assertThat(getTokens(service, getUri("not-open.config"))).isNull();
    }

}

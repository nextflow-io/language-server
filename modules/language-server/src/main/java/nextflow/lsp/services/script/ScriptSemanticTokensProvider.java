/*
 * Copyright 2024, Seqera Labs
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import nextflow.lsp.ast.ASTUtils;
import nextflow.lsp.services.SemanticTokensProvider;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.Positions;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.script.ast.IncludeVariable;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokenTypes;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptSemanticTokensProvider implements SemanticTokensProvider {

    public static final List<String> TOKEN_TYPES = List.of(
        SemanticTokenTypes.Type,
        SemanticTokenTypes.Function,
        SemanticTokenTypes.Parameter,
        SemanticTokenTypes.Variable
    );

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptSemanticTokensProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public SemanticTokens semanticTokensFull(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while providing semantic tokens");
            return null;
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return null;

        var tokens = ast.getNodes(uri).stream()
            .map(node -> getSemanticToken(node))
            .filter(token -> token != null)
            .sorted((a, b) -> Positions.COMPARATOR.compare(a.position(), b.position()))
            .collect(Collectors.toList());

        var deltaTokens = new ArrayList<SemanticTokenDelta>(tokens.size());
        int line = 0;
        int startChar = 0;
        for( var token : tokens ) {
            var deltaLine = token.position().getLine() - line;
            var deltaStartChar = deltaLine > 0
                ? token.position().getCharacter()
                : token.position().getCharacter() - startChar;
            deltaTokens.add(new SemanticTokenDelta(
                deltaLine,
                deltaStartChar,
                token.length(),
                token.type(),
                token.modifiers()
            ));
            line = token.position().getLine();
            startChar = token.position().getCharacter();
        }

        var data = deltaTokens.stream()
            .flatMap(token -> Stream.of(
                token.deltaLine(),
                token.deltaStartChar(),
                token.length(),
                TOKEN_TYPES.indexOf(token.type()),
                0
            ))
            .collect(Collectors.toList());
            
        return new SemanticTokens(data);
    }

    private SemanticToken getSemanticToken(ASTNode node) {
        if( node.getLineNumber() != node.getLastLineNumber() )
            return null;
        var position = LanguageServerUtils.groovyToLspPosition(node.getLineNumber(), node.getColumnNumber());
        if( position == null )
            return null;
        var length = node.getLastColumnNumber() - node.getColumnNumber();
        var type = getTokenType(node, ast);
        if( type == null )
            return null;
        return new SemanticToken(
            position,
            length,
            type,
            getTokenModifiers(node)
        );
    }

    static String getTokenType(ASTNode node, ScriptAstCache ast) {
        if( node instanceof ClassNode )
            return SemanticTokenTypes.Type;

        if( node instanceof IncludeVariable )
            return null;

        if( node instanceof MethodNode )
            return SemanticTokenTypes.Function;

        if( node instanceof Parameter )
            return SemanticTokenTypes.Parameter;

        if( node instanceof Variable )
            return SemanticTokenTypes.Variable;

        if( node instanceof ClassExpression || node instanceof ConstantExpression )
            return getTokenType(ASTUtils.getDefinition(node, ast), ast);

        return null;
    }

    static List<String> getTokenModifiers(ASTNode node) {
        return Collections.emptyList();
    }
}

record SemanticToken(
    Position position,
    int length,
    String type,
    List<String> modifiers
) {}

record SemanticTokenDelta(
    int deltaLine,
    int deltaStartChar,
    int length,
    String type,
    List<String> modifiers
) {}

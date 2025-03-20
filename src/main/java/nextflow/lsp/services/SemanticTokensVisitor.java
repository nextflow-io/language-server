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
package nextflow.lsp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import nextflow.lsp.util.Positions;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.script.dsl.Constant;
import nextflow.script.parser.TokenPosition;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokenTypes;

import static nextflow.script.ast.ASTUtils.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class SemanticTokensVisitor extends CodeVisitorSupport {

    public static final List<String> TOKEN_TYPES = List.of(
        SemanticTokenTypes.EnumMember,
        SemanticTokenTypes.Function,
        SemanticTokenTypes.Number,
        SemanticTokenTypes.Parameter,
        SemanticTokenTypes.Property,
        SemanticTokenTypes.String,
        SemanticTokenTypes.Type,
        SemanticTokenTypes.Variable
    );

    private List<SemanticToken> tokens = new ArrayList<SemanticToken>();

    public void append(ASTNode node, String type) {
        var length = node.getLastColumnNumber() - node.getColumnNumber();
        append(node.getLineNumber() - 1, node.getColumnNumber() - 1, length, type);
    }

    public void append(TokenPosition start, String text, String type) {
        append(start.line(), start.character(), text.length(), type);
    }

    public void append(int line, int character, int length, String type) {
        tokens.add(new SemanticToken(
            new Position(line, character),
            length,
            type
        ));
    }

    public SemanticTokens getTokens() {
        tokens.sort((a, b) -> Positions.COMPARATOR.compare(a.position(), b.position()));

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
                token.type()
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
            .toList();

        return new SemanticTokens(data);
    }

    // expressions

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        if( !node.isImplicitThis() )
            visit(node.getObjectExpression());
        append(node.getMethod(), SemanticTokenTypes.Function);
        visit(node.getArguments());
    }

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        if( node.getParameters() != null )
            visitParameters(node.getParameters());
        visit(node.getCode());
    }

    public void visitParameters(Parameter[] parameters) {
        for( int i = 0; i < parameters.length; i++ ) {
            var param = parameters[i];
            append((TokenPosition) param.getNodeMetaData("_START_NAME"), param.getName(), SemanticTokenTypes.Parameter);
            if( param.hasInitialExpression() )
                visit(param.getInitialExpression());
        }
    }

    @Override
    public void visitMapExpression(MapExpression node) {
        if( node instanceof NamedArgumentListExpression )
            visitNamedArgs(node.getMapEntryExpressions());
        else
            super.visitMapExpression(node);
    }

    protected void visitNamedArgs(List<MapEntryExpression> args) {
        for( var namedArg : args ) {
            append(namedArg.getKeyExpression(), SemanticTokenTypes.Parameter);
            visit(namedArg.getValueExpression());
        }
    }

    @Override
    public void visitConstantExpression(ConstantExpression node) {
        if( !inGString )
            return;
        var value = node.getValue();
        if( value instanceof Number )
            append(node, SemanticTokenTypes.Number);
        else if( value instanceof String )
            append(node, SemanticTokenTypes.String);
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        var variable = node.getAccessedVariable();
        var mn = asMethodVariable(variable);
        if( mn != null && !findAnnotation(mn, Constant.class).isPresent() )
            append(node, SemanticTokenTypes.Function);
        else if( !(variable instanceof DynamicVariable) )
            append(node, SemanticTokenTypes.Variable);
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        visit(node.getObjectExpression());
        append(node.getProperty(), SemanticTokenTypes.Property);
    }

    private boolean inGString = false;

    @Override
    public void visitGStringExpression(GStringExpression node) {
        var igs = inGString;
        inGString = true;
        super.visitGStringExpression(node);
        inGString = igs;
    }

    private static record SemanticToken(
        Position position,
        int length,
        String type
    ) {}

    private static record SemanticTokenDelta(
        int deltaLine,
        int deltaStartChar,
        int length,
        String type
    ) {}

}

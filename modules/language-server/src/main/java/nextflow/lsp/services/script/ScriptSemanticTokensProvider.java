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
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokenTypes;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import static nextflow.script.ast.ASTHelpers.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptSemanticTokensProvider implements SemanticTokensProvider {

    public static final List<String> TOKEN_TYPES = List.of(
        SemanticTokenTypes.EnumMember,
        SemanticTokenTypes.Function,
        SemanticTokenTypes.Parameter,
        SemanticTokenTypes.Property,
        SemanticTokenTypes.Type,
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

        var sourceUnit = ast.getSourceUnit(uri);
        var visitor = new ScriptSemanticTokensVisitor(sourceUnit, ast);
        visitor.visit();

        var tokens = visitor.getTokens();
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
            .collect(Collectors.toList());
            
        return new SemanticTokens(data);
    }

}


record SemanticToken(
    Position position,
    int length,
    String type
) {}


record SemanticTokenDelta(
    int deltaLine,
    int deltaStartChar,
    int length,
    String type
) {}


class ScriptSemanticTokensVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ScriptAstCache ast;

    private List<SemanticToken> tokens = new ArrayList<SemanticToken>();

    public ScriptSemanticTokensVisitor(SourceUnit sourceUnit, ScriptAstCache ast) {
        this.sourceUnit = sourceUnit;
        this.ast = ast;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ScriptNode sn )
            visit(sn);
    }

    protected void addToken(ASTNode node, String type) {
        var position = LanguageServerUtils.groovyToLspPosition(node.getLineNumber(), node.getColumnNumber());
        var length = node.getLastColumnNumber() - node.getColumnNumber();
        tokens.add(new SemanticToken(
            position,
            length,
            type
        ));
    }

    public List<SemanticToken> getTokens() {
        return tokens;
    }

    // script declarations

    // TODO: highlight include name and alias as definitions?
    // @Override
    // public void visitInclude(IncludeNode node) {
    // }

    @Override
    public void visitWorkflow(WorkflowNode node) {
        if( node.takes instanceof BlockStatement block )
            visitWorkflowTakes(block.getStatements());

        visit(node.main);

        if( node.emits instanceof BlockStatement block )
            visitWorkflowEmits(block.getStatements());

        visit(node.publishers);
    }

    protected void visitWorkflowTakes(List<Statement> takes) {
        for( var stmt : takes ) {
            var ve = (VariableExpression) asVarX(stmt);
            addToken(ve, SemanticTokenTypes.Parameter);
        }
    }

    protected void visitWorkflowEmits(List<Statement> emits) {
        for( var stmt : emits ) {
            var es = (ExpressionStatement)stmt;
            var emit = es.getExpression();
            if( emit instanceof AssignmentExpression assign ) {
                var ve = (VariableExpression)assign.getLeftExpression();
                addToken(ve, SemanticTokenTypes.Parameter);
                visit(assign.getRightExpression());
            }
            else if( emit instanceof VariableExpression ve ) {
                if( emits.size() == 1 )
                    visit(emit);
                else
                    addToken(ve, SemanticTokenTypes.Parameter);
            }
            else {
                visit(stmt);
            }
        }
    }

    @Override
    public void visitProcess(ProcessNode node) {
        visit(node.directives);
        visit(node.inputs);
        visit(node.outputs);
        visit(node.when);
        // TODO: highlight embedded scripts
        visit(node.exec);
        visit(node.stub);
    }

    @Override
    public void visitFunction(FunctionNode node) {
        visitParameters(node.getParameters());
        visit(node.getCode());
    }

    public void visitParameters(Parameter[] parameters) {
        for( int i = 0; i < parameters.length; i++ ) {
            var param = parameters[i];
            // TODO: highlight param name
            // addToken(param.getName(), SemanticTokenTypes.Parameter);
            visitTypeName(param.getType());
            if( param.hasInitialExpression() )
                visit(param.getInitialExpression());
        }
    }

    @Override
    public void visitEnum(ClassNode node) {
        for( var fn : node.getFields() )
            addToken(fn, SemanticTokenTypes.EnumMember);
    }

    @Override
    public void visitOutput(OutputNode node) {
        visitOutputBody(node.body);
    }

    protected void visitOutputBody(Statement body) {
        asBlockStatements(body).forEach((stmt) -> {
            var call = asMethodCallX(stmt);
            if( call == null )
                return;

            var code = asDslBlock(call, 1);
            if( code != null ) {
                addToken(call.getMethod(), SemanticTokenTypes.Parameter);
                visit(code);
            }
        });
    }

    // expressions

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        if( !node.isImplicitThis() )
            visit(node.getObjectExpression());
        addToken(node.getMethod(), SemanticTokenTypes.Function);
        visit(node.getArguments());
    }

    @Override
    public void visitBinaryExpression(BinaryExpression node) {
        visit(node.getLeftExpression());

        // TODO: fix division + comment parsed as regex
        // addToken(node.getOperation(), SemanticTokenTypes.Operator);

        visit(node.getRightExpression());
    }

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        if( node.getParameters() != null )
            visitParameters(node.getParameters());
        visit(node.getCode());
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
            addToken(namedArg.getKeyExpression(), SemanticTokenTypes.Parameter);
            visit(namedArg.getValueExpression());
        }
    }

    @Override
    public void visitClassExpression(ClassExpression node) {
        visitTypeName(node.getType());
    }

    protected void visitTypeName(ClassNode type) {
        // addToken(type, SemanticTokenTypes.Type);
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        if( !(node.getAccessedVariable() instanceof DynamicVariable) )
            addToken(node, SemanticTokenTypes.Variable);
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        visit(node.getObjectExpression());
        addToken(node.getProperty(), SemanticTokenTypes.Property);
    }

}

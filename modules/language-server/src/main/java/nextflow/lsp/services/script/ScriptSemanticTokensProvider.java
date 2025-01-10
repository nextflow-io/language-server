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
import java.util.List;

import nextflow.lsp.services.SemanticTokensProvider;
import nextflow.lsp.services.SemanticTokensVisitor;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ParamNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokenTypes;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import static nextflow.script.ast.ASTHelpers.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptSemanticTokensProvider implements SemanticTokensProvider {

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
        var visitor = new Visitor(sourceUnit);
        visitor.visit();
        return visitor.getTokens();
    }

    private static class Visitor extends ScriptVisitorSupport {

        private SourceUnit sourceUnit;

        private SemanticTokensVisitor tok;

        public Visitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit;
            this.tok = new SemanticTokensVisitor();
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

        public SemanticTokens getTokens() {
            return tok.getTokens();
        }

        @Override
        public void visitFeatureFlag(FeatureFlagNode node) {
            tok.visit(node.value);
        }

        @Override
        public void visitInclude(IncludeNode node) {
            for( var module : node.modules ) {
                tok.append(module.getNodeMetaData("_START_NAME"), module.name, SemanticTokenTypes.Function);
                if( module.alias != null )
                    tok.append(module.getNodeMetaData("_START_ALIAS"), module.alias, SemanticTokenTypes.Function);
            }
        }

        @Override
        public void visitParam(ParamNode node) {
            tok.visit(node.target);
            tok.visit(node.value);
        }

        @Override
        public void visitWorkflow(WorkflowNode node) {
            if( node.takes instanceof BlockStatement block )
                visitWorkflowTakes(block.getStatements());

            tok.visit(node.main);

            if( node.emits instanceof BlockStatement block )
                visitWorkflowEmits(block.getStatements());

            tok.visit(node.publishers);
        }

        protected void visitWorkflowTakes(List<Statement> takes) {
            for( var stmt : takes ) {
                var ve = (VariableExpression) asVarX(stmt);
                tok.append(ve, SemanticTokenTypes.Parameter);
            }
        }

        protected void visitWorkflowEmits(List<Statement> emits) {
            for( var stmt : emits ) {
                var es = (ExpressionStatement)stmt;
                var emit = es.getExpression();
                if( emit instanceof AssignmentExpression assign ) {
                    var ve = (VariableExpression)assign.getLeftExpression();
                    tok.append(ve, SemanticTokenTypes.Parameter);
                    tok.visit(assign.getRightExpression());
                }
                else if( emit instanceof VariableExpression ve ) {
                    if( emits.size() == 1 )
                        tok.visit(emit);
                    else
                        tok.append(ve, SemanticTokenTypes.Parameter);
                }
                else {
                    tok.visit(stmt);
                }
            }
        }

        @Override
        public void visitProcess(ProcessNode node) {
            tok.visit(node.directives);
            tok.visit(node.inputs);
            tok.visit(node.outputs);
            tok.visit(node.when);
            tok.visit(node.exec);
            tok.visit(node.stub);
        }

        @Override
        public void visitFunction(FunctionNode node) {
            tok.visitParameters(node.getParameters());
            tok.visit(node.getCode());
        }

        @Override
        public void visitEnum(ClassNode node) {
            for( var fn : node.getFields() )
                tok.append(fn, SemanticTokenTypes.EnumMember);
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
                    tok.append(call.getMethod(), SemanticTokenTypes.Parameter);
                    tok.visit(code);
                }
            });
        }

    }

}

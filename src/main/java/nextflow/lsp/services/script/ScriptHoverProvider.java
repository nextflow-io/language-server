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

import java.net.URI;

import nextflow.lsp.ast.ASTNodeStringUtils;
import nextflow.lsp.ast.LanguageServerASTUtils;
import nextflow.lsp.services.HoverProvider;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * Provide hints for an expression or statement when hovered
 * based on available definitions and Groovydoc comments.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptHoverProvider implements HoverProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptHoverProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public Hover hover(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing hover hint");
            return null;
        }

        var uri = URI.create(textDocument.getUri());
        var nodeStack = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if( nodeStack.isEmpty() )
            return null;

        var offsetNode = nodeStack.get(0);
        var defNode = LanguageServerASTUtils.getDefinition(offsetNode, ast);
        if( defNode instanceof VariableExpression ve && ve.isDynamicTyped() )
            ve.setType(LanguageServerASTUtils.getType(ve, ast));

        var builder = new StringBuilder();

        var label = ASTNodeStringUtils.getLabel(defNode);
        if( label != null ) {
            builder.append("```nextflow\n");
            builder.append(label);
            builder.append("\n```");
        }

        var documentation = ASTNodeStringUtils.getDocumentation(defNode);
        if( documentation != null ) {
            builder.append("\n\n---\n\n");
            builder.append(documentation);
        }

        if( Logger.isDebugEnabled() ) {
            builder.append("\n\n---\n\n");
            builder.append("```\n");
            for( int i = 0; i < nodeStack.size(); i++ ) {
                var node = nodeStack.get(nodeStack.size() - 1 - i);
                builder.append("  ".repeat(i));
                builder.append(node.getClass().getSimpleName());
                builder.append(String.format("(%d:%d-%d:%d)", node.getLineNumber(), node.getColumnNumber(), node.getLastLineNumber(), node.getLastColumnNumber() - 1));
                var scope =
                    node instanceof BlockStatement block ? block.getVariableScope() :
                    node instanceof MethodNode mn ? mn.getVariableScope() :
                    null;
                if( scope != null && scope.isClassScope() ) {
                    builder.append(" [");
                    builder.append(scope.getClassScope().getNameWithoutPackage());
                    builder.append(']');
                }
                builder.append('\n');
            }
            builder.append("\n```");
        }

        var value = builder.toString();
        if( value.isEmpty() )
            return null;
        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, value));
    }

}

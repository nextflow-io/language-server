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
package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeStringUtils
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.util.Logger
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Provide hints for an expression or statement when hovered
 * based on available definitions and Groovydoc comments.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptHoverProvider implements HoverProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptHoverProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    Hover hover(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing hover hint")
            return null
        }

        final uri = URI.create(textDocument.getUri())
        final nodeTree = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !nodeTree )
            return null

        final offsetNode = nodeTree.first()
        final defNode = ASTUtils.getDefinition(offsetNode, false, ast)

        final builder = new StringBuilder()

        final label = ASTNodeStringUtils.getLabel(defNode, ast)
        if( label != null ) {
            builder.append('```groovy\n')
            builder.append(label)
            builder.append('\n```')
        }

        final documentation = ASTNodeStringUtils.getDocumentation(defNode)
        if( documentation != null ) {
            builder.append('\n\n---\n\n')
            builder.append(documentation)
        }

        if( Logger.isDebugEnabled() ) {
            builder.append('\n\n---\n\n')
            builder.append('```\n')
            nodeTree.asReversed().eachWithIndex { node, i ->
                builder.append('  ' * i)
                builder.append(node.class.simpleName)
                builder.append("(${node.getLineNumber()}:${node.getColumnNumber()}-${node.getLastLineNumber()}:${node.getLastColumnNumber()-1})")
                if( node instanceof Statement && node.statementLabels ) {
                    builder.append(': ')
                    builder.append(node.statementLabels.join(', '))
                }
                final scope =
                    node instanceof BlockStatement ? node.variableScope :
                    node instanceof MethodNode ? node.variableScope :
                    null
                if( scope && scope.isClassScope() ) {
                    builder.append(' [')
                    builder.append(scope.getClassScope().getNameWithoutPackage())
                    builder.append(']')
                }
                builder.append('\n')
            }
            builder.append('\n```')
        }

        final value = builder.toString()
        if( !value )
            return null
        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, value))
    }

}

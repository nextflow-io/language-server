package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeStringUtils
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.util.Logger
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.OperatorNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.Variable
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
        final definitionNode = ASTUtils.getDefinition(offsetNode, false, ast)
        final label = definitionNode ? getHoverLabel(definitionNode) : null
        final detail = ASTNodeStringUtils.getDocumentation(definitionNode)

        final builder = new StringBuilder()

        if( label != null ) {
            builder.append('```groovy\n')
            builder.append(label)
            builder.append('\n```')
        }

        if( detail != null ) {
            builder.append('\n\n---\n\n')
            builder.append(detail)
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
                builder.append('\n')
            }
            builder.append('\n```')
        }

        final value = builder.toString()
        if( !value )
            return null
        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, value))
    }

    private String getHoverLabel(ASTNode node) {
        if( node instanceof FunctionNode ) {
            return ASTNodeStringUtils.toString(node, ast)
        }
        else if( node instanceof OperatorNode ) {
            return ASTNodeStringUtils.toString(node, ast)
        }
        else if( node instanceof ProcessNode ) {
            return ASTNodeStringUtils.toString(node, ast)
        }
        else if( node instanceof WorkflowNode ) {
            return ASTNodeStringUtils.toString(node, ast)
        }
        else if( node instanceof Variable ) {
            return ASTNodeStringUtils.toString(node, ast)
        }
        else {
            return null
        }
    }

}

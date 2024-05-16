package nextflow.lsp.services

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import nextflow.lsp.compiler.GroovydocUtils
import nextflow.lsp.util.ASTNodeToStringUtils
import nextflow.lsp.util.Logger
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
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

    private ASTNodeCache ast

    ScriptHoverProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    @Override
    Hover provideHover(TextDocumentIdentifier textDocument, Position position) {
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
        final content = definitionNode ? getHoverContent(definitionNode) : null
        final documentation = definitionNode instanceof AnnotatedNode
            ? GroovydocUtils.groovydocToMarkdownDescription(definitionNode.getGroovydoc())
            : null

        final builder = new StringBuilder()

        if( Logger.isDebugEnabled() ) {
            builder.append('```\n')
            nodeTree.reverse().eachWithIndex { node, i ->
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

        if( content != null ) {
            builder.append('\n\n---\n\n')
            builder.append('```groovy\n')
            builder.append(content)
            builder.append('\n```')
        }

        if( documentation != null ) {
            builder.append('\n\n---\n\n')
            builder.append(documentation)
        }

        final value = builder.toString()
        if( !value )
            return null

        final contents = new MarkupContent()
        contents.setKind(MarkupKind.MARKDOWN)
        contents.setValue(value)
        final hover = new Hover()
        hover.setContents(contents)
        return hover
    }

    private String getHoverContent(ASTNode node) {
        if( node instanceof FunctionNode ) {
            return ASTNodeToStringUtils.functionToString(node, ast)
        }
        else if( node instanceof ProcessNode ) {
            return ASTNodeToStringUtils.processToString(node, ast)
        }
        else if( node instanceof WorkflowNode ) {
            return ASTNodeToStringUtils.workflowToString(node, ast)
        }
        else {
            return null
        }
    }

}

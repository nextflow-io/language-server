package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigAssignmentNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.util.Logger
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.stmt.Statement
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Provide hints for an expression or statement when hovered
 * based on available definitions.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigHoverProvider implements HoverProvider {

    private static Logger log = Logger.instance

    private ASTNodeCache ast

    ConfigHoverProvider(ASTNodeCache ast) {
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

        final content = getHoverContent(nodeTree)

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
            builder.append(content)
            builder.append('\n')
        }

        final value = builder.toString()
        if( !value )
            return null

        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, value))
    }

    String getHoverContent(List<ASTNode> nodeTree) {
        final offsetNode = nodeTree.first()
        if( offsetNode instanceof ConfigAssignmentNode ) {
            final names = []
            for( final node : nodeTree.asReversed() )
                if( node instanceof ConfigBlockNode )
                    names << node.name
            names.addAll(offsetNode.names)

            final fqName = names.join('.')
            final option = ConfigDefs.OPTIONS.find { name, description -> name == fqName }
            if( option ) {
                final description = option[1].stripIndent(true).trim()
                final builder = new StringBuilder()
                builder.append("`${fqName}`")
                builder.append('\n\n')
                description.eachLine { line ->
                    builder.append(line)
                    builder.append('\n')
                }
                return builder.toString()
            }
            else {
                return "`${fqName}`"
            }
        }
        if( offsetNode instanceof ConfigBlockNode ) {
            final names = []
            for( final node : nodeTree.asReversed() )
                if( node instanceof ConfigBlockNode )
                    names << node.name

            final fqName = names.join('.')
            final scope = ConfigDefs.SCOPES.find { name, description -> name == fqName }
            if( scope ) {
                final description = scope[1].stripIndent(true).trim()
                final builder = new StringBuilder()
                description.eachLine { line ->
                    builder.append(line)
                    builder.append('\n')
                }
                return builder.toString()
            }
            else {
                return "`${fqName}`"
            }
        }

        return null
    }

}

package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigSchema
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

        if( content != null ) {
            builder.append(content)
            builder.append('\n')
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
                if( node instanceof ConfigBlockNode ) {
                    final scope = ConfigSchema.SCOPES[node.name]
                    if( scope ) {
                        builder.append(' [')
                        builder.append(scope.class.simpleName)
                        builder.append(']')
                    }
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

    String getHoverContent(List<ASTNode> nodeTree) {
        final offsetNode = nodeTree.first()
        if( offsetNode instanceof ConfigAssignmentNode ) {
            final names = []
            for( final node : nodeTree.asReversed() ) {
                if( node instanceof ConfigBlockNode && node.kind == null )
                    names << node.name
            }
            names.addAll(offsetNode.names)

            if( names.first() == 'profiles' ) {
                if( names ) names.pop()
                if( names ) names.pop()
            }

            final fqName = names.join('.')
            final option = ConfigSchema.OPTIONS[fqName]
            if( option ) {
                final description = option.stripIndent(true).trim()
                final builder = new StringBuilder()
                builder.append("`${fqName}`")
                builder.append('\n\n')
                description.eachLine { line ->
                    builder.append(line)
                    builder.append('\n')
                }
                return builder.toString()
            }
            else if( fqName && Logger.isDebugEnabled() ) {
                return "`${fqName}`"
            }
        }

        if( offsetNode instanceof ConfigBlockNode ) {
            final names = []
            for( final node : nodeTree.asReversed() ) {
                if( node instanceof ConfigBlockNode && node.kind == null )
                    names << node.name
            }

            if( names.first() == 'profiles' ) {
                if( names ) names.pop()
                if( names ) names.pop()
                if( !names )
                    return null
            }

            final fqName = names.join('.')
            final scope = ConfigSchema.SCOPES[fqName]
            if( scope ) {
                final description = scope.description().stripIndent(true).trim()
                final builder = new StringBuilder()
                description.eachLine { line ->
                    builder.append(line)
                    builder.append('\n')
                }
                return builder.toString()
            }
            else if( fqName && Logger.isDebugEnabled() ) {
                return "`${fqName}`"
            }
        }

        return null
    }

}

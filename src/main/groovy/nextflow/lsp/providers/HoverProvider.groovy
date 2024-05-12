package nextflow.lsp.providers

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import nextflow.lsp.compiler.GroovydocUtils
import nextflow.lsp.util.ASTNodeToStringUtils
import nextflow.lsp.util.Logger
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
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
class HoverProvider {

    private static Logger log = Logger.instance

    private ASTNodeCache ast

    HoverProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    Hover provideHover(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing hover hint")
            return null
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( offsetNode == null )
            return null

        final definitionNode = ASTUtils.getDefinition(offsetNode, false, ast)
        if( definitionNode == null )
            return null

        final content = getHoverContent(definitionNode)
        if( content == null )
            return null

        final documentation = definitionNode instanceof AnnotatedNode
            ? GroovydocUtils.groovydocToMarkdownDescription(definitionNode.getGroovydoc())
            : null

        final builder = new StringBuilder()
        builder.append('```groovy\n')
        builder.append(content)
        builder.append('\n```')
        if( documentation != null ) {
            builder.append('\n\n---\n\n')
            builder.append(documentation)
        }

        final contents = new MarkupContent()
        contents.setKind(MarkupKind.MARKDOWN)
        contents.setValue(builder.toString())
        final hover = new Hover()
        hover.setContents(contents)
        return hover
    }

    private String getHoverContent(ASTNode node) {
        if( node instanceof ClassNode ) {
            return ASTNodeToStringUtils.classToString(node, ast)
        }
        else if( node instanceof MethodNode ) {
            return ASTNodeToStringUtils.methodToString(node, ast)
        }
        else if( node instanceof Variable ) {
            return ASTNodeToStringUtils.variableToString(node, ast)
        }
        else {
            log.debug("could not determine type of definition node: ${node}")
            return null
        }
    }

}

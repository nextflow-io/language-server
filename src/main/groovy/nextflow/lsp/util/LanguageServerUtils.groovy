package nextflow.lsp.util

import groovy.transform.CompileStatic
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.syntax.SyntaxException
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind

/**
 * Utility methods for mapping compiler data structures
 * to LSP data structures.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class LanguageServerUtils {

    static Position createGroovyPosition(int groovyLine, int groovyColumn) {
        int lspLine = groovyLine > 0 ? groovyLine - 1 : groovyLine
        int lspColumn = groovyColumn > 0 ? groovyColumn - 1 : groovyColumn
        return new Position(lspLine, lspColumn)
    }

    static Range syntaxExceptionToRange(SyntaxException exception) {
        return new Range(
                createGroovyPosition(exception.getStartLine(), exception.getStartColumn()),
                createGroovyPosition(exception.getEndLine(), exception.getEndColumn()))
    }

    static Range astNodeToRange(ASTNode node) {
        return new Range(
                createGroovyPosition(node.getLineNumber(), node.getColumnNumber()),
                createGroovyPosition(node.getLastLineNumber(), node.getLastColumnNumber()))
    }

    static CompletionItemKind astNodeToCompletionItemKind(ASTNode node) {
        if( node instanceof ClassNode ) {
            if( node.isEnum() )
                return CompletionItemKind.Enum
            else
                return CompletionItemKind.Class
        }
        else if( node instanceof FunctionNode || node instanceof ProcessNode || node instanceof WorkflowNode ) {
            return CompletionItemKind.Method
        }
        else if( node instanceof Variable ) {
            if( node instanceof FieldNode || node instanceof PropertyNode )
                return CompletionItemKind.Field
            else
                return CompletionItemKind.Variable
        }
        return CompletionItemKind.Property
    }

    static SymbolKind astNodeToSymbolKind(ASTNode node) {
        if( node instanceof ClassNode ) {
            if( node.isEnum() )
                return SymbolKind.Enum
            else
                return SymbolKind.Class
        }
        else if( node instanceof FunctionNode || node instanceof ProcessNode || node instanceof WorkflowNode ) {
            return SymbolKind.Method
        }
        else if( node instanceof Variable ) {
            if( node instanceof FieldNode || node instanceof PropertyNode )
                return SymbolKind.Field
            else
                return SymbolKind.Variable
        }
        return SymbolKind.Property
    }

    static Location astNodeToLocation(ASTNode node, URI uri) {
        return new Location(uri.toString(), astNodeToRange(node))
    }

    static SymbolInformation astNodeToSymbolInformation(ClassNode node, URI uri) {
        return new SymbolInformation(
                node.getName(),
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                null)
    }

    static SymbolInformation astNodeToSymbolInformation(FunctionNode node, URI uri) {
        return new SymbolInformation(
                node.getName(),
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                null)
    }

    static SymbolInformation astNodeToSymbolInformation(ProcessNode node, URI uri) {
        return new SymbolInformation(
                node.getName(),
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                null)
    }

    static SymbolInformation astNodeToSymbolInformation(WorkflowNode node, URI uri) {
        return new SymbolInformation(
                node.getName() ?: '<entry>',
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                null)
    }

    static SymbolInformation astNodeToSymbolInformation(Variable node, URI uri, String parentName) {
        if( node !instanceof ASTNode ) {
            return null
        }
        return new SymbolInformation(
                node.getName(),
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                parentName)
    }

}

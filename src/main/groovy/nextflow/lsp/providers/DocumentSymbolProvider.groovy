package nextflow.lsp.providers

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Positions
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Provide the set of document symbols for a source file,
 * which can be used for efficient lookup and traversal.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class DocumentSymbolProvider {

    private static Logger log = Logger.instance

    private ASTNodeCache ast

    DocumentSymbolProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    List<Either<SymbolInformation, DocumentSymbol>> provideDocumentSymbols(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding document symbols")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        final nodes = ast.getNodes(uri)

        final List<Either<SymbolInformation, DocumentSymbol>> result = []
        for( final node : nodes ) {
            if( !(node instanceof ClassNode || node instanceof MethodNode || node instanceof FieldNode || node instanceof PropertyNode) )
                continue

            final symbolInfo = getSymbolInformation(node, uri)
            if( symbolInfo == null )
                continue
            if( !Positions.valid(symbolInfo.location.range.start) )
                continue

            result << Either.<SymbolInformation, DocumentSymbol>forLeft(symbolInfo)
        }

        return result
    }

    private SymbolInformation getSymbolInformation(ASTNode node, URI uri) {
        if( node instanceof ClassNode )
            return LanguageServerUtils.astNodeToSymbolInformation(node, uri, null)

        final classNode = (ClassNode) ASTUtils.getEnclosingNodeOfType(node, ClassNode.class, ast)
        if( node instanceof MethodNode ) {
            return LanguageServerUtils.astNodeToSymbolInformation(node, uri, classNode.getName())
        }
        else if( node instanceof PropertyNode ) {
            return LanguageServerUtils.astNodeToSymbolInformation(node, uri, classNode.getName())
        }
        else if( node instanceof FieldNode ) {
            return LanguageServerUtils.astNodeToSymbolInformation(node, uri, classNode.getName())
        }
        else {
            log.error("could not determine type of definition node: ${node}")
            return null
        }
    }

}

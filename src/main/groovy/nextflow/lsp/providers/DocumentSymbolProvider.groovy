package nextflow.lsp.providers

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Positions
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
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
            if( !(node instanceof FunctionNode || node instanceof ProcessNode || node instanceof WorkflowNode) )
                continue

            final symbolInfo = getSymbolInformation(node, uri)
            if( symbolInfo == null )
                continue

            result << Either.<SymbolInformation, DocumentSymbol>forLeft(symbolInfo)
        }

        return result
    }

    private SymbolInformation getSymbolInformation(ASTNode node, URI uri) {
        if( node instanceof FunctionNode ) {
            return LanguageServerUtils.astNodeToSymbolInformation(node, uri)
        }
        else if( node instanceof ProcessNode ) {
            return LanguageServerUtils.astNodeToSymbolInformation(node, uri)
        }
        else if( node instanceof WorkflowNode ) {
            return LanguageServerUtils.astNodeToSymbolInformation(node, uri)
        }
        else {
            log.error("could not determine type of definition node: ${node}")
            return null
        }
    }

}

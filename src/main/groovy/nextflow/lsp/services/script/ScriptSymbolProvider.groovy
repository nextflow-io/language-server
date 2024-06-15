package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.services.SymbolProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
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
class ScriptSymbolProvider implements SymbolProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptSymbolProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    List<Either<SymbolInformation, DocumentSymbol>> documentSymbol(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding document symbols")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        if( !ast.hasAST(uri) )
            return Collections.emptyList()

        final definitions = ast.getDefinitions(uri)
        final List<Either<SymbolInformation, DocumentSymbol>> result = []
        for( final node : definitions ) {
            if( node.getLineNumber() < 0 )
                continue
            final symbolInfo = getSymbolInformation(node, uri)
            result << Either.<SymbolInformation, DocumentSymbol>forLeft(symbolInfo)
        }

        return result
    }

    @Override
    List<? extends SymbolInformation> symbol(String query) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding workspace symbols")
            return Collections.emptyList()
        }

        final lowerCaseQuery = query.toLowerCase()
        final definitions = ast.getDefinitions()
        final List<SymbolInformation> result = []
        for( final node : definitions ) {
            String name = null
            if( node instanceof FunctionNode )
                name = node.name
            else if( node instanceof ProcessNode )
                name = node.name
            else if( node instanceof WorkflowNode )
                name = node.name ?: '<entry>'

            if( !name || !name.toLowerCase().contains(lowerCaseQuery) )
                continue

            final uri = ast.getURI(node)
            final symbolInfo = getSymbolInformation(node, uri)
            if( symbolInfo == null )
                continue

            result << symbolInfo
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
            return null
        }
    }

}

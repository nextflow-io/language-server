package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.ReferenceProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Get the locations of all references of a symbol.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptReferenceProvider implements ReferenceProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptReferenceProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    List<? extends Location> references(TextDocumentIdentifier textDocument, Position position, boolean includeDeclaration) {
        if( ast == null ) {
            log.error("ast cache is empty while providing hover hint")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !offsetNode )
            return Collections.emptyList()

        final references = ASTUtils.getReferences(offsetNode, ast, includeDeclaration)
        final List<Location> result = []
        for( final refNode : references ) {
            final refUri = ast.getURI(refNode)
            final location = LanguageServerUtils.astNodeToLocation(refNode, refUri)
            if( location )
                result.add(location)
        }

        return result
    }

}

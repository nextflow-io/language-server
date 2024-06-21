package nextflow.lsp.services

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

interface ReferenceProvider {

    List<? extends Location> references(TextDocumentIdentifier textDocument, Position position, boolean includeDeclaration)

}

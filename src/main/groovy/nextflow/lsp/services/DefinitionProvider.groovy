package nextflow.lsp.services

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

interface DefinitionProvider {

    Either<List<? extends Location>, List<? extends LocationLink>> definition(TextDocumentIdentifier textDocument, Position position)

}

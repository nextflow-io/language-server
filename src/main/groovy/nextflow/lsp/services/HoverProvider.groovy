package nextflow.lsp.services

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

interface HoverProvider {

    Hover provideHover(TextDocumentIdentifier textDocument, Position position)

}

package nextflow.lsp.services

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

interface DocumentSymbolProvider {

    List<Either<SymbolInformation, DocumentSymbol>> provideDocumentSymbols(TextDocumentIdentifier textDocument)

}

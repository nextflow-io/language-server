package nextflow.lsp.services

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

interface SymbolProvider {

    List<Either<SymbolInformation, DocumentSymbol>> provideDocumentSymbols(TextDocumentIdentifier textDocument)

    List<? extends SymbolInformation> provideWorkspaceSymbols(String query)

}

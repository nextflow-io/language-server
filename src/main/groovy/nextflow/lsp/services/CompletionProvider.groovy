package nextflow.lsp.services

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

interface CompletionProvider {

    /**
     * Get a list of completions for a given completion context. An
     * incomplete list may be returned if the full list exceeds the
     * maximum size.
     *
     * @param textDocument
     * @param position
     */
    Either<List<CompletionItem>, CompletionList> provideCompletion(TextDocumentIdentifier textDocument, Position position)

}

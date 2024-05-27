package nextflow.lsp.services

import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

interface FormattingProvider {

    List<? extends TextEdit> formatting(TextDocumentIdentifier textDocument, FormattingOptions options)

}

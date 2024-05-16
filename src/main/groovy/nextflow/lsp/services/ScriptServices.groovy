package nextflow.lsp.services

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.CompilationCache
import nextflow.lsp.compiler.ScriptCompilationCache

/**
 * Implementation of language services for Nextflow scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptServices extends AbstractServices {

    @Override
    protected CompilationCache getCompilationCache() {
        ScriptCompilationCache.create()
    }

    @Override
    protected CompletionProvider getCompletionProvider(ASTNodeCache astCache) {
        new ScriptCompletionProvider(astCache)
    }

    @Override
    protected SymbolProvider getSymbolProvider(ASTNodeCache astCache) {
        new ScriptSymbolProvider(astCache)
    }

    @Override
    protected HoverProvider getHoverProvider(ASTNodeCache astCache) {
        new ScriptHoverProvider(astCache)
    }

}

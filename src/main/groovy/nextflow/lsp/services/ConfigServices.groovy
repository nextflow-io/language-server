package nextflow.lsp.services

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.CompilationCache
import nextflow.lsp.compiler.ConfigCompilationCache

/**
 * Implementation of language services for Nextflow scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigServices extends AbstractServices {

    @Override
    protected CompilationCache getCompilationCache() {
        ConfigCompilationCache.create()
    }

    @Override
    protected CompletionProvider getCompletionProvider(ASTNodeCache astCache) {
        new ConfigCompletionProvider(astCache)
    }

    @Override
    protected HoverProvider getHoverProvider(ASTNodeCache astCache) {
        new ConfigHoverProvider(astCache)
    }

}

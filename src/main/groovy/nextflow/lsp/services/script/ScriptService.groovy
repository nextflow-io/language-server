package nextflow.lsp.services.script

import java.nio.file.Path

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.CompilationCache
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.services.LanguageService
import nextflow.lsp.services.SymbolProvider
import nextflow.script.v2.ScriptParserPluginFactory
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

/**
 * Implementation of language services for Nextflow scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptService extends LanguageService {

    private static final String FILE_EXTENSION = '.nf'

    @Override
    boolean matchesFile(String uri) {
        uri.endsWith(FILE_EXTENSION)
    }

    @Override
    protected CompilationCache getCompiler() {
        final config = createConfiguration()
        final classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true)
        return new CompilationCache(FILE_EXTENSION, config, classLoader)
    }

    protected CompilerConfiguration createConfiguration() {
        final importCustomizer = new ImportCustomizer()
        importCustomizer.addImports( java.nio.file.Path.name )
        // Channel
        // Duration
        // MemoryUnit

        final config = new CompilerConfiguration()
        config.addCompilationCustomizers( importCustomizer )
        config.setPluginFactory(new ScriptParserPluginFactory(true))

        final optimizationOptions = config.getOptimizationOptions()
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true)

        return config
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

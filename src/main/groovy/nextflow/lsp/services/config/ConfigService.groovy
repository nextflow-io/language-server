package nextflow.lsp.services.config

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigParserPluginFactory
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.services.LanguageService
import org.codehaus.groovy.control.CompilerConfiguration

/**
 * Implementation of language services for Nextflow config files.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigService extends LanguageService {

    @Override
    boolean matchesFile(String uri) {
        uri.endsWith('.config') && !uri.endsWith('nf-test.config')
    }

    @Override
    protected Compiler getCompiler() {
        final config = createConfiguration()
        final classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true)
        return new Compiler(config, classLoader)
    }

    protected CompilerConfiguration createConfiguration() {
        final config = new CompilerConfiguration()
        config.setPluginFactory(new ConfigParserPluginFactory(true))
        return config
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

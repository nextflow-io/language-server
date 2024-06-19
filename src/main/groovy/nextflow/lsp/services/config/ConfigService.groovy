package nextflow.lsp.services.config

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigParserPluginFactory
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.compiler.CompilerTransform
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.services.LanguageService
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit

/**
 * Implementation of language services for Nextflow config files.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigService extends LanguageService {

    private ConfigAstCache astCache = new ConfigAstCache(getCompiler())

    @Override
    boolean matchesFile(String uri) {
        uri.endsWith('.config') && !uri.endsWith('nf-test.config')
    }

    @Override
    protected ASTNodeCache getAstCache() {
        return astCache
    }

    protected Compiler getCompiler() {
        final config = createConfiguration()
        final classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true)
        final CompilerTransform transform = new CompilerTransform() {
            @Override
            void visit(SourceUnit sourceUnit) {
                new ConfigSchemaVisitor(sourceUnit).visit()
            }
        }
        return new Compiler(config, classLoader, List.of(transform))
    }

    protected CompilerConfiguration createConfiguration() {
        final config = new CompilerConfiguration()
        config.setPluginFactory(new ConfigParserPluginFactory())
        return config
    }

    @Override
    protected CompletionProvider getCompletionProvider() {
        new ConfigCompletionProvider(astCache)
    }

    @Override
    protected FormattingProvider getFormattingProvider() {
        new ConfigFormattingProvider(astCache)
    }

    @Override
    protected HoverProvider getHoverProvider() {
        new ConfigHoverProvider(astCache)
    }

}

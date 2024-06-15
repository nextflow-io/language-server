package nextflow.lsp.services.script

import java.nio.file.Path

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.compiler.CompilerTransform
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.services.LanguageService
import nextflow.lsp.services.SymbolProvider
import nextflow.script.v2.ScriptParserPluginFactory
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ImportCustomizer

/**
 * Implementation of language services for Nextflow scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptService extends LanguageService {

    private ScriptAstCache astCache = new ScriptAstCache(getCompiler())

    @Override
    boolean matchesFile(String uri) {
        uri.endsWith('.nf')
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
                new VariableScopeVisitor(sourceUnit).visit()
            }
        }
        return new Compiler(config, classLoader, List.of(transform))
    }

    protected CompilerConfiguration createConfiguration() {
        final importCustomizer = new ImportCustomizer()
        importCustomizer.addImports( java.nio.file.Path.name )
        // Channel
        // Duration
        // MemoryUnit

        final config = new CompilerConfiguration()
        config.addCompilationCustomizers( importCustomizer )
        config.setPluginFactory(new ScriptParserPluginFactory())

        final optimizationOptions = config.getOptimizationOptions()
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true)

        return config
    }

    @Override
    protected CompletionProvider getCompletionProvider() {
        new ScriptCompletionProvider(astCache)
    }

    @Override
    protected FormattingProvider getFormattingProvider() {
        new ScriptFormattingProvider(astCache)
    }

    @Override
    protected SymbolProvider getSymbolProvider() {
        new ScriptSymbolProvider(astCache)
    }

    @Override
    protected HoverProvider getHoverProvider() {
        new ScriptHoverProvider(astCache)
    }

}

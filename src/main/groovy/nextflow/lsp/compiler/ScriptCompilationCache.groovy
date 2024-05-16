package nextflow.lsp.compiler

import java.nio.file.Path

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.script.v2.ScriptParserPluginFactory
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

@CompileStatic
class ScriptCompilationCache extends CompilationCache {

    @Override
    protected String getFileExtension() { 'nf' }

    static ScriptCompilationCache create() {
        final config = createConfiguration()
        final classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true)
        return new ScriptCompilationCache(config, classLoader)
    }

    static protected CompilerConfiguration createConfiguration() {
        final importCustomizer = new ImportCustomizer()
        importCustomizer.addImports( java.nio.file.Path.name )
        // Channel
        // Duration
        // MemoryUnit

        final Map<String, Boolean> optimizationOptions = [:]
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true)

        final config = new CompilerConfiguration()
        config.addCompilationCustomizers( importCustomizer )
        config.setOptimizationOptions(optimizationOptions)
        config.setPluginFactory(new ScriptParserPluginFactory())

        return config
    }

    ScriptCompilationCache(CompilerConfiguration config, GroovyClassLoader loader) {
        super(config, loader)
    }

}

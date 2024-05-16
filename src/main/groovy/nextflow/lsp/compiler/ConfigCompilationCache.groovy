package nextflow.lsp.compiler

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigParserPluginFactory
import org.codehaus.groovy.control.CompilerConfiguration

@CompileStatic
class ConfigCompilationCache extends CompilationCache {

    @Override
    protected String getFileExtension() { 'config' }

    static ConfigCompilationCache create() {
        final config = createConfiguration()
        final classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true)
        return new ConfigCompilationCache(config, classLoader)
    }

    static protected CompilerConfiguration createConfiguration() {
        final config = new CompilerConfiguration()
        config.setPluginFactory(new ConfigParserPluginFactory())

        return config
    }

    ConfigCompilationCache(CompilerConfiguration config, GroovyClassLoader loader) {
        super(config, loader)
    }

}

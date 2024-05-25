package nextflow.lsp.compiler

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector

/**
 * Error collector that does not throw exceptions.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class LanguageServerErrorCollector extends ErrorCollector {

    private static final long serialVersionUID = 1L

    LanguageServerErrorCollector(CompilerConfiguration configuration) {
        super(configuration)
    }

    @Override
    protected void failIfErrors() {
    }

}

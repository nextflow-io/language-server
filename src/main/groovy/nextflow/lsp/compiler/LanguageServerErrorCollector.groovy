package nextflow.lsp.compiler

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.messages.Message

/**
 * A special error collector that can reset errors and does
 * not throw exceptions.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class LanguageServerErrorCollector extends ErrorCollector {

    private static final long serialVersionUID = 1L

    LanguageServerErrorCollector(CompilerConfiguration configuration) {
        super(configuration)
    }

    void clear() {
        if( errors != null )
            errors.clear()
        if( warnings != null )
            warnings.clear()
    }

    @Override
    protected void failIfErrors() {
    }

    @Override
    List<? extends Message> getErrors() {
        final errors = super.getErrors()
        return errors != null
            ? errors
            : Collections.emptyList()
    }

}

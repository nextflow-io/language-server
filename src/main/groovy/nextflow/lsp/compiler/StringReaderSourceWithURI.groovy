package nextflow.lsp.compiler

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.io.StringReaderSource

@CompileStatic
class StringReaderSourceWithURI extends StringReaderSource {

    private URI uri

    StringReaderSourceWithURI(String string, URI uri, CompilerConfiguration configuration) {
        super(string, configuration)
        this.uri = uri
    }

    URI getURI() {
        return uri
    }

}

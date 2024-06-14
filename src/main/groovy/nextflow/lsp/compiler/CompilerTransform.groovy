package nextflow.lsp.compiler

import org.codehaus.groovy.control.SourceUnit

interface CompilerTransform {
    void visit(SourceUnit sourceUnit)
}

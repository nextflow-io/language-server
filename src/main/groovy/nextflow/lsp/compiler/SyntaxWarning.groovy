package nextflow.lsp.compiler

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.syntax.SyntaxException

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class SyntaxWarning extends SyntaxException {

    SyntaxWarning(String message, ASTNode node) {
        super(message, node)
    }
}

package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigSchema
import nextflow.config.v2.ConfigAssignmentNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.lsp.compiler.SyntaxWarning
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigSchemaVisitor extends ClassCodeVisitorSupport {

    private SourceUnit sourceUnit

    private List<String> scopes = []

    ConfigSchemaVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode == null )
            return
        visit(moduleNode.getStatementBlock())
    }

    @Override
    void visitExpressionStatement(ExpressionStatement node) {
        if( node instanceof ConfigAssignmentNode )
            visitConfigAssignment(node)
        else if( node instanceof ConfigBlockNode )
            visitConfigBlock(node)
        else if( node instanceof ConfigIncludeNode )
            visitConfigInclude(node)
        else
            super.visitExpressionStatement(node)
    }

    protected void visitConfigAssignment(ConfigAssignmentNode node) {
        final names = scopes + node.names
        if( names.first() == 'profiles' ) {
            if( names ) names.pop()
            if( names ) names.pop()
        }
        if( names.first() == 'env' ) {
            final envName = names.head().join('.')
            if( envName.contains('.') )
                addWarning("Invalid environment variable name '${envName}'", node)
            return
        }
        if( names.first() == 'params' ) {
            // TODO: validate params against schema
            return
        }
        if( names.first() == 'plugins' ) {
            // TODO: load plugin config scopes
            return
        }

        final fqName = names.join('.')
        final option = ConfigSchema.OPTIONS[fqName]
        if( !option )
            addWarning("Unrecognized config option '${fqName}'", node)
    }

    protected void visitConfigBlock(ConfigBlockNode node) {
        final newScope = node.kind == null
        if( newScope )
            scopes.add(node.name)
        visit(node.block)
        if( newScope )
            scopes.removeLast()
    }

    protected void visitConfigInclude(ConfigIncludeNode node) {
    }

    void addWarning(String message, ASTNode node) {
        final cause = new SyntaxWarning(message, node)
        final errorMessage = new SyntaxErrorMessage(cause, sourceUnit)
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage)
    }

}

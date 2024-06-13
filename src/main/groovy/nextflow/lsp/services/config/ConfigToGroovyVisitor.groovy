package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigAppendNode
import nextflow.config.v2.ConfigAssignmentNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit

import static org.codehaus.groovy.ast.tools.GeneralUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigToGroovyVisitor extends ClassCodeVisitorSupport {

    private SourceUnit sourceUnit

    ConfigToGroovyVisitor(SourceUnit sourceUnit) {
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
        super.visitBlockStatement(moduleNode.getStatementBlock())
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
        final methodName = node instanceof ConfigAppendNode ? 'append' : 'assign'
        final names = listX( node.names.collect(name -> constX(name)) as List<Expression> )
        node.expression = callThisX(methodName, args(names, node.value))
    }

    protected void visitConfigBlock(ConfigBlockNode node) {
        node.expression = callThisX(node.kind ?: 'block', args(constX(node.name), closureX(node.block)))
    }

    protected void visitConfigInclude(ConfigIncludeNode node) {
        node.expression = callThisX('includeConfig', args(node.source))
    }
}

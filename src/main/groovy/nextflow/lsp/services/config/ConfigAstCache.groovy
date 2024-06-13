package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.config.v2.ConfigAssignmentNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigAstCache extends ASTNodeCache {

    ConfigAstCache(Compiler compiler) {
        super(compiler)
    }

    protected ASTNodeCache.Visitor createVisitor(SourceUnit sourceUnit) {
        return new Visitor(sourceUnit)
    }

    private class Visitor extends ASTNodeCache.Visitor {

        Visitor(SourceUnit sourceUnit) {
            super(sourceUnit)
        }

        @Override
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
            pushASTNode(node)
            try {
                visit(node.value)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitConfigBlock(ConfigBlockNode node) {
            pushASTNode(node)
            try {
                visit(node.block)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitConfigInclude(ConfigIncludeNode node) {
            pushASTNode(node)
            try {
                visit(node.source)
            }
            finally {
                popASTNode()
            }
        }
    }

}

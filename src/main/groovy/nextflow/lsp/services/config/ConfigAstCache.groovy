package nextflow.lsp.services.config

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.file.FileCache
import nextflow.config.v2.ConfigAssignmentNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigAstCache extends ASTNodeCache {

    ConfigAstCache(Compiler compiler) {
        super(compiler)
    }

    @Override
    Map<URI, List<SyntaxException>> update(Set<URI> uris, FileCache fileCache) {
        final errorsByUri = super.update(uris, fileCache)

        for( final sourceUnit : getSourceUnits() ) {
            final visitor = new ResolveIncludeVisitor(sourceUnit, this, uris)
            visitor.visit()

            final uri = sourceUnit.getSource().getURI()
            if( !errorsByUri.containsKey(uri) )
                errorsByUri.put(uri, [])
            errorsByUri[uri].addAll(visitor.getErrors())
        }

        return errorsByUri
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

    private class ResolveIncludeVisitor extends ClassCodeVisitorSupport {

        private SourceUnit sourceUnit

        private URI uri

        private ConfigAstCache astCache

        private Set<URI> changedUris

        private List<SyntaxException> errors = []

        ResolveIncludeVisitor(SourceUnit sourceUnit, ConfigAstCache astCache, Set<URI> changedUris) {
            this.sourceUnit = sourceUnit
            this.uri = sourceUnit.getSource().getURI()
            this.astCache = astCache
            this.changedUris = changedUris
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
        }

        protected void visitConfigBlock(ConfigBlockNode node) {
        }

        protected void visitConfigInclude(ConfigIncludeNode node) {
            if( node.source !instanceof ConstantExpression )
                return
            final source = node.source.getText()
            final includeUri = getIncludeUri(uri, source)
            // resolve include node only if it is stale
            if( uri !in changedUris && includeUri !in changedUris )
                return
            final includeUnit = astCache.getSourceUnit(includeUri)
            if( !includeUnit ) {
                addError("Invalid include source: '${includeUri}'", node)
                return
            }
        }

        protected URI getIncludeUri(URI uri, String source) {
            return Path.of(uri).getParent().resolve(source).normalize().toUri()
        }

        List<SyntaxException> getErrors() {
            return errors
        }

        @Override
        void addError(String message, ASTNode node) {
            errors.add(new SyntaxException(message, node))
        }
    }

}

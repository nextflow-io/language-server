package nextflow.lsp.compiler

import groovy.transform.CompileStatic
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Positions
import nextflow.lsp.util.Ranges
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.FieldExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Cache the AST for each compiled source file for
 * efficient querying.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ASTNodeCache {

    private Map<URI, List<ASTNode>> nodesByURI = [:]

    private Map<URI, List<ClassNode>> classNodesByURI = [:]

    private Map<LookupKey, LookupData> lookup = [:]

    /**
     * Get the list of class nodes across all cached files.
     */
    List<ClassNode> getClassNodes() {
        final List<ClassNode> result = []
        for( final nodes : classNodesByURI.values() )
            result.addAll(nodes)
        return result
    }

    /**
     * Get the list of ast nodes across all cached files.
     */
    List<ASTNode> getNodes() {
        final List<ASTNode> result = []
        for( final nodes : nodesByURI.values() )
            result.addAll(nodes)
        return result
    }

    /**
     * Get the list of ast nodes for a given file.
     *
     * @param uri
     */
    List<ASTNode> getNodes(URI uri) {
        return nodesByURI[uri] ?: Collections.emptyList()
    }

    /**
     * Get the tree of nodes at a given location in a file.
     *
     * @param uri
     * @param line
     * @param column
     */
    List<ASTNode> getNodesAtLineAndColumn(URI uri, int line, int column) {
        final position = new Position(line, column)
        final Map<ASTNode, Range> nodeToRange = [:]
        final nodes = nodesByURI[uri]
        if( nodes == null )
            return []

        return nodes
            .findAll(node -> {
                if( node.getLineNumber() == -1 )
                    return false
                final range = LanguageServerUtils.astNodeToRange(node)
                if( !Ranges.contains(range, position) )
                    return false
                nodeToRange.put(node, range)
                return true
            })
            .sort((n1, n2) -> {
                // select node with higher start position
                final cmp1 = Positions.COMPARATOR.compare(nodeToRange[n1].start, nodeToRange[n2].start)
                if( cmp1 != 0 )
                    return -cmp1
                // select node with lower end position
                final cmp2 = Positions.COMPARATOR.compare(nodeToRange[n1].end, nodeToRange[n2].end)
                if( cmp2 != 0 )
                    return cmp2
                // select the most descendant node
                if( contains(n1, n2) )
                    return 1
                if( contains(n2, n1) )
                    return -1
                return 0
            })
    }

    /**
     * Get the most specific ast node at a given location in a file.
     *
     * @param uri
     * @param line
     * @param column
     */
    ASTNode getNodeAtLineAndColumn(URI uri, int line, int column) {
        final foundNodes = getNodesAtLineAndColumn(uri, line, column)
        return foundNodes.size() > 0 ? foundNodes.first() : null
    }

    /**
     * Get the parent of a given ast node.
     *
     * @param child
     */
    ASTNode getParent(ASTNode child) {
        if( child == null )
            return null
        return lookup.get(new LookupKey(child))?.parent
    }

    /**
     * Determine whether an ast node is a direct or indirect
     * parent of another node.
     *
     * @param ancestor
     * @param descendant
     */
    boolean contains(ASTNode ancestor, ASTNode descendant) {
        ASTNode current = getParent(descendant)
        while( current != null ) {
            if( current == ancestor )
                return true
            current = getParent(current)
        }
        return false
    }

    /**
     * Get the file that contains an ast node.
     *
     * @param node
     */
    URI getURI(ASTNode node) {
        return lookup.get(new LookupKey(node))?.uri
    }

    /**
     * Clear the cache and add all source files from a
     * compilation unit.
     *
     * @param unit
     */
    void update(CompilationUnit unit) {
        nodesByURI.clear()
        classNodesByURI.clear()
        lookup.clear()
        unit.iterator().forEachRemaining(sourceUnit -> {
            visitSourceUnit(sourceUnit)
        })
    }

    /**
     * Update the cache entries for a given set of files
     * in a compilation unit.
     *
     * @param unit
     * @param uris
     */
    void update(CompilationUnit unit, Collection<URI> uris) {
        // remove given files from ast cache
        uris.forEach(uri -> {
            final nodes = nodesByURI.remove(uri)
            nodes?.forEach(node -> {
                lookup.remove(new LookupKey(node))
            })
            classNodesByURI.remove(uri)
        })

        // update cache entries for given files
        unit.iterator().forEachRemaining(sourceUnit -> {
            final uri = sourceUnit.getSource().getURI()
            if( !uris.contains(uri) )
                return
            visitSourceUnit(sourceUnit)
        })
    }

    protected void visitSourceUnit(SourceUnit sourceUnit) {
        new Visitor(sourceUnit).visit()
    }

    private class Visitor extends ClassCodeVisitorSupport {

        private SourceUnit sourceUnit

        private Stack<ASTNode> stack = []

        Visitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        void visit() {
            final uri = sourceUnit.getSource().getURI()
            nodesByURI.put(uri, [])
            classNodesByURI.put(uri, [])
            final moduleNode = sourceUnit.getAST()
            if( moduleNode != null )
                visitModule(moduleNode)
        }

        protected void visitModule(ModuleNode node) {
            for( final classNode : node.getClasses() ) {
                if( classNode != node.getScriptClassDummy() )
                    visitClass(classNode)
            }
            for( final methodNode : node.getMethods() ) {
                if( methodNode instanceof FunctionNode )
                    visitFunction(methodNode)
            }
            super.visitBlockStatement(node.getStatementBlock())
        }

        @Override
        void visitClass(ClassNode node) {
            final uri = sourceUnit.getSource().getURI()
            classNodesByURI.get(uri).add(node)
            pushASTNode(node)
            try {
                super.visitClass(node)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitFunction(FunctionNode node) {
            pushASTNode(node)
            try {
                super.visitMethod(node)
                for( final parameter : node.getParameters() )
                    visitParameter(parameter)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitParameter(Parameter node) {
            pushASTNode(node)
            try {
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitField(FieldNode node) {
            pushASTNode(node)
            try {
                super.visitField(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitProperty(PropertyNode node) {
            pushASTNode(node)
            try {
                super.visitProperty(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitBlockStatement(BlockStatement node) {
            pushASTNode(node)
            try {
                super.visitBlockStatement(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitIfElse(IfStatement node) {
            pushASTNode(node)
            try {
                super.visitIfElse(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitExpressionStatement(ExpressionStatement node) {
            if( node instanceof ProcessNode ) {
                visitProcess(node)
                return
            }
            if( node instanceof WorkflowNode ) {
                visitWorkflow(node)
                return
            }
            pushASTNode(node)
            try {
                super.visitExpressionStatement(node)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitProcess(ProcessNode node) {
            pushASTNode(node)
            try {
                visit(node.directives)
                visit(node.inputs)
                visit(node.outputs)
                visit(node.exec)
                visit(node.stub)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitWorkflow(WorkflowNode node) {
            pushASTNode(node)
            try {
                visit(node.takes)
                visit(node.emits)
                visit(node.main)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitReturnStatement(ReturnStatement node) {
            pushASTNode(node)
            try {
                super.visitReturnStatement(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitAssertStatement(AssertStatement node) {
            pushASTNode(node)
            try {
                super.visitAssertStatement(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitEmptyStatement(EmptyStatement node) {
            pushASTNode(node)
            try {
                super.visitEmptyStatement(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitMethodCallExpression(MethodCallExpression node) {
            pushASTNode(node)
            try {
                super.visitMethodCallExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitStaticMethodCallExpression(StaticMethodCallExpression node) {
            pushASTNode(node)
            try {
                super.visitStaticMethodCallExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitConstructorCallExpression(ConstructorCallExpression node) {
            pushASTNode(node)
            try {
                super.visitConstructorCallExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitBinaryExpression(BinaryExpression node) {
            pushASTNode(node)
            try {
                super.visitBinaryExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitTernaryExpression(TernaryExpression node) {
            pushASTNode(node)
            try {
                super.visitTernaryExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitShortTernaryExpression(ElvisOperatorExpression node) {
            pushASTNode(node)
            try {
                super.visitShortTernaryExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitPostfixExpression(PostfixExpression node) {
            pushASTNode(node)
            try {
                super.visitPostfixExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitPrefixExpression(PrefixExpression node) {
            pushASTNode(node)
            try {
                super.visitPrefixExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitBooleanExpression(BooleanExpression node) {
            pushASTNode(node)
            try {
                super.visitBooleanExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitNotExpression(NotExpression node) {
            pushASTNode(node)
            try {
                super.visitNotExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitClosureExpression(ClosureExpression node) {
            pushASTNode(node)
            try {
                super.visitClosureExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitTupleExpression(TupleExpression node) {
            pushASTNode(node)
            try {
                super.visitTupleExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitListExpression(ListExpression node) {
            pushASTNode(node)
            try {
                super.visitListExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitMapExpression(MapExpression node) {
            pushASTNode(node)
            try {
                super.visitMapExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitMapEntryExpression(MapEntryExpression node) {
            pushASTNode(node)
            try {
                super.visitMapEntryExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitRangeExpression(RangeExpression node) {
            pushASTNode(node)
            try {
                super.visitRangeExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitSpreadExpression(SpreadExpression node) {
            pushASTNode(node)
            try {
                super.visitSpreadExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitSpreadMapExpression(SpreadMapExpression node) {
            pushASTNode(node)
            try {
                super.visitSpreadMapExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitUnaryMinusExpression(UnaryMinusExpression node) {
            pushASTNode(node)
            try {
                super.visitUnaryMinusExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitUnaryPlusExpression(UnaryPlusExpression node) {
            pushASTNode(node)
            try {
                super.visitUnaryPlusExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
            pushASTNode(node)
            try {
                super.visitBitwiseNegationExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitCastExpression(CastExpression node) {
            pushASTNode(node)
            try {
                super.visitCastExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitConstantExpression(ConstantExpression node) {
            pushASTNode(node)
            try {
                super.visitConstantExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitClassExpression(ClassExpression node) {
            pushASTNode(node)
            try {
                super.visitClassExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitVariableExpression(VariableExpression node) {
            pushASTNode(node)
            try {
                super.visitVariableExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitPropertyExpression(PropertyExpression node) {
            pushASTNode(node)
            try {
                super.visitPropertyExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitFieldExpression(FieldExpression node) {
            pushASTNode(node)
            try {
                super.visitFieldExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        @Override
        void visitGStringExpression(GStringExpression node) {
            pushASTNode(node)
            try {
                super.visitGStringExpression(node)
            }
            finally {
                popASTNode()
            }
        }

        private void pushASTNode(ASTNode node) {
            final isSynthetic = node instanceof AnnotatedNode && node.isSynthetic()
            if( !isSynthetic ) {
                final uri = sourceUnit.getSource().getURI()
                nodesByURI.get(uri).add(node)

                final data = new LookupData()
                data.uri = uri
                if( stack.size() > 0 )
                    data.parent = stack.lastElement()
                lookup.put(new LookupKey(node), data)
            }

            stack.add(node)
        }

        private void popASTNode() {
            stack.pop()
        }
    }

    static private class LookupKey {
        private ASTNode node

        LookupKey(ASTNode node) {
            this.node = node
        }

        @Override
        boolean equals(Object o) {
            final other = (LookupKey) o
            return node == other.node
        }

        @Override
        int hashCode() {
            return node.hashCode()
        }
    }

    static private class LookupData {
        ASTNode parent
        URI uri
    }

}

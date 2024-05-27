package nextflow.lsp.ast

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Positions
import nextflow.lsp.util.Ranges
import nextflow.config.v2.ConfigAssignmentNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
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
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
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

    private Compiler compiler

    private Map<URI, SourceUnit> sourcesByUri = [:]

    private Map<URI, List<ASTNode>> nodesByURI = [:]

    private Map<URI, List<FunctionNode>> functionNodesByURI = [:]

    private Map<URI, List<ProcessNode>> processNodesByURI = [:]

    private Map<URI, List<WorkflowNode>> workflowNodesByURI = [:]

    private Map<URI, List<ClassNode>> classNodesByURI = [:]

    private Map<LookupKey, LookupData> lookup = [:]

    ASTNodeCache(Compiler compiler) {
        this.compiler = compiler
    }

    /**
     * Update the cache for a set of source files.
     *
     * @param uris
     * @param fileCache
     */
    Map<URI, List<SyntaxException>> update(Set<URI> uris, FileCache fileCache) {
        final sources = compiler.compile(uris, fileCache)
        final Map<URI, List<SyntaxException>> errorsByUri = [:]

        for( final uri : uris ) {
            // remove any existing cache entries
            final nodes = nodesByURI.remove(uri)
            if( nodes ) {
                for( final node : nodes )
                    lookup.remove(new LookupKey(node))
            }
            sourcesByUri.remove(uri)
            functionNodesByURI.remove(uri)
            processNodesByURI.remove(uri)
            workflowNodesByURI.remove(uri)
            classNodesByURI.remove(uri)

            final sourceUnit = sources[uri]
            if( !sourceUnit ) {
                errorsByUri[uri] = []
                continue
            }

            // update cache
            new Visitor(sourceUnit).visit()
            sourcesByUri[uri] = sourceUnit

            // collect errors
            final List<SyntaxException> errors = []
            final messages = sourceUnit.getErrorCollector().getErrors() ?: []
            for( final message : messages ) {
                if( message instanceof SyntaxErrorMessage )
                    errors.add(message.cause)
            }
            errorsByUri[uri] = errors
        }

        return errorsByUri
    }

    SourceUnit getSourceUnit(URI uri) {
        return sourcesByUri[uri]
    }

    /**
     * Get the list of function nodes across all cached files.
     */
    List<FunctionNode> getFunctionNodes() {
        final List<FunctionNode> result = []
        for( final nodes : functionNodesByURI.values() )
            result.addAll(nodes)
        return result
    }

    List<FunctionNode> getFunctionNodes(URI uri) {
        return functionNodesByURI[uri]
    }

    /**
     * Get the list of process nodes across all cached files.
     */
    List<ProcessNode> getProcessNodes() {
        final List<ProcessNode> result = []
        for( final nodes : processNodesByURI.values() )
            result.addAll(nodes)
        return result
    }

    List<ProcessNode> getProcessNodes(URI uri) {
        return processNodesByURI[uri]
    }

    /**
     * Get the list of workflow nodes across all cached files.
     */
    List<WorkflowNode> getWorkflowNodes() {
        final List<WorkflowNode> result = []
        for( final nodes : workflowNodesByURI.values() )
            result.addAll(nodes)
        return result
    }

    List<WorkflowNode> getWorkflowNodes(URI uri) {
        return workflowNodesByURI[uri]
    }

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

    private class Visitor extends ClassCodeVisitorSupport {

        private SourceUnit sourceUnit

        private URI uri

        private Stack<ASTNode> stack = []

        Visitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit
            this.uri = sourceUnit.getSource().getURI()
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        void visit() {
            nodesByURI.put(uri, [])
            functionNodesByURI.put(uri, [])
            processNodesByURI.put(uri, [])
            workflowNodesByURI.put(uri, [])
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
            classNodesByURI[uri].add(node)
            pushASTNode(node)
            try {
                super.visitClass(node)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitFunction(FunctionNode node) {
            functionNodesByURI[uri].add(node)
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
            if( node instanceof ConfigAssignmentNode ) {
                visitConfigAssignment(node)
                return
            }
            if( node instanceof ConfigBlockNode ) {
                visitConfigBlock(node)
                return
            }
            if( node instanceof ConfigIncludeNode ) {
                visitConfigInclude(node)
                return
            }
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

        protected void visitProcess(ProcessNode node) {
            processNodesByURI[uri].add(node)
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
            workflowNodesByURI[uri].add(node)
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
        void visitTryCatchFinally(TryCatchStatement node) {
            pushASTNode(node)
            try {
                super.visitTryCatchFinally(node)
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
                // see CodeVisitorSupport::visitShortTernaryExpression()
                super.visitTernaryExpression(node)
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

        @Override
        void visitCatchStatement(CatchStatement node) {
            pushASTNode(node)
            try {
                super.visitCatchStatement(node)
            }
            finally {
                popASTNode()
            }
        }

        private void pushASTNode(ASTNode node) {
            final isSynthetic = node instanceof AnnotatedNode && node.isSynthetic()
            if( !isSynthetic ) {
                final uri = sourceUnit.getSource().getURI()
                nodesByURI[uri].add(node)

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

/*
 * Copyright 2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.lsp.ast;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import nextflow.lsp.compiler.Compiler;
import nextflow.lsp.compiler.SyntaxWarning;
import nextflow.lsp.file.FileCache;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Positions;
import nextflow.lsp.util.Ranges;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.SpreadMapExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Cache the AST for each compiled source file for
 * efficient querying.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ASTNodeCache {

    private Compiler compiler;

    private Map<URI, SourceUnit> sourcesByUri = new HashMap<>();

    private Map<URI, List<SyntaxException>> errorsByUri = new HashMap<>();

    private Map<URI, List<ASTNode>> nodesByURI = new HashMap<>();

    private Map<LookupKey, LookupData> lookup = new HashMap<>();

    public ASTNodeCache(Compiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Clear the cache.
     */
    public void clear() {
        sourcesByUri.clear();
        errorsByUri.clear();
        nodesByURI.clear();
        lookup.clear();
    }

    /**
     * Update the cache for a set of source files.
     *
     * @param uris
     * @param fileCache
     */
    public Map<URI, List<SyntaxException>> update(Set<URI> uris, FileCache fileCache) {
        var sources = compiler.compile(uris, fileCache);

        for( var uri : uris ) {
            // remove any existing cache entries
            var nodes = nodesByURI.remove(uri);
            if( nodes != null ) {
                for( var node : nodes )
                    lookup.remove(new LookupKey(node));
            }
            sourcesByUri.remove(uri);

            var sourceUnit = sources.get(uri);
            if( sourceUnit == null ) {
                errorsByUri.put(uri, new ArrayList<>());
                continue;
            }

            // update cache
            var visitor = createVisitor(sourceUnit);
            visitor.visit();

            sourcesByUri.put(uri, sourceUnit);
            nodesByURI.put(uri, visitor.getNodes());
            lookup.putAll(visitor.getLookupEntries());

            // collect errors
            var errors = new ArrayList<SyntaxException>();
            var messages = sourceUnit.getErrorCollector().getErrors();
            if( messages != null ) {
                for( var message : messages ) {
                    if( message instanceof SyntaxErrorMessage sem )
                        errors.add(sem.getCause());
                }
            }
            errorsByUri.put(uri, errors);
        }

        return errorsByUri;
    }

    protected Visitor createVisitor(SourceUnit sourceUnit) {
        return new Visitor(sourceUnit);
    }

    /**
     * Get the list of source units for all cached files.
     */
    public Collection<SourceUnit> getSourceUnits() {
        return sourcesByUri.values();
    }

    /**
     * Get the source unit for a given file.
     *
     * @param uri
     */
    public SourceUnit getSourceUnit(URI uri) {
        return sourcesByUri.get(uri);
    }

    /**
     * Check whether an AST is defined for a given file.
     *
     * @param uri
     */
    public boolean hasAST(URI uri) {
        var sourceUnit = sourcesByUri.get(uri);
        return sourceUnit != null && sourceUnit.getAST() != null;
    }

    /**
     * Check whether a source file has any errors.
     *
     * @param uri
     */
    public boolean hasErrors(URI uri) {
        var errors = errorsByUri.get(uri);
        if( errors == null )
            return false;
        for( var error : errors ) {
            if( !(error instanceof SyntaxWarning) )
                return true;
        }
        return false;
    }

    /**
     * Check whether a source file has any warnings.
     *
     * @param uri
     */
    public boolean hasWarnings(URI uri) {
        var errors = errorsByUri.get(uri);
        if( errors == null )
            return false;
        for( var error : errors ) {
            if( error instanceof SyntaxWarning )
                return true;
        }
        return false;
    }

    /**
     * Get the list of ast nodes across all cached files.
     */
    public List<ASTNode> getNodes() {
        var result = new ArrayList<ASTNode>();
        for( var nodes : nodesByURI.values() )
            result.addAll(nodes);
        return result;
    }

    /**
     * Get the list of ast nodes for a given file.
     *
     * @param uri
     */
    public List<ASTNode> getNodes(URI uri) {
        return nodesByURI.getOrDefault(uri, Collections.emptyList());
    }

    /**
     * Get the most specific ast node at a given location in a file.
     *
     * @param uri
     * @param line
     * @param column
     */
    public ASTNode getNodeAtLineAndColumn(URI uri, int line, int column) {
        var position = new Position(line, column);
        var nodeToRange = new HashMap<ASTNode, Range>();
        var nodes = nodesByURI.get(uri);
        if( nodes == null )
            return null;

        return nodes.stream()
            .filter(node -> {
                if( node.getLineNumber() == -1 )
                    return false;
                var range = LanguageServerUtils.astNodeToRange(node);
                if( !Ranges.contains(range, position) )
                    return false;
                nodeToRange.put(node, range);
                return true;
            })
            .sorted((n1, n2) -> {
                // select node with higher start position
                var cmp1 = Positions.COMPARATOR.compare(nodeToRange.get(n1).getStart(), nodeToRange.get(n2).getStart());
                if( cmp1 != 0 )
                    return -cmp1;
                // select node with lower end position
                var cmp2 = Positions.COMPARATOR.compare(nodeToRange.get(n1).getEnd(), nodeToRange.get(n2).getEnd());
                if( cmp2 != 0 )
                    return cmp2;
                // select the most descendant node
                if( contains(n1, n2) )
                    return 1;
                if( contains(n2, n1) )
                    return -1;
                return 0;
            })
            .findFirst().orElse(null);
    }

    /**
     * Get the tree of nodes at a given location in a file.
     *
     * @param uri
     * @param line
     * @param column
     */
    public List<ASTNode> getNodesAtLineAndColumn(URI uri, int line, int column) {
        var offsetNode = getNodeAtLineAndColumn(uri, line, column);
        var result = new ArrayList<ASTNode>();
        ASTNode current = offsetNode;
        while( current != null ) {
            result.add(current);
            current = getParent(current);
        }
        return result;
    }

    /**
     * Get the parent of a given ast node.
     *
     * @param child
     */
    public ASTNode getParent(ASTNode child) {
        if( child == null )
            return null;
        var lookupData = lookup.get(new LookupKey(child));
        return lookupData != null ? lookupData.parent : null;
    }

    /**
     * Determine whether an ast node is a direct or indirect
     * parent of another node.
     *
     * @param ancestor
     * @param descendant
     */
    public boolean contains(ASTNode ancestor, ASTNode descendant) {
        ASTNode current = getParent(descendant);
        while( current != null ) {
            if( current.equals(ancestor) )
                return true;
            current = getParent(current);
        }
        return false;
    }

    /**
     * Get the file that contains an ast node.
     *
     * @param node
     */
    public URI getURI(ASTNode node) {
        var lookupData = lookup.get(new LookupKey(node));
        return lookupData != null ? lookupData.uri : null;
    }

    public static class Visitor extends ClassCodeVisitorSupport {

        private SourceUnit sourceUnit;

        private URI uri;

        private List<ASTNode> nodes = new ArrayList<>();

        private Map<LookupKey, LookupData> lookupEntries = new HashMap<>();

        private Stack<ASTNode> stack = new Stack<>();

        public Visitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit;
            this.uri = sourceUnit.getSource().getURI();
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        public void visit() {
            var moduleNode = sourceUnit.getAST();
            if( moduleNode == null )
                return;
            for( var classNode : moduleNode.getClasses() ) {
                if( classNode != moduleNode.getScriptClassDummy() )
                    visitClass(classNode);
            }
            for( var methodNode : moduleNode.getMethods() ) {
                visitMethod(methodNode);
            }
            super.visitBlockStatement(moduleNode.getStatementBlock());
        }

        public List<ASTNode> getNodes() {
            return nodes;
        }

        public Map<LookupKey, LookupData> getLookupEntries() {
            return lookupEntries;
        }

        // class statements

        @Override
        public void visitClass(ClassNode node) {
            pushASTNode(node);
            try {
                super.visitClass(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitMethod(MethodNode node) {
            pushASTNode(node);
            try {
                super.visitMethod(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitField(FieldNode node) {
            pushASTNode(node);
            try {
                super.visitField(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitProperty(PropertyNode node) {
            pushASTNode(node);
            try {
                super.visitProperty(node);
            }
            finally {
                popASTNode();
            }
        }

        // statements

        @Override
        public void visitBlockStatement(BlockStatement node) {
            pushASTNode(node);
            try {
                super.visitBlockStatement(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitIfElse(IfStatement node) {
            pushASTNode(node);
            try {
                super.visitIfElse(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitExpressionStatement(ExpressionStatement node) {
            pushASTNode(node);
            try {
                super.visitExpressionStatement(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitReturnStatement(ReturnStatement node) {
            pushASTNode(node);
            try {
                super.visitReturnStatement(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitAssertStatement(AssertStatement node) {
            pushASTNode(node);
            try {
                super.visitAssertStatement(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitTryCatchFinally(TryCatchStatement node) {
            pushASTNode(node);
            try {
                super.visitTryCatchFinally(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitCatchStatement(CatchStatement node) {
            pushASTNode(node);
            try {
                super.visitCatchStatement(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitEmptyStatement(EmptyStatement node) {
            pushASTNode(node);
            try {
                super.visitEmptyStatement(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitThrowStatement(ThrowStatement node) {
            pushASTNode(node);
            try {
                super.visitThrowStatement(node);
            }
            finally {
                popASTNode();
            }
        }

        // expressions

        @Override
        public void visitMethodCallExpression(MethodCallExpression node) {
            pushASTNode(node);
            try {
                super.visitMethodCallExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression node) {
            pushASTNode(node);
            try {
                super.visitStaticMethodCallExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression node) {
            pushASTNode(node);
            try {
                super.visitConstructorCallExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitBinaryExpression(BinaryExpression node) {
            pushASTNode(node);
            try {
                super.visitBinaryExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitTernaryExpression(TernaryExpression node) {
            pushASTNode(node);
            try {
                super.visitTernaryExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitShortTernaryExpression(ElvisOperatorExpression node) {
            pushASTNode(node);
            try {
                // see CodeVisitorSupport::visitShortTernaryExpression()
                super.visitTernaryExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitBooleanExpression(BooleanExpression node) {
            pushASTNode(node);
            try {
                super.visitBooleanExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitNotExpression(NotExpression node) {
            pushASTNode(node);
            try {
                super.visitNotExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitClosureExpression(ClosureExpression node) {
            pushASTNode(node);
            try {
                var parameters = node.getParameters();
                if( parameters != null ) {
                    for( var parameter : parameters )
                        visitParameter(parameter);
                }
                super.visitClosureExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        protected void visitParameter(Parameter node) {
            pushASTNode(node);
            try {
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitTupleExpression(TupleExpression node) {
            pushASTNode(node);
            try {
                super.visitTupleExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitListExpression(ListExpression node) {
            pushASTNode(node);
            try {
                super.visitListExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitMapExpression(MapExpression node) {
            pushASTNode(node);
            try {
                super.visitMapExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression node) {
            pushASTNode(node);
            try {
                super.visitMapEntryExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitRangeExpression(RangeExpression node) {
            pushASTNode(node);
            try {
                super.visitRangeExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitSpreadExpression(SpreadExpression node) {
            pushASTNode(node);
            try {
                super.visitSpreadExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitSpreadMapExpression(SpreadMapExpression node) {
            pushASTNode(node);
            try {
                super.visitSpreadMapExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitUnaryMinusExpression(UnaryMinusExpression node) {
            pushASTNode(node);
            try {
                super.visitUnaryMinusExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitUnaryPlusExpression(UnaryPlusExpression node) {
            pushASTNode(node);
            try {
                super.visitUnaryPlusExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
            pushASTNode(node);
            try {
                super.visitBitwiseNegationExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitCastExpression(CastExpression node) {
            pushASTNode(node);
            try {
                super.visitCastExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitConstantExpression(ConstantExpression node) {
            pushASTNode(node);
            try {
                super.visitConstantExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitClassExpression(ClassExpression node) {
            pushASTNode(node);
            try {
                super.visitClassExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitVariableExpression(VariableExpression node) {
            pushASTNode(node);
            try {
                super.visitVariableExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitPropertyExpression(PropertyExpression node) {
            pushASTNode(node);
            try {
                super.visitPropertyExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitFieldExpression(FieldExpression node) {
            pushASTNode(node);
            try {
                super.visitFieldExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        @Override
        public void visitGStringExpression(GStringExpression node) {
            pushASTNode(node);
            try {
                super.visitGStringExpression(node);
            }
            finally {
                popASTNode();
            }
        }

        protected void pushASTNode(ASTNode node) {
            var isSynthetic = node instanceof AnnotatedNode an && an.isSynthetic();
            if( !isSynthetic ) {
                nodes.add(node);

                var data = new LookupData();
                data.uri = uri;
                if( stack.size() > 0 )
                    data.parent = stack.lastElement();
                lookupEntries.put(new LookupKey(node), data);
            }

            stack.add(node);
        }

        protected void popASTNode() {
            stack.pop();
        }
    }

    static private class LookupKey {
        private ASTNode node;

        public LookupKey(ASTNode node) {
            this.node = node;
        }

        @Override
        public boolean equals(Object o) {
			// some ASTNode subclasses (i.e. ClassNode) override equals() with
			// comparisons that are not strict
            var that = (LookupKey) o;
            return this.node == that.node;
        }

        @Override
        public int hashCode() {
            return node.hashCode();
        }
    }

    static private class LookupData {
        ASTNode parent;
        URI uri;
    }

}

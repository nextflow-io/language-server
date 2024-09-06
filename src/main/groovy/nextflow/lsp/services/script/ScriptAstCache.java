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
package nextflow.lsp.services.script;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.ast.ASTNodeLookup;
import nextflow.lsp.compiler.Compiler;
import nextflow.lsp.compiler.LanguageServerErrorCollector;
import nextflow.lsp.compiler.Phases;
import nextflow.lsp.file.FileCache;
import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.IncludeNode;
import nextflow.script.v2.IncludeVariable;
import nextflow.script.v2.OutputNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.ScriptNode;
import nextflow.script.v2.ScriptParserPluginFactory;
import nextflow.script.v2.ScriptVisitorSupport;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
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
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptAstCache extends ASTNodeCache {

    private Compiler compiler;

    private CompilationUnit compilationUnit;

    public ScriptAstCache() {
        var config = createConfiguration();
        var classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config, true);
        compiler = new Compiler(config, classLoader);
        compilationUnit = new CompilationUnit(config, null, classLoader);
    }

    private CompilerConfiguration createConfiguration() {
        var config = new CompilerConfiguration();
        config.setPluginFactory(new ScriptParserPluginFactory());

        var optimizationOptions = config.getOptimizationOptions();
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);

        return config;
    }

    @Override
    protected Map<URI, SourceUnit> buildAST(Set<URI> uris, FileCache fileCache) {
        // phase 1: syntax resolution
        return compiler.compile(uris, fileCache);
    }

    @Override
    protected Set<URI> preVisitParents(Set<URI> uris) {
        // phase 2: name resolution
        for( var uri : uris ) {
            var sourceUnit = getSourceUnit(uri);
            new ResolveVisitor(sourceUnit, compilationUnit).visit();
        }

        // phase 3: include resolution
        var changedUris = new HashSet<>(uris);

        for( var sourceUnit : getSourceUnits() ) {
            var visitor = new ResolveIncludeVisitor(sourceUnit, this, uris);
            visitor.visit();

            var uri = sourceUnit.getSource().getURI();
            if( visitor.isChanged() && !uris.contains(uri) ) {
                var errorCollector = (LanguageServerErrorCollector) sourceUnit.getErrorCollector();
                errorCollector.updatePhase(Phases.INCLUDE_RESOLUTION, visitor.getErrors());
                changedUris.add(uri);
            }
        }

        return changedUris;
    }

    @Override
    protected Map<ASTNode, ASTNode> visitParents(SourceUnit sourceUnit) {
        var visitor = new Visitor(sourceUnit);
        visitor.visit();
        return visitor.getLookup().getParents();
    }

    @Override
    protected void postVisitParents(Set<URI> uris) {
        // phase 4: type inference
        for( var uri : uris ) {
            var sourceUnit = getSourceUnit(uri);
            var visitor = new MethodCallVisitor(sourceUnit, this);
            visitor.visit();
        }
    }

    public List<IncludeNode> getIncludeNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        if( scriptNode == null )
            return Collections.emptyList();
        return scriptNode.getIncludes();
    }

    public List<MethodNode> getDefinitions() {
        var result = new ArrayList<MethodNode>();
        result.addAll(getFunctionNodes());
        result.addAll(getProcessNodes());
        result.addAll(getWorkflowNodes());
        return result;
    }

    public List<MethodNode> getDefinitions(URI uri) {
        var result = new ArrayList<MethodNode>();
        result.addAll(getFunctionNodes(uri));
        result.addAll(getProcessNodes(uri));
        result.addAll(getWorkflowNodes(uri));
        return result;
    }

    public List<FunctionNode> getFunctionNodes() {
        var result = new ArrayList<FunctionNode>();
        for( var script : getScriptNodes() )
            result.addAll(script.getFunctions());
        return result;
    }

    public List<FunctionNode> getFunctionNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        if( scriptNode == null )
            return Collections.emptyList();
        return scriptNode.getFunctions();
    }

    public List<ProcessNode> getProcessNodes() {
        var result = new ArrayList<ProcessNode>();
        for( var script : getScriptNodes() )
            result.addAll(script.getProcesses());
        return result;
    }

    public List<ProcessNode> getProcessNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        if( scriptNode == null )
            return Collections.emptyList();
        return scriptNode.getProcesses();
    }

    public List<WorkflowNode> getWorkflowNodes() {
        var result = new ArrayList<WorkflowNode>();
        for( var script : getScriptNodes() )
            result.addAll(script.getWorkflows());
        return result;
    }

    public List<WorkflowNode> getWorkflowNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        if( scriptNode == null )
            return Collections.emptyList();
        return scriptNode.getWorkflows();
    }

    private List<ScriptNode> getScriptNodes() {
        var result = new ArrayList<ScriptNode>();
        for( var sourceUnit : getSourceUnits() ) {
            if( sourceUnit.getAST() instanceof ScriptNode sn )
                result.add(sn);
        }
        return result;
    }

    private ScriptNode getScriptNode(URI uri) {
        return (ScriptNode) getSourceUnit(uri).getAST();
    }

    private static class Visitor extends ScriptVisitorSupport {

        private SourceUnit sourceUnit;

        private ASTNodeLookup lookup = new ASTNodeLookup();

        public Visitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        public void visit() {
            var moduleNode = sourceUnit.getAST();
            if( moduleNode instanceof ScriptNode sn )
                super.visit(sn);
        }

        public ASTNodeLookup getLookup() {
            return lookup;
        }

        @Override
        public void visitFeatureFlag(FeatureFlagNode node) {
            lookup.push(node);
            try {
                visit(node.value);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitInclude(IncludeNode node) {
            lookup.push(node);
            try {
                visit(node.source);
                for( var module : node.modules )
                    visitIncludeVariable(module);
            }
            finally {
                lookup.pop();
            }
        }

        protected void visitIncludeVariable(IncludeVariable node) {
            lookup.push(node);
            try {
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitWorkflow(WorkflowNode node) {
            lookup.push(node);
            try {
                super.visitWorkflow(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitProcess(ProcessNode node) {
            lookup.push(node);
            try {
                super.visitProcess(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitFunction(FunctionNode node) {
            lookup.push(node);
            try {
                for( var parameter : node.getParameters() )
                    visitParameter(parameter);
                visit(node.getCode());
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitOutput(OutputNode node) {
            lookup.push(node);
            try {
                super.visitOutput(node);
            }
            finally {
                lookup.pop();
            }
        }

        // statements

        @Override
        public void visitBlockStatement(BlockStatement node) {
            lookup.push(node);
            try {
                super.visitBlockStatement(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitIfElse(IfStatement node) {
            lookup.push(node);
            try {
                super.visitIfElse(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitExpressionStatement(ExpressionStatement node) {
            lookup.push(node);
            try {
                super.visitExpressionStatement(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitReturnStatement(ReturnStatement node) {
            lookup.push(node);
            try {
                super.visitReturnStatement(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitAssertStatement(AssertStatement node) {
            lookup.push(node);
            try {
                super.visitAssertStatement(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitTryCatchFinally(TryCatchStatement node) {
            lookup.push(node);
            try {
                super.visitTryCatchFinally(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitThrowStatement(ThrowStatement node) {
            lookup.push(node);
            try {
                super.visitThrowStatement(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitCatchStatement(CatchStatement node) {
            lookup.push(node);
            try {
                super.visitCatchStatement(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitEmptyStatement(EmptyStatement node) {
            lookup.push(node);
            try {
                super.visitEmptyStatement(node);
            }
            finally {
                lookup.pop();
            }
        }

        // expressions

        @Override
        public void visitMethodCallExpression(MethodCallExpression node) {
            lookup.push(node);
            try {
                super.visitMethodCallExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression node) {
            lookup.push(node);
            try {
                super.visitStaticMethodCallExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression node) {
            lookup.push(node);
            try {
                super.visitConstructorCallExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitTernaryExpression(TernaryExpression node) {
            lookup.push(node);
            try {
                super.visitTernaryExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitShortTernaryExpression(ElvisOperatorExpression node) {
            lookup.push(node);
            try {
                // see CodeVisitorSupport::visitShortTernaryExpression()
                super.visitTernaryExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitBinaryExpression(BinaryExpression node) {
            lookup.push(node);
            try {
                super.visitBinaryExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitBooleanExpression(BooleanExpression node) {
            lookup.push(node);
            try {
                super.visitBooleanExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitClosureExpression(ClosureExpression node) {
            lookup.push(node);
            try {
                var parameters = node.getParameters();
                if( parameters != null ) {
                    for( var parameter : parameters )
                        visitParameter(parameter);
                }
                super.visitClosureExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        protected void visitParameter(Parameter node) {
            lookup.push(node);
            try {
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitTupleExpression(TupleExpression node) {
            lookup.push(node);
            try {
                super.visitTupleExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitMapExpression(MapExpression node) {
            lookup.push(node);
            try {
                super.visitMapExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression node) {
            lookup.push(node);
            try {
                super.visitMapEntryExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitListExpression(ListExpression node) {
            lookup.push(node);
            try {
                super.visitListExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitRangeExpression(RangeExpression node) {
            lookup.push(node);
            try {
                super.visitRangeExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitPropertyExpression(PropertyExpression node) {
            lookup.push(node);
            try {
                super.visitPropertyExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitFieldExpression(FieldExpression node) {
            lookup.push(node);
            try {
                super.visitFieldExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitConstantExpression(ConstantExpression node) {
            lookup.push(node);
            try {
                super.visitConstantExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitClassExpression(ClassExpression node) {
            lookup.push(node);
            try {
                super.visitClassExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitVariableExpression(VariableExpression node) {
            lookup.push(node);
            try {
                super.visitVariableExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitGStringExpression(GStringExpression node) {
            lookup.push(node);
            try {
                super.visitGStringExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitSpreadExpression(SpreadExpression node) {
            lookup.push(node);
            try {
                super.visitSpreadExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitSpreadMapExpression(SpreadMapExpression node) {
            lookup.push(node);
            try {
                super.visitSpreadMapExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitNotExpression(NotExpression node) {
            lookup.push(node);
            try {
                super.visitNotExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitUnaryMinusExpression(UnaryMinusExpression node) {
            lookup.push(node);
            try {
                super.visitUnaryMinusExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitUnaryPlusExpression(UnaryPlusExpression node) {
            lookup.push(node);
            try {
                super.visitUnaryPlusExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
            lookup.push(node);
            try {
                super.visitBitwiseNegationExpression(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitCastExpression(CastExpression node) {
            lookup.push(node);
            try {
                super.visitCastExpression(node);
            }
            finally {
                lookup.pop();
            }
        }
    }

}

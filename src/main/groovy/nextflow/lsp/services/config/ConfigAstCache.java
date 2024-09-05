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
package nextflow.lsp.services.config;

import java.net.URI;
import java.util.ArrayList;
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
import nextflow.config.v2.ConfigAssignNode;
import nextflow.config.v2.ConfigBlockNode;
import nextflow.config.v2.ConfigIncludeNode;
import nextflow.config.v2.ConfigIncompleteNode;
import nextflow.config.v2.ConfigNode;
import nextflow.config.v2.ConfigParserPluginFactory;
import nextflow.config.v2.ConfigVisitorSupport;
import org.codehaus.groovy.ast.ASTNode;;
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
import org.codehaus.groovy.syntax.SyntaxException;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigAstCache extends ASTNodeCache {

    private Compiler compiler;

    private CompilationUnit compilationUnit;

    public ConfigAstCache() {
        var config = createConfiguration();
        var classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
        compiler = new Compiler(config, classLoader);
        compilationUnit = new CompilationUnit(config, null, classLoader);
    }

    protected CompilerConfiguration createConfiguration() {
        var config = new CompilerConfiguration();
        config.setPluginFactory(new ConfigParserPluginFactory());
        return config;
    }

    @Override
    protected Map<URI, SourceUnit> compile(Set<URI> uris, FileCache fileCache) {
        // phase 1: syntax resolution
        var sources = compiler.compile(uris, fileCache);

        // phase 2: name resolution
        sources.forEach((uri, sourceUnit) -> {
            new ConfigSchemaVisitor(sourceUnit).visit();
        });

        // phase 3: include resolution
        var allSources = new ArrayList<>(sources.values());
        allSources.addAll(getSourceUnits());

        for( var sourceUnit : allSources ) {
            var visitor = new ResolveIncludeVisitor(sourceUnit, this, uris);
            visitor.visit();

            var uri = sourceUnit.getSource().getURI();
            if( visitor.isChanged() && !uris.contains(uri) ) {
                var errorCollector = (LanguageServerErrorCollector) sourceUnit.getErrorCollector();
                errorCollector.updatePhase(Phases.INCLUDE_RESOLUTION, visitor.getErrors());
                sources.put(uri, null);
            }
        }

        // phase 4: type inference
        // TODO

        return sources;
    }

    @Override
    protected Map<ASTNode, ASTNode> visitParents(SourceUnit sourceUnit) {
        var visitor = new Visitor(sourceUnit);
        visitor.visit();
        return visitor.getLookup().getParents();
    }

    private static class Visitor extends ConfigVisitorSupport {

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
            if( moduleNode instanceof ConfigNode cn )
                super.visit(cn);
        }

        public ASTNodeLookup getLookup() {
            return lookup;
        }

        @Override
        public void visitConfigAssign(ConfigAssignNode node) {
            lookup.push(node);
            try {
                super.visitConfigAssign(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitConfigBlock(ConfigBlockNode node) {
            lookup.push(node);
            try {
                super.visitConfigBlock(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitConfigInclude(ConfigIncludeNode node) {
            lookup.push(node);
            try {
                super.visitConfigInclude(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitConfigIncomplete(ConfigIncompleteNode node) {
            lookup.push(node);
            try {
                super.visitConfigIncomplete(node);
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

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.ast.ASTParentVisitor;
import nextflow.lsp.compiler.Compiler;
import nextflow.lsp.compiler.LanguageServerErrorCollector;
import nextflow.lsp.compiler.PhaseAware;
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
import org.codehaus.groovy.control.messages.WarningMessage;

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
        config.setWarningLevel(WarningMessage.POSSIBLE_ERRORS);
        return config;
    }

    @Override
    protected SourceUnit buildAST(URI uri, FileCache fileCache) {
        // phase 1: syntax resolution
        var sourceUnit = compiler.compile(uri, fileCache);

        // phase 2: name resolution
        if( sourceUnit != null )
            new ConfigSchemaVisitor(sourceUnit).visit();
        return sourceUnit;
    }

    @Override
    protected Map<ASTNode, ASTNode> visitParents(SourceUnit sourceUnit) {
        var visitor = new Visitor(sourceUnit);
        visitor.visit();
        return visitor.getParents();
    }

    @Override
    protected Set<URI> visitAST(Set<URI> uris) {
        // phase 3: include resolution
        var changedUris = new HashSet<>(uris);

        for( var sourceUnit : getSourceUnits() ) {
            var visitor = new ResolveIncludeVisitor(sourceUnit, this, uris);
            visitor.visit();

            var uri = sourceUnit.getSource().getURI();
            if( visitor.isChanged() ) {
                var errorCollector = (LanguageServerErrorCollector) sourceUnit.getErrorCollector();
                errorCollector.updatePhase(Phases.INCLUDE_RESOLUTION, visitor.getErrors());
                changedUris.add(uri);
            }
        }

        // phase 4: type inference
        // TODO

        return changedUris;
    }

    /**
     * Check whether a source file has any errors.
     *
     * @param uri
     */
    public boolean hasSyntaxErrors(URI uri) {
        return getErrors(uri).stream()
            .filter(error -> error instanceof PhaseAware pa ? pa.getPhase() == Phases.SYNTAX : true)
            .findFirst()
            .isPresent();
    }

    private static class Visitor extends ConfigVisitorSupport {

        private SourceUnit sourceUnit;

        private ASTParentVisitor lookup = new ASTParentVisitor();

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

        public Map<ASTNode, ASTNode> getParents() {
            return lookup.getParents();
        }

        @Override
        public void visitConfigAssign(ConfigAssignNode node) {
            lookup.push(node);
            try {
                lookup.visit(node.value);
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
                lookup.visit(node.source);
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
    }

}

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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.ast.ASTParentVisitor;
import nextflow.lsp.compiler.Compiler;
import nextflow.lsp.compiler.LanguageServerErrorCollector;
import nextflow.lsp.file.FileCache;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.IncludeVariable;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ParamNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import nextflow.script.control.ResolveVisitor;
import nextflow.script.control.VariableScopeVisitor;
import nextflow.script.parser.ScriptParserPluginFactory;
import nextflow.script.types.Types;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptAstCache extends ASTNodeCache {

    private Compiler compiler;

    private CompilationUnit compilationUnit;

    public void initialize(String rootUri) {
        var config = createConfiguration();
        var libDir = Path.of(URI.create(rootUri)).resolve("lib");
        if( Files.isDirectory(libDir) )
            config.setClasspath(libDir.toString());
        var classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config, true);
        compiler = new Compiler(config, classLoader);
        compilationUnit = new CompilationUnit(config, null, classLoader);
    }

    private CompilerConfiguration createConfiguration() {
        var config = new CompilerConfiguration();
        config.setPluginFactory(new ScriptParserPluginFactory());
        config.setWarningLevel(WarningMessage.POSSIBLE_ERRORS);

        var optimizationOptions = config.getOptimizationOptions();
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);

        return config;
    }

    @Override
    protected SourceUnit buildAST(URI uri, FileCache fileCache) {
        // phase 1: syntax resolution
        var sourceUnit = compiler.compile(uri, fileCache);

        // phase 2: name resolution
        // NOTE: must be done before visiting parents because it transforms nodes
        if( sourceUnit != null ) {
            new ResolveVisitor(sourceUnit, compilationUnit, Types.TYPES).visit();
            new ParameterSchemaVisitor(sourceUnit).visit();
        }
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
        for( var uri : changedUris ) {
            var sourceUnit = getSourceUnit(uri);
            if( sourceUnit == null )
                continue;
            if( sourceUnit.getErrorCollector().hasErrors() )
                continue;
            var visitor = new MethodCallVisitor(sourceUnit, this);
            visitor.visit();
        }

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

    public List<ScriptNode> getScriptNodes() {
        var result = new ArrayList<ScriptNode>();
        for( var sourceUnit : getSourceUnits() ) {
            if( sourceUnit.getAST() instanceof ScriptNode sn )
                result.add(sn);
        }
        return result;
    }

    public ScriptNode getScriptNode(URI uri) {
        return (ScriptNode) getSourceUnit(uri).getAST();
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

    public List<ClassNode> getEnumNodes() {
        var result = new ArrayList<ClassNode>();
        for( var uri : getUris() )
            result.addAll(getEnumNodes(uri));
        return result;
    }

    public List<ClassNode> getEnumNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        if( scriptNode == null )
            return Collections.emptyList();
        var result = new ArrayList<ClassNode>();
        for( var cn : scriptNode.getClasses() ) {
            if( cn.isEnum() )
                result.add(cn);
        }
        return result;
    }

    private static class Visitor extends ScriptVisitorSupport {

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
            if( moduleNode instanceof ScriptNode sn )
                super.visit(sn);
        }

        public Map<ASTNode, ASTNode> getParents() {
            return lookup.getParents();
        }

        @Override
        public void visitFeatureFlag(FeatureFlagNode node) {
            lookup.push(node);
            try {
                lookup.visit(node.value);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitInclude(IncludeNode node) {
            lookup.push(node);
            try {
                lookup.visit(node.source);
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
        public void visitParam(ParamNode node) {
            lookup.push(node);
            try {
                lookup.visit(node.value);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitWorkflow(WorkflowNode node) {
            lookup.push(node);
            try {
                lookup.visit(node.takes);
                lookup.visit(node.main);
                lookup.visit(node.emits);
                lookup.visit(node.publishers);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitProcess(ProcessNode node) {
            lookup.push(node);
            try {
                lookup.visit(node.directives);
                lookup.visit(node.inputs);
                lookup.visit(node.outputs);
                lookup.visit(node.when);
                lookup.visit(node.exec);
                lookup.visit(node.stub);
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
                    lookup.visitParameter(parameter);
                lookup.visit(node.getCode());
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitEnum(ClassNode node) {
            lookup.push(node);
            try {
                super.visitEnum(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitField(FieldNode node) {
            lookup.push(node);
            try {
                super.visitField(node);
            }
            finally {
                lookup.pop();
            }
        }

        @Override
        public void visitOutput(OutputNode node) {
            lookup.push(node);
            try {
                lookup.visit(node.body);
            }
            finally {
                lookup.pop();
            }
        }
    }

}

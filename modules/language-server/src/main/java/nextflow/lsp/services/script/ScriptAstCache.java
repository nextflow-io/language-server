/*
 * Copyright 2024-2025, Seqera Labs
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
import nextflow.script.control.ScriptResolveVisitor;
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

    private String rootUri;

    public ScriptAstCache() {
        var config = createConfiguration();
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

    public void initialize(String rootUri) {
        this.rootUri = rootUri;
    }

    @Override
    protected SourceUnit buildAST(URI uri, FileCache fileCache) {
        // compile Groovy classes in lib directory
        var libImports = getLibImports();

        // phase 1: syntax resolution
        var sourceUnit = compiler.compile(uri, fileCache);

        // phase 2: name resolution
        // NOTE: must be done before visiting parents because it transforms nodes
        if( sourceUnit != null ) {
            new ScriptResolveVisitor(sourceUnit, compilationUnit, Types.TYPES, libImports).visit();
            new ParameterSchemaVisitor(sourceUnit).visit();
        }
        return sourceUnit;
    }

    private static class Entry {
        FileTime lastModified;
        List<ClassNode> classes;

        Entry(FileTime lastModified) {
            this.lastModified = lastModified;
        }
    }

    private Map<URI,Entry> libCache = new HashMap<>();

    private List<ClassNode> getLibImports() {
        if( rootUri == null )
            return Collections.emptyList();

        // collect Groovy files in lib directory
        var libDir = Path.of(URI.create(rootUri)).resolve("lib");
        if( !Files.isDirectory(libDir) )
            return Collections.emptyList();

        Set<URI> uris;
        try {
            uris = Files.walk(libDir)
                .filter(path -> path.toString().endsWith(".groovy"))
                .map(path -> path.toUri())
                .collect(Collectors.toSet());
        }
        catch( IOException e ) {
            System.err.println("Failed to read Groovy source files in lib directory: " + e.toString());
            return Collections.emptyList();
        }

        if( uris.isEmpty() )
            return Collections.emptyList();

        // compile source files
        var cachedClasses = new ArrayList<ClassNode>();
        var config = new CompilerConfiguration();
        config.getOptimizationOptions().put(CompilerConfiguration.GROOVYDOC, true);
        var classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config, true);
        var compilationUnit = new CompilationUnit(config, null, classLoader);
        for( var uri : uris ) {
            var lastModified = getLastModified(uri);
            if( libCache.containsKey(uri) ) {
                var entry = libCache.get(uri);
                if( lastModified != null && lastModified.equals(entry.lastModified) ) {
                    if( entry.classes != null )
                        cachedClasses.addAll(entry.classes);
                    continue;
                }
            }

            System.err.println("compile " + uri.toString());
            var sourceUnit = new SourceUnit(
                    new File(uri),
                    config,
                    classLoader,
                    new LanguageServerErrorCollector(config));
            compilationUnit.addSource(sourceUnit);
            libCache.put(uri, new Entry(lastModified));
        }

        try {
            compilationUnit.compile(org.codehaus.groovy.control.Phases.CANONICALIZATION);
        }
        catch( CompilationFailedException e ) {
            // ignore
        }
        catch( GroovyBugError | Exception e ) {
            System.err.println("Failed to compile Groovy source files in lib directory -- " + e.toString());
        }

        // collect class nodes and report errors
        var result = new ArrayList<ClassNode>();
        result.addAll(cachedClasses);
        compilationUnit.iterator().forEachRemaining((sourceUnit) -> {
            var uri = sourceUnit.getSource().getURI();
            var errors = sourceUnit.getErrorCollector().getErrors();
            if( errors != null ) {
                for( var error : errors ) {
                    if( !(error instanceof SyntaxErrorMessage) )
                        continue;
                    var sem = (SyntaxErrorMessage) error;
                    var cause = sem.getCause();
                    System.err.println(String.format("Groovy syntax error in %s -- %s: %s", uri, cause, cause.getMessage()));
                }
            }

            var moduleNode = sourceUnit.getAST();
            if( moduleNode == null )
                return;
            var packageName = libDir
                .relativize(Path.of(uri).getParent())
                .toString()
                .replaceAll("/", ".");
            moduleNode.setPackageName(packageName);
            for( var cn : moduleNode.getClasses() ) {
                var className = packageName.isEmpty()
                    ? cn.getNameWithoutPackage()
                    : packageName + "." + cn.getNameWithoutPackage();
                cn.setName(className);
            }
            result.addAll(moduleNode.getClasses());

            var entry = libCache.get(uri);
            entry.classes = moduleNode.getClasses();
        });
        return result;
    }

    private FileTime getLastModified(URI uri) {
        try {
            return Files.getLastModifiedTime(Path.of(uri));
        }
        catch( IOException e ) {
            System.err.println(String.format("Failed to get last modified time for %s -- %s", uri, e));
            return null;
        }
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

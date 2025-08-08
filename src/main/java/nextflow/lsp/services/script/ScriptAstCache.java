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

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.compiler.LanguageServerCompiler;
import nextflow.lsp.compiler.LanguageServerErrorCollector;
import nextflow.lsp.file.FileCache;
import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.control.ModuleResolver;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import nextflow.script.control.ResolveIncludeVisitor;
import nextflow.script.control.ScriptResolveVisitor;
import nextflow.script.control.TypeCheckingVisitorEx;
import nextflow.script.parser.ScriptParserPluginFactory;
import nextflow.script.types.Types;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptAstCache extends ASTNodeCache {

    private GroovyLibCache libCache;

    private LanguageServerConfiguration configuration;

    public ScriptAstCache(String rootUri) {
        super(createCompiler());
        this.libCache = createLibCache(rootUri);
    }

    private static GroovyLibCache createLibCache(String rootUri) {
        if( rootUri == null )
            return null;
        var libDir = Path.of(URI.create(rootUri)).resolve("lib");
        return new GroovyLibCache(libDir);
    }

    private static LanguageServerCompiler createCompiler() {
        var config = createConfiguration();
        var classLoader = new GroovyClassLoader();
        return new LanguageServerCompiler(config, classLoader);
    }

    private static CompilerConfiguration createConfiguration() {
        var config = new CompilerConfiguration();
        config.setPluginFactory(new ScriptParserPluginFactory());
        config.setWarningLevel(WarningMessage.POSSIBLE_ERRORS);

        var optimizationOptions = config.getOptimizationOptions();
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);

        return config;
    }

    public void initialize(LanguageServerConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected Set<URI> analyze(Set<URI> uris, FileCache fileCache) {
        // recursively load included modules
        var changedUris = new HashSet<>(uris);

        for( var uri : uris ) {
            var source = compiler().getSource(uri);
            new ModuleResolver(compiler()).resolve(source, (newUri) -> {
                changedUris.add(newUri);
                return compiler().createSourceUnit(newUri, fileCache);
            });
        }

        // phase 2: include checking
        for( var sourceUnit : getSourceUnits() ) {
            var visitor = new ResolveIncludeVisitor(sourceUnit, compiler(), uris);
            visitor.visit();

            var uri = sourceUnit.getSource().getURI();
            if( visitor.isChanged() ) {
                var errorCollector = (LanguageServerErrorCollector) sourceUnit.getErrorCollector();
                errorCollector.updatePhase(Phases.INCLUDE_RESOLUTION, visitor.getErrors());
                changedUris.add(uri);
            }
        }

        var libImports = libImports();

        for( var uri : changedUris ) {
            var sourceUnit = getSourceUnit(uri);
            if( sourceUnit == null )
                continue;
            // phase 3: name checking
            new ScriptResolveVisitor(sourceUnit, compiler().compilationUnit(), Types.DEFAULT_SCRIPT_IMPORTS, libImports).visit();
            new ParameterSchemaVisitor(sourceUnit).visit();
            if( sourceUnit.getErrorCollector().hasErrors() )
                continue;
            // phase 4: type checking
            new TypeCheckingVisitorEx(sourceUnit, configuration.typeChecking()).visit();
        }

        return changedUris;
    }

    private List<ClassNode> libImports() {
        return libCache != null ? libCache.refresh() : Collections.emptyList();
    }

    @Override
    protected Map<ASTNode, ASTNode> visitParents(SourceUnit sourceUnit) {
        var visitor = new ScriptAstParentVisitor(sourceUnit);
        visitor.visit();
        return visitor.getParents();
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

    public List<ClassNode> getTypeNodes() {
        var result = new ArrayList<ClassNode>();
        for( var uri : getUris() )
            result.addAll(getTypeNodes(uri));
        return result;
    }

    public List<ClassNode> getTypeNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        if( scriptNode == null )
            return Collections.emptyList();
        return scriptNode.getTypes();
    }

}

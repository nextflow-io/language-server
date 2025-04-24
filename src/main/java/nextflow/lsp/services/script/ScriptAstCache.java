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
import nextflow.script.control.TypeCheckingVisitor;
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

    private String rootUri;

    private LanguageServerConfiguration configuration;

    private GroovyLibCache libCache = new GroovyLibCache();

    public ScriptAstCache() {
        super(createCompiler());
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

    public void initialize(String rootUri, LanguageServerConfiguration configuration) {
        this.rootUri = rootUri;
        this.configuration = configuration;
    }

    @Override
    protected Set<URI> analyze(Set<URI> uris, FileCache fileCache) {
        // recursively load included modules
        for( var uri : uris ) {
            var source = compiler().getSource(uri);
            new ModuleResolver(compiler()).resolve(source, (includeUri) -> compiler().createSourceUnit(includeUri, fileCache));
        }

        // phase 2: include checking
        var changedUris = new HashSet<>(uris);

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

        var libImports = libCache.load(rootUri);

        for( var uri : changedUris ) {
            var sourceUnit = getSourceUnit(uri);
            if( sourceUnit == null )
                continue;
            // phase 3: name checking
            new ScriptResolveVisitor(sourceUnit, compiler().compilationUnit(), Types.DEFAULT_IMPORTS, libImports).visit();
            new ParameterSchemaVisitor(sourceUnit).visit();
            if( sourceUnit.getErrorCollector().hasErrors() )
                continue;
            // phase 4: type checking
            new TypeCheckingVisitor(sourceUnit, configuration.typeChecking()).visit();
        }

        return changedUris;
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

}

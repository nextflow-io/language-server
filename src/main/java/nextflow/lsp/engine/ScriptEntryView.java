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
package nextflow.lsp.engine;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nextflow.lsp.ast.ScriptView;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;

/**
 * View of the built scripts of a project.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class ScriptEntryView extends EntryView implements ScriptView {

    ScriptEntryView(Engine engine, Project project) {
        super(engine, project, FileKind.SCRIPT);
    }

    @Override
    public Path getProjectDir() {
        entries();
        return project.root();
    }

    @Override
    public List<ScriptNode> getScriptNodes() {
        var result = new ArrayList<ScriptNode>();
        for( var entry : entries().values() ) {
            if( entry.unit.getAST() instanceof ScriptNode sn )
                result.add(sn);
        }
        return result;
    }

    @Override
    public ScriptNode getScriptNode(URI uri) {
        var sourceUnit = getSourceUnit(uri);
        return sourceUnit != null && sourceUnit.getAST() instanceof ScriptNode sn ? sn : null;
    }

    @Override
    public List<IncludeNode> getIncludeNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        return scriptNode != null ? scriptNode.getIncludes() : List.of();
    }

    @Override
    public List<AnnotatedNode> getDefinitions() {
        var result = new ArrayList<AnnotatedNode>();
        result.addAll(getFunctionNodes());
        result.addAll(getProcessNodes());
        result.addAll(getWorkflowNodes());
        result.addAll(getTypeNodes());
        return result;
    }

    @Override
    public List<AnnotatedNode> getDefinitions(URI uri) {
        var result = new ArrayList<AnnotatedNode>();
        result.addAll(getFunctionNodes(uri));
        result.addAll(getProcessNodes(uri));
        result.addAll(getWorkflowNodes(uri));
        result.addAll(getTypeNodes(uri));
        return result;
    }

    @Override
    public List<WorkflowNode> getWorkflowNodes() {
        var result = new ArrayList<WorkflowNode>();
        for( var script : getScriptNodes() )
            result.addAll(script.getWorkflows());
        return result;
    }

    @Override
    public List<WorkflowNode> getWorkflowNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        return scriptNode != null ? scriptNode.getWorkflows() : List.of();
    }

    @Override
    public List<ProcessNode> getProcessNodes() {
        var result = new ArrayList<ProcessNode>();
        for( var script : getScriptNodes() )
            result.addAll(script.getProcesses());
        return result;
    }

    @Override
    public List<ProcessNode> getProcessNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        return scriptNode != null ? scriptNode.getProcesses() : List.of();
    }

    @Override
    public List<FunctionNode> getFunctionNodes() {
        var result = new ArrayList<FunctionNode>();
        for( var script : getScriptNodes() )
            result.addAll(script.getFunctions());
        return result;
    }

    @Override
    public List<FunctionNode> getFunctionNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        return scriptNode != null ? scriptNode.getFunctions() : List.of();
    }

    @Override
    public List<ClassNode> getTypeNodes() {
        var result = new ArrayList<ClassNode>();
        for( var script : getScriptNodes() )
            result.addAll(script.getTypes());
        return result;
    }

    @Override
    public List<ClassNode> getTypeNodes(URI uri) {
        var scriptNode = getScriptNode(uri);
        return scriptNode != null ? scriptNode.getTypes() : List.of();
    }

}

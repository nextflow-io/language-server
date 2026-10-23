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
package nextflow.lsp.ast;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;

/**
 * Read-only view of the built scripts of a project.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface ScriptView extends AstView {

    /**
     * Get the project directory, or null for files outside of
     * every workspace folder.
     */
    Path getProjectDir();

    /**
     * Check whether a file has syntax errors.
     *
     * @param uri
     */
    boolean hasSyntaxErrors(URI uri);

    List<ScriptNode> getScriptNodes();

    /**
     * Get the AST of a script, or null if the script is unknown.
     *
     * @param uri
     */
    ScriptNode getScriptNode(URI uri);

    List<IncludeNode> getIncludeNodes(URI uri);

    List<AnnotatedNode> getDefinitions();

    List<AnnotatedNode> getDefinitions(URI uri);

    List<WorkflowNode> getWorkflowNodes();

    List<WorkflowNode> getWorkflowNodes(URI uri);

    List<ProcessNode> getProcessNodes();

    List<ProcessNode> getProcessNodes(URI uri);

    List<FunctionNode> getFunctionNodes();

    List<FunctionNode> getFunctionNodes(URI uri);

    List<ClassNode> getTypeNodes();

    List<ClassNode> getTypeNodes(URI uri);

}

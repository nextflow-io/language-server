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
package nextflow.script.v2;

import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.IncludeNode;
import nextflow.script.v2.OutputNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.ScriptNode;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.stmt.Statement;

public interface ScriptVisitor {

    default void visit(ScriptNode script) {
        for( var featureFlag : script.getFeatureFlags() )
            visitFeatureFlag(featureFlag);
        for( var includeNode : script.getIncludes() )
            visitInclude(includeNode);
        for( var functionNode : script.getFunctions() )
            visitFunction(functionNode);
        for( var processNode : script.getProcesses() )
            visitProcess(processNode);
        for( var workflowNode : script.getWorkflows() )
            visitWorkflow(workflowNode);
        if( script.getOutput() != null )
            visitOutput(script.getOutput());
    }

    default void visitFeatureFlag(FeatureFlagNode node) {}

    default void visitFunction(FunctionNode node) {
        visit(node.getCode());
    }

    default void visitInclude(IncludeNode node) {
        visit(node.source);
    }

    default void visitProcess(ProcessNode node) {
        visit(node.directives);
        visit(node.inputs);
        visit(node.outputs);
        visit(node.when);
        visit(node.exec);
        visit(node.stub);
    }

    default void visitWorkflow(WorkflowNode node) {
        visit(node.takes);
        visit(node.main);
        visit(node.emits);
        visit(node.publishers);
    }

    default void visitOutput(OutputNode node) {
        visit(node.body);
    }

    void visit(Statement node);
    void visit(Expression node);

}

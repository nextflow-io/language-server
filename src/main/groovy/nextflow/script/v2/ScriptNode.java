/*
 * Copyright 2013-2024, Seqera Labs
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

import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.SourceUnit;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptNode extends ModuleNode {
    private List<FeatureFlagNode> featureFlags = new ArrayList<>();
    private List<IncludeNode> includes = new ArrayList<>();
    private List<FunctionNode> functions = new ArrayList<>();
    private List<ProcessNode> processes = new ArrayList<>();
    private List<WorkflowNode> workflows = new ArrayList<>();
    private WorkflowNode entry;
    private OutputNode output;

    public ScriptNode(SourceUnit sourceUnit) {
        super(sourceUnit);
    }

    public List<FeatureFlagNode> getFeatureFlags() {
        return featureFlags;
    }

    public List<IncludeNode> getIncludes() {
        return includes;
    }

    public List<FunctionNode> getFunctions() {
        return functions;
    }

    public List<ProcessNode> getProcesses() {
        return processes;
    }

    public List<WorkflowNode> getWorkflows() {
        return workflows;
    }

    public WorkflowNode getEntry() {
        return entry;
    }

    public OutputNode getOutput() {
        return output;
    }

    public void addFeatureFlag(FeatureFlagNode featureFlag) {
        featureFlags.add(featureFlag);
    }

    public void addInclude(IncludeNode includeNode) {
        includes.add(includeNode);
    }

    public void addFunction(FunctionNode functionNode) {
        functions.add(functionNode);
    }

    public void addProcess(ProcessNode processNode) {
        processes.add(processNode);
    }

    public void addWorkflow(WorkflowNode workflowNode) {
        workflows.add(workflowNode);
    }

    public void setEntry(WorkflowNode entry) {
        this.entry = entry;
    }

    public void setOutput(OutputNode output) {
        this.output = output;
    }
}
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
package nextflow.script.v2

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.SourceUnit

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptNode extends ModuleNode {
    private List<FeatureFlagNode> featureFlags = []
    private List<IncludeNode> includes = []
    private List<FunctionNode> functions = []
    private List<ProcessNode> processes = []
    private List<WorkflowNode> workflows = []
    private WorkflowNode entry
    private OutputNode output

    ScriptNode(SourceUnit sourceUnit) {
        super(sourceUnit)
    }

    List<FeatureFlagNode> getFeatureFlags() {
        return featureFlags
    }

    List<IncludeNode> getIncludes() {
        return includes
    }

    List<FunctionNode> getFunctions() {
        return functions
    }

    List<ProcessNode> getProcesses() {
        return processes
    }

    List<WorkflowNode> getWorkflows() {
        return workflows
    }

    WorkflowNode getEntry() {
        return entry
    }

    OutputNode getOutput() {
        return output
    }

    void addFeatureFlag(FeatureFlagNode featureFlag) {
        featureFlags << featureFlag
    }

    void addInclude(IncludeNode includeNode) {
        includes << includeNode
    }

    void addFunction(FunctionNode functionNode) {
        functions << functionNode
    }

    void addProcess(ProcessNode processNode) {
        processes << processNode
    }

    void addWorkflow(WorkflowNode workflowNode) {
        workflows << workflowNode
    }

    void setEntry(WorkflowNode entry) {
        this.entry = entry
    }

    void setOutput(OutputNode output) {
        this.output = output
    }
}

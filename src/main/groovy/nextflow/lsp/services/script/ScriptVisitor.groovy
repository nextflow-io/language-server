package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.script.v2.FeatureFlagNode
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.IncludeNode
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.control.SourceUnit

@CompileStatic
interface ScriptVisitor {

    default void visit(ScriptNode script) {
        for( final featureFlag : script.getFeatureFlags() )
            visitFeatureFlag(featureFlag)
        for( final functionNode : script.getFunctions() )
            visitFunction(functionNode)
        for( final processNode : script.getProcesses() )
            visitProcess(processNode)
        for( final workflowNode : script.getWorkflows() )
            visitWorkflow(workflowNode)
        if( script.getOutput() )
            visitOutput(script.getOutput())
    }

    void visitFeatureFlag(FeatureFlagNode node)

    void visitFunction(FunctionNode node)

    void visitInclude(IncludeNode node)

    void visitProcess(ProcessNode node)

    void visitWorkflow(WorkflowNode node)

    void visitOutput(OutputNode node)

}

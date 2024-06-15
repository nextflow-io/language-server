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

    default void visitFeatureFlag(FeatureFlagNode node) {}

    default void visitFunction(FunctionNode node) {}

    default void visitInclude(IncludeNode node) {}

    default void visitProcess(ProcessNode node) {}

    default void visitWorkflow(WorkflowNode node) {}

    default void visitOutput(OutputNode node) {}

}

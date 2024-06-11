package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.IncludeNode
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.control.SourceUnit

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptAstCache extends ASTNodeCache {

    ScriptAstCache(Compiler compiler) {
        super(compiler)
    }

    protected ASTNodeCache.Visitor createVisitor(SourceUnit sourceUnit) {
        return new Visitor(sourceUnit)
    }

    List<IncludeNode> getIncludeNodes(URI uri) {
        return getScriptNode(uri).getIncludes()
    }

    List<FunctionNode> getFunctionNodes() {
        final List<FunctionNode> result = []
        for( final script : getScriptNodes() )
            result.addAll(script.getFunctions())
        return result
    }

    List<FunctionNode> getFunctionNodes(URI uri) {
        return getScriptNode(uri).getFunctions()
    }

    List<ProcessNode> getProcessNodes() {
        final List<ProcessNode> result = []
        for( final script : getScriptNodes() )
            result.addAll(script.getProcesses())
        return result
    }

    List<ProcessNode> getProcessNodes(URI uri) {
        return getScriptNode(uri).getProcesses()
    }

    List<WorkflowNode> getWorkflowNodes() {
        final List<WorkflowNode> result = []
        for( final script : getScriptNodes() )
            result.addAll(script.getWorkflows())
        return result
    }

    List<WorkflowNode> getWorkflowNodes(URI uri) {
        return getScriptNode(uri).getWorkflows()
    }

    private List<ScriptNode> getScriptNodes() {
        final List<ScriptNode> result = []
        for( final sourceUnit : getSourceUnits() )
            result << (ScriptNode) sourceUnit.getAST()
        return result
    }

    private ScriptNode getScriptNode(URI uri) {
        return (ScriptNode) getSourceUnit(uri).getAST()
    }

    private class Visitor extends ASTNodeCache.Visitor {

        Visitor(SourceUnit sourceUnit) {
            super(sourceUnit)
        }

        @Override
        void visit() {
            final moduleNode = (ScriptNode) sourceUnit.getAST()
            if( moduleNode == null )
                return
            for( final featureFlag : moduleNode.getFeatureFlags() )
                visit(featureFlag)
            for( final includeNode : moduleNode.getIncludes() )
                visitInclude(includeNode)
            for( final functionNode : moduleNode.getFunctions() )
                visitFunction(functionNode)
            for( final processNode : moduleNode.getProcesses() )
                visitProcess(processNode)
            for( final workflowNode : moduleNode.getWorkflows() )
                visitWorkflow(workflowNode)
            if( moduleNode.getOutput() )
                visitOutput(moduleNode.getOutput())
        }

        protected void visitInclude(IncludeNode node) {
            pushASTNode(node)
            try {
                visit(node.expression)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitFunction(FunctionNode node) {
            pushASTNode(node)
            try {
                visit(node.code)
                for( final parameter : node.getParameters() )
                    visitParameter(parameter)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitParameter(Parameter node) {
            pushASTNode(node)
            try {
            }
            finally {
                popASTNode()
            }
        }

        protected void visitProcess(ProcessNode node) {
            pushASTNode(node)
            try {
                visit(node.directives)
                visit(node.inputs)
                visit(node.outputs)
                visit(node.exec)
                visit(node.stub)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitWorkflow(WorkflowNode node) {
            pushASTNode(node)
            try {
                visit(node.takes)
                visit(node.emits)
                visit(node.main)
            }
            finally {
                popASTNode()
            }
        }

        protected void visitOutput(OutputNode node) {
            pushASTNode(node)
            try {
                visit(node.body)
            }
            finally {
                popASTNode()
            }
        }
    }

}

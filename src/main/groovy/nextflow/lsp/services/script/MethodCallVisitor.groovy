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
package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTUtils
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.ScriptVisitor
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException

/**
 * Validate process and workflow invocations.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class MethodCallVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

    private SourceUnit sourceUnit

    private ScriptAstCache astCache

    private List<SyntaxException> errors = []

    MethodCallVisitor(SourceUnit sourceUnit, ScriptAstCache astCache) {
        this.sourceUnit = sourceUnit
        this.astCache = astCache
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode !instanceof ScriptNode )
            return
        visit((ScriptNode) moduleNode)
    }

    private boolean inWorkflow

    @Override
    void visitWorkflow(WorkflowNode node) {
        inWorkflow = true
        super.visitWorkflow(node)
        inWorkflow = false
    }

    @Override
    void visitMethodCallExpression(MethodCallExpression node) {
        checkMethodCall(node)
        super.visitMethodCallExpression(node)
    }

    protected void checkMethodCall(MethodCallExpression node) {
        final defNode = ASTUtils.getMethodFromCallExpression(node, astCache)
        if( !defNode )
            return
        if( defNode !instanceof ProcessNode && defNode !instanceof WorkflowNode )
            return
        if( !inWorkflow ) {
            addError("${defNode instanceof ProcessNode ? 'Processes' : 'Workflows'} can only be called from a workflow", node)
            return
        }
        if( inClosure ) {
            addError("${defNode instanceof ProcessNode ? 'Processes' : 'Workflows'} cannot be called from within a closure", node)
            return
        }
        final argsCount = ((ArgumentListExpression) node.arguments).size()
        final paramsCount = getNumberOfParameters(defNode)
        if( argsCount != paramsCount )
            addError("Incorrect number of call arguments, expected ${paramsCount} but received ${argsCount}", node)
    }

    protected static int getNumberOfParameters(MethodNode node) {
        if( node instanceof ProcessNode ) {
            if( node.inputs !instanceof BlockStatement )
                return 0
            final code = (BlockStatement) node.inputs
            return code.statements.size()
        }
        if( node instanceof WorkflowNode ) {
            if( node.takes !instanceof BlockStatement )
                return 0
            final code = (BlockStatement) node.takes
            return code.statements.size()
        }
        return node.parameters.length
    }

    private boolean inClosure

    @Override
    void visitClosureExpression(ClosureExpression node) {
        final ic = inClosure
        inClosure = true
        super.visitClosureExpression(node)
        inClosure = ic
    }

    @Override
    void addError(String message, ASTNode node) {
        errors.add(new MethodCallException(message, node))
    }

    List<SyntaxException> getErrors() {
        return errors
    }
}

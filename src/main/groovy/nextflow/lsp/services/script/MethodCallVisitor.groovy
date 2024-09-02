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
import nextflow.script.dsl.Operator
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.ScriptVisitorSupport
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Types

/**
 * Validate process and workflow invocations.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class MethodCallVisitor extends ScriptVisitorSupport {

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
        final argsCount = ((TupleExpression) node.arguments).size()
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
    void visitBinaryExpression(BinaryExpression node) {
        if( node.getOperation().getType() == Types.PIPE ) {
            checkPipeline(node)
        }

        super.visitBinaryExpression(node)
    }

    private void checkPipeline(BinaryExpression node) {
        final lhs = node.leftExpression
        final rhs = node.rightExpression

        if( lhs instanceof VariableExpression ) {
            final defNode = ASTUtils.getDefinition(lhs, false, astCache)
            if( defNode instanceof MethodNode )
                addError("Invalid pipe expression -- left-hand side cannot be a callable", lhs)
        }

        if( rhs instanceof MethodCallExpression ) {
            final defNode = ASTUtils.getMethodFromCallExpression(rhs, astCache)
            if( defNode != null && !isOperator(defNode) )
                addError("Invalid pipe expression -- only operators can be curried", rhs)
        }
    }

    private boolean isOperator(MethodNode mn) {
        return mn.getAnnotations().stream()
            .filter(an -> an.getClassNode().getTypeClass() == Operator)
            .findFirst()
            .isPresent()
    }

    @Override
    void visitPropertyExpression(PropertyExpression node) {
        final mn = asOutputProperty(node)
        if( mn instanceof ProcessNode )
            checkProcessOut(node, mn)
        else if( mn instanceof WorkflowNode )
            checkWorkflowOut(node, mn)
        else
            super.visitPropertyExpression(node)
    }

    private MethodNode asOutputProperty(PropertyExpression node) {
        if( node.getObjectExpression() instanceof PropertyExpression ) {
            final pe = (PropertyExpression) node.getObjectExpression()
            if( pe.getObjectExpression() instanceof VariableExpression && pe.getPropertyAsString() == 'out' ) {
                final defNode = ASTUtils.getDefinition(pe.getObjectExpression(), false, astCache)
                if( defNode instanceof MethodNode )
                    return defNode
            }
        }
        return null
    }

    private void checkProcessOut(PropertyExpression node, ProcessNode process) {
        final property = node.getPropertyAsString()
        if( process.outputs instanceof BlockStatement ) {
            final block = (BlockStatement) process.outputs
            for( final stmt : block.statements ) {
                final stmtX = (ExpressionStatement)stmt
                final call = (MethodCallExpression)stmtX.expression
                if( property == getProcessEmitName(call) )
                    return
            }
        }
        addError("Unrecognized output `${property}` for process `${process.getName()}`", node)
    }

    private String getProcessEmitName(MethodCallExpression output) {
        if( output.arguments !instanceof TupleExpression )
            return null
        final args = (TupleExpression) output.arguments
        if( args.size() == 0 || args.getExpression(0) !instanceof MapExpression )
            return null
        final namedArgs = (MapExpression) args.getExpression(0)
        return namedArgs.mapEntryExpressions.stream()
            .filter(entry -> ((ConstantExpression) entry.keyExpression).text == 'emit')
            .findFirst()
            .map((entry) -> {
                entry.valueExpression instanceof VariableExpression
                    ? ((VariableExpression) entry.valueExpression).name
                    : null
            })
            .orElse(null)
    }

    private void checkWorkflowOut(PropertyExpression node, WorkflowNode workflow) {
        final property = node.getPropertyAsString()
        if( workflow.emits instanceof BlockStatement ) {
            final block = (BlockStatement) workflow.emits
            for( final stmt : block.statements ) {
                final stmtX = (ExpressionStatement)stmt
                final emit = stmtX.expression
                if( property == getWorkflowEmitName(emit) )
                    return
            }
        }
        addError("Unrecognized output `${property}` for workflow `${workflow.getName()}`", node)
    }

    private String getWorkflowEmitName(Expression emit) {
        if( emit instanceof VariableExpression ) {
            return emit.name
        }
        else if( emit instanceof BinaryExpression ) {
            final left = (VariableExpression)emit.leftExpression
            return left.name
        }
        return null
    }

    @Override
    void addError(String message, ASTNode node) {
        errors.add(new MethodCallException(message, node))
    }

    List<SyntaxException> getErrors() {
        return errors
    }
}

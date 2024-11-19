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
package nextflow.lsp.services.script;

import java.util.Optional;

import nextflow.lsp.ast.ASTUtils;
import nextflow.lsp.compiler.PhaseAware;
import nextflow.lsp.compiler.Phases;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import static nextflow.script.ast.ASTHelpers.*;

/**
 * Validate process and workflow invocations.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class MethodCallVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ScriptAstCache astCache;

    public MethodCallVisitor(SourceUnit sourceUnit, ScriptAstCache astCache) {
        this.sourceUnit = sourceUnit;
        this.astCache = astCache;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ScriptNode sn )
            visit(sn);
    }

    private boolean inWorkflow;

    @Override
    public void visitWorkflow(WorkflowNode node) {
        inWorkflow = true;
        super.visitWorkflow(node);
        inWorkflow = false;
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        checkMethodCall(node);
        super.visitMethodCallExpression(node);
    }

    protected void checkMethodCall(MethodCallExpression node) {
        var defNode = ASTUtils.getMethodFromCallExpression(node, astCache);
        if( defNode == null )
            return;
        if( !(defNode instanceof ProcessNode) && !(defNode instanceof WorkflowNode) )
            return;
        if( !inWorkflow ) {
            var type = defNode instanceof ProcessNode ? "Processes" : "Workflows";
            addError(type + " can only be called from a workflow", node);
            return;
        }
        if( inClosure ) {
            var type = defNode instanceof ProcessNode ? "Processes" : "Workflows";
            addError(type + " cannot be called from within a closure", node);
            return;
        }
        var argsCount = asMethodCallArguments(node).size();
        var paramsCount = getNumberOfParameters(defNode);
        if( argsCount != paramsCount )
            addError(String.format("Incorrect number of call arguments, expected %d but received %d", paramsCount, argsCount), node);
    }

    protected static int getNumberOfParameters(MethodNode node) {
        if( node instanceof ProcessNode pn ) {
            return (int) asBlockStatements(pn.inputs).size();
        }
        if( node instanceof WorkflowNode wn ) {
            return (int) asBlockStatements(wn.takes).size();
        }
        return node.getParameters().length;
    }

    private boolean inClosure;

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        var ic = inClosure;
        inClosure = true;
        super.visitClosureExpression(node);
        inClosure = ic;
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        var mn = asMethodOutput(node);
        if( mn instanceof ProcessNode pn )
            checkProcessOut(node, pn);
        else if( mn instanceof WorkflowNode wn )
            checkWorkflowOut(node, wn);
        else
            super.visitPropertyExpression(node);
    }

    private MethodNode asMethodOutput(PropertyExpression node) {
        if( node.getObjectExpression() instanceof PropertyExpression pe ) {
            if( pe.getObjectExpression() instanceof VariableExpression ve && "out".equals(pe.getPropertyAsString()) ) {
                var defNode = ASTUtils.getDefinition(ve, astCache);
                if( defNode instanceof MethodNode mn )
                    return mn;
            }
        }
        return null;
    }

    private void checkProcessOut(PropertyExpression node, ProcessNode process) {
        var property = node.getPropertyAsString();
        var result = asDirectives(process.outputs)
            .filter(call -> property.equals(getProcessEmitName(call)))
            .findFirst();

        if( !result.isPresent() )
            addError("Unrecognized output `" + property + "` for process `" + process.getName() + "`", node);
    }

    private String getProcessEmitName(MethodCallExpression output) {
        return Optional.of(output)
            .flatMap(call -> Optional.ofNullable(asNamedArgs(call)))
            .flatMap(namedArgs ->
                namedArgs.stream()
                    .filter(entry -> "emit".equals(entry.getKeyExpression().getText()))
                    .findFirst()
            )
            .flatMap(entry -> Optional.ofNullable(
                entry.getValueExpression() instanceof VariableExpression ve ? ve.getName() : null
            ))
            .orElse(null);
    }

    private void checkWorkflowOut(PropertyExpression node, WorkflowNode workflow) {
        var property = node.getPropertyAsString();
        var result = asBlockStatements(workflow.emits).stream()
            .map(stmt -> ((ExpressionStatement) stmt).getExpression())
            .filter(emit -> property.equals(getWorkflowEmitName(emit)))
            .findFirst();

        if( !result.isPresent() )
            addError("Unrecognized output `" + property + "` for workflow `" + workflow.getName() + "`", node);
    }

    private String getWorkflowEmitName(Expression emit) {
        if( emit instanceof VariableExpression ve ) {
            return ve.getName();
        }
        else if( emit instanceof BinaryExpression be ) {
            var left = (VariableExpression)be.getLeftExpression();
            return left.getName();
        }
        return null;
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new MethodCallError(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private class MethodCallError extends SyntaxException implements PhaseAware {

        public MethodCallError(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.TYPE_INFERENCE;
        }
    }
}

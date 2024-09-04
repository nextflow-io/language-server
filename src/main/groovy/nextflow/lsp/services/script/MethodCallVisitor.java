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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import nextflow.lsp.ast.ASTUtils;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.ScriptNode;
import nextflow.script.v2.ScriptVisitorSupport;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * Validate process and workflow invocations.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class MethodCallVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ScriptAstCache astCache;

    private List<SyntaxException> errors = new ArrayList<>();

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
        var argsCount = ((TupleExpression) node.getArguments()).getExpressions().size();
        var paramsCount = getNumberOfParameters(defNode);
        if( argsCount != paramsCount )
            addError(String.format("Incorrect number of call arguments, expected %d but received %d", paramsCount, argsCount), node);
    }

    protected static int getNumberOfParameters(MethodNode node) {
        if( node instanceof ProcessNode process ) {
            if( !(process.inputs instanceof BlockStatement) )
                return 0;
            var code = (BlockStatement) process.inputs;
            return code.getStatements().size();
        }
        if( node instanceof WorkflowNode workflow ) {
            if( !(workflow.takes instanceof BlockStatement) )
                return 0;
            var code = (BlockStatement) workflow.takes;
            return code.getStatements().size();
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
                var defNode = ASTUtils.getDefinition(ve, false, astCache);
                if( defNode instanceof MethodNode mn )
                    return mn;
            }
        }
        return null;
    }

    private void checkProcessOut(PropertyExpression node, ProcessNode process) {
        var property = node.getPropertyAsString();
        if( process.outputs instanceof BlockStatement block ) {
            for( var stmt : block.getStatements() ) {
                var stmtX = (ExpressionStatement)stmt;
                var call = (MethodCallExpression)stmtX.getExpression();
                if( property.equals(getProcessEmitName(call)) )
                    return;
            }
        }
        addError("Unrecognized output `" + property + "` for process `" + process.getName() + "`", node);
    }

    private String getProcessEmitName(MethodCallExpression output) {
        return Optional.of(output)
            .flatMap(call -> Optional.ofNullable(
                call.getArguments() instanceof TupleExpression te ? te.getExpressions() : null
            ))
            .flatMap(args -> Optional.ofNullable(
                args.size() > 0 && args.get(0) instanceof MapExpression me ? me : null
            ))
            .flatMap(namedArgs ->
                namedArgs.getMapEntryExpressions().stream()
                    .filter(entry -> "emit".equals(entry.getKeyExpression().getText()))
                    .findFirst()
            )
            .flatMap(entry -> 
                entry.getValueExpression() instanceof VariableExpression ve
                    ? Optional.of(ve.getName())
                    : Optional.empty()
            )
            .orElse(null);
    }

    private void checkWorkflowOut(PropertyExpression node, WorkflowNode workflow) {
        var property = node.getPropertyAsString();
        if( workflow.emits instanceof BlockStatement block ) {
            for( var stmt : block.getStatements() ) {
                var stmtX = (ExpressionStatement)stmt;
                var emit = stmtX.getExpression();
                if( property.equals(getWorkflowEmitName(emit)) )
                    return;
            }
        }
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
        errors.add(new MethodCallException(message, node));
    }

    public List<SyntaxException> getErrors() {
        return errors;
    }
}

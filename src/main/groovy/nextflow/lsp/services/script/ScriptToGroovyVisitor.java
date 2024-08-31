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
import java.util.stream.Collectors;

import nextflow.script.BodyDef;
import nextflow.script.IncludeDef;
import nextflow.script.TokenEnvCall;
import nextflow.script.TokenFileCall;
import nextflow.script.TokenPathCall;
import nextflow.script.TokenStdinCall;
import nextflow.script.TokenStdoutCall;
import nextflow.script.TokenValCall;
import nextflow.script.TokenVar;
import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.IncludeNode;
import nextflow.script.v2.OutputNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.ScriptNode;
import nextflow.script.v2.ScriptVisitorSupport;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;

import static nextflow.ast.ASTHelpers.*;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;

/**
 * Transform Nextflow AST nodes into pure Groovy.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptToGroovyVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ScriptNode moduleNode;

    public ScriptToGroovyVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
        this.moduleNode = (ScriptNode) sourceUnit.getAST();
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        if( moduleNode == null )
            return;
        super.visit(moduleNode);
        if( moduleNode.isEmpty() )
            moduleNode.addStatement(ReturnStatement.RETURN_NULL_OR_VOID);
    }

    @Override
    public void visitFeatureFlag(FeatureFlagNode node) {
        var left = constX(node.name);
        node.setExpression(callThisX("feature", args(left, node.value)));
        moduleNode.addStatement(node);
    }

    @Override
    public void visitInclude(IncludeNode node) {
        var moduleArgs = node.modules.stream()
            .map((module) -> {
                var name = constX(module.name);
                return module.alias != null
                    ? (Expression) createX(IncludeDef.Module.class, name, constX(module.alias))
                    : (Expression) createX(IncludeDef.Module.class, name);
            })
            .collect(Collectors.toList());

        var include = callThisX("include", args(createX(IncludeDef.class, args(moduleArgs))));
        var from = callX(include, "from", args(node.source));
        node.setExpression(callX(from, "load0", args(varX("params"))));
        moduleNode.addStatement(node);
    }

    @Override
    public void visitFunction(FunctionNode node) {
        moduleNode.addMethod(node);
    }

    @Override
    public void visitProcess(ProcessNode node) {
        visitProcessInputs(node.inputs);
        visitProcessOutputs(node.outputs);

        var when = processWhen(node.when);
        var bodyDef = stmt(createX(
            BodyDef.class,
            args(
                closureX(node.exec),
                constX(""), // TODO: source code formatting
                constX(node.type),
                new ListExpression() // TODO: variable references (see VariableVisitor)
            )
        ));
        var stub = processStub(node.stub);
        var closure = closureX(block(new VariableScope(), List.of(
            node.directives,
            node.inputs,
            node.outputs,
            when,
            stub,
            bodyDef
        )));
        var result = stmt(callThisX("process", args(constX(node.getName()), closure)));
        moduleNode.addStatement(result);
    }

    private void visitProcessInputs(Statement inputs) {
        if( !(inputs instanceof BlockStatement) )
            return;
        var code = (BlockStatement)inputs;
        for( var stmt : code.getStatements() ) {
            var stmtX = (ExpressionStatement)stmt;
            var call = (MethodCallExpression)stmtX.getExpression();
            var name = call.getMethodAsString();
            varToConstX(call.getArguments(), "tuple".equals(name), "each".equals(name));
            call.setMethod( constX("_in_" + name) );
        }
    }

    private void visitProcessOutputs(Statement outputs) {
        if( !(outputs instanceof BlockStatement) )
            return;
        var code = (BlockStatement)outputs;
        for( var stmt : code.getStatements() ) {
            var stmtX = (ExpressionStatement)stmt;
            var call = (MethodCallExpression)stmtX.getExpression();
            var name = call.getMethodAsString();
            varToConstX(call.getArguments(), "tuple".equals(name), "each".equals(name));
            call.setMethod( constX("_out_" + name) );
            visitProcessOutputEmitAndTopic(call);
        }
    }

    private static final List<String> EMIT_AND_TOPIC = List.of("emit", "topic");

    private void visitProcessOutputEmitAndTopic(MethodCallExpression output) {
        var args = isTupleX(output.getArguments());
        if( args == null )
            return;
        var namedArgs = isMapX(args.getExpression(0));
        if( namedArgs == null )
            return;
        var mapEntryExpressions = namedArgs.getMapEntryExpressions();
        for( int i = 0; i < mapEntryExpressions.size(); i++ ) {
            var entry = mapEntryExpressions.get(i);
            var key = isConstX(entry.getKeyExpression());
            var value = isVariableX(entry.getValueExpression());
            if( value != null && key != null && EMIT_AND_TOPIC.contains(key.getText()) ) {
                mapEntryExpressions.set(i, entryX(key, constX(value.getText())));
            }
        }
    }

    private Expression varToConstX(Expression node, boolean withinTuple, boolean withinEach) {
        if( node instanceof VariableExpression ve ) {
            var name = ve.getName();

            if( "stdin".equals(name) && withinTuple )
                return createX( TokenStdinCall.class );

            if ( "stdout".equals(name) && withinTuple )
                return createX( TokenStdoutCall.class );

            return createX( TokenVar.class, constX(name) );
        }

        if( node instanceof MethodCallExpression mce ) {
            var name = mce.getMethodAsString();
            var arguments = mce.getArguments();

            if( "env".equals(name) && withinTuple )
                return createX( TokenEnvCall.class, (TupleExpression) varToStrX(arguments) );

            if( "file".equals(name) && (withinTuple || withinEach) )
                return createX( TokenFileCall.class, (TupleExpression) varToConstX(arguments, withinTuple, withinEach) );

            if( "path".equals(name) && (withinTuple || withinEach) )
                return createX( TokenPathCall.class, (TupleExpression) varToConstX(arguments, withinTuple, withinEach) );

            if( "val".equals(name) && withinTuple )
                return createX( TokenValCall.class, (TupleExpression) varToStrX(arguments) );
        }

        if( node instanceof TupleExpression te ) {
            var arguments = te.getExpressions();
            for( int i = 0; i < arguments.size(); i++ )
                arguments.set(i, varToConstX(arguments.get(i), withinTuple, withinEach));
            return te;
        }

        return node;
    }

    private Expression varToStrX(Expression node) {
        if( node instanceof VariableExpression ve ) {
            var name = ve.getName();
            return createX( TokenVar.class, constX(name) );
        }

        if( node instanceof TupleExpression te ) {
            var arguments = te.getExpressions();
            for( int i = 0; i < arguments.size(); i++ )
                arguments.set(i, varToStrX(arguments.get(i)));
            return te;
        }

        return node;
    }

    private Statement processWhen(Expression when) {
        if( when instanceof EmptyExpression )
            return EmptyStatement.INSTANCE;
        return stmt(when);
    }

    private Statement processStub(Statement stub) {
        if( stub instanceof EmptyStatement )
            return EmptyStatement.INSTANCE;
        return stmt(callThisX("stub", closureX(stub)));
    }

    @Override
    public void visitWorkflow(WorkflowNode node) {
        visitWorkflowTakes(node.takes);
        visitWorkflowEmits(node.emits, node.main);
        visitWorkflowPublishers(node.publishers, node.main);

        var bodyDef = stmt(createX(
            BodyDef.class,
            args(
                closureX(node.main),
                constX(""), // TODO: source code formatting
                constX("workflow"),
                new ListExpression() // TODO: variable references (see VariableVisitor)
            )
        ));
        var closure = closureX(block(new VariableScope(), List.of(
            node.takes,
            node.emits,
            bodyDef
        )));
        var arguments = node.isEntry()
            ? args(closure)
            : args(constX(node.getName()), closure);
        var result = stmt(callThisX("workflow", arguments));
        moduleNode.addStatement(result);
    }

    private void visitWorkflowTakes(Statement takes) {
        if( !(takes instanceof BlockStatement) )
            return;
        var code = (BlockStatement)takes;
        for( var stmt : code.getStatements() ) {
            var stmtX = (ExpressionStatement)stmt;
            var take = (VariableExpression)stmtX.getExpression();
            stmtX.setExpression(callThisX("_take_", args(constX(take.getName()))));
        }
    }

    private void visitWorkflowEmits(Statement emits, Statement main) {
        if( !(emits instanceof BlockStatement) )
            return;
        var mainCode = (BlockStatement)main;
        var emitCode = (BlockStatement)emits;
        for( var stmt : emitCode.getStatements() ) {
            var stmtX = (ExpressionStatement)stmt;
            var emit = stmtX.getExpression();
            if( emit instanceof VariableExpression ve ) {
                stmtX.setExpression(callThisX("_emit_", args(constX(ve.getName()))));
            }
            else if( emit instanceof BinaryExpression be ) {
                var left = (VariableExpression)be.getLeftExpression();
                stmtX.setExpression(callThisX("_emit_", args(constX(left.getName()))));
                mainCode.addStatement(stmtX);
            }
            else {
                var target = varX("$out");
                mainCode.addStatement(assignS(target, emit));
                stmtX.setExpression(callThisX("_emit_", args(constX(target.getName()))));
                mainCode.addStatement(stmtX);
            }
        }
    }

    private void visitWorkflowPublishers(Statement publishers, Statement main) {
        if( !(publishers instanceof BlockStatement) )
            return;
        var mainCode = (BlockStatement)main;
        var publishCode = (BlockStatement)publishers;
        for( var stmt : publishCode.getStatements() ) {
            var stmtX = (ExpressionStatement)stmt;
            var publish = (BinaryExpression)stmtX.getExpression();
            stmtX.setExpression(callThisX("_publish_target", args(publish.getLeftExpression(), publish.getRightExpression())));
            mainCode.addStatement(stmtX);
        }
    }

    @Override
    public void visitOutput(OutputNode node) {
        var closure = closureX(node.body);
        node.setExpression(callThisX("output", args(closure)));
        moduleNode.addStatement(node);
    }

}

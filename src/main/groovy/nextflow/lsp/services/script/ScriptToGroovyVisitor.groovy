package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.script.BodyDef
import nextflow.script.IncludeDef
import nextflow.script.TokenEnvCall
import nextflow.script.TokenFileCall
import nextflow.script.TokenPathCall
import nextflow.script.TokenStdinCall
import nextflow.script.TokenStdoutCall
import nextflow.script.TokenValCall
import nextflow.script.TokenVar
import nextflow.script.v2.FeatureFlagNode
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.IncludeNode
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit

import static nextflow.ast.ASTHelpers.*
import static org.codehaus.groovy.ast.tools.GeneralUtils.*

/**
 * Transform Nextflow AST nodes into pure Groovy.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptToGroovyVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

    private SourceUnit sourceUnit

    private ScriptNode moduleNode

    ScriptToGroovyVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
        this.moduleNode = (ScriptNode) sourceUnit.getAST()
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        if( moduleNode == null )
            return
        for( final featureFlag : moduleNode.getFeatureFlags() )
            visitFeatureFlag(featureFlag)
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

    @Override
    void visitFeatureFlag(FeatureFlagNode node) {
        final left = constX(node.names.join('.'))
        node.expression = callThisX('feature', args(left, node.value))
        moduleNode.addStatement(node)
    }

    @Override
    void visitInclude(IncludeNode node) {
        final moduleArgs = node.modules.collect { module ->
            final name = constX(module.name)
            module.alias
                ? createX(IncludeDef.Module, name, constX(module.alias))
                : createX(IncludeDef.Module, name)
        } as List<Expression>
        final include = callThisX('include', args(createX(IncludeDef, args(moduleArgs))))
        final from = callX(include, 'from', args(constX(node.source)))
        node.expression = callX(from, 'load0', args(varX('params')))
        moduleNode.addStatement(node)
    }

    @Override
    void visitFunction(FunctionNode node) {
        moduleNode.addMethod(node)
    }

    @Override
    void visitProcess(ProcessNode node) {
        convertProcessInputs(node.inputs)
        convertProcessOutputs(node.outputs)

        final when = processWhen(node.when)
        final bodyDef = stmt(createX(
            BodyDef,
            args(
                closureX(node.exec),
                constX(''), // TODO: source code formatting
                constX(node.type),
                new ListExpression() // TODO: variable references (see VariableVisitor)
            )
        ))
        final stub = processStub(node.stub)
        final closure = closureX(block(new VariableScope(), [
            node.directives,
            node.inputs,
            node.outputs,
            when,
            stub,
            bodyDef
        ]))
        node.expression = callThisX('process', args(constX(node.name), closure))
        moduleNode.addStatement(node)
    }

    private void convertProcessInputs(Statement inputs) {
        if( inputs !instanceof BlockStatement )
            return
        final code = (BlockStatement)inputs
        for( final stmt : code.statements ) {
            final stmtX = (ExpressionStatement)stmt
            final methodCall = (MethodCallExpression)stmtX.expression
            convertProcessInput(methodCall)
        }
    }

    private void convertProcessInput(MethodCallExpression input) {
        final name = input.getMethodAsString()
        varToConstX(input.getArguments(), name == 'tuple', name == 'each')
        input.setMethod( constX('_in_' + name) )
    }

    private void convertProcessOutputs(Statement outputs) {
        if( outputs !instanceof BlockStatement )
            return
        final code = (BlockStatement)outputs
        for( final stmt : code.statements ) {
            final stmtX = (ExpressionStatement)stmt
            final methodCall = (MethodCallExpression)stmtX.expression
            convertProcessOutput(methodCall)
        }
    }

    private void convertProcessOutput(MethodCallExpression output) {
        final name = output.getMethodAsString()
        varToConstX(output.getArguments(), name == 'tuple', name == 'each')
        output.setMethod( constX('_out_' + name) )
        convertEmitAndTopicOptions(output)
    }

    private void convertEmitAndTopicOptions(MethodCallExpression output) {
        final args = isTupleX(output.arguments)?.expressions
        if( !args )
            return
        final map = isMapX(args[0])
        if( !map )
            return
        for( int i = 0; i < map.mapEntryExpressions.size(); i++ ) {
            final entry = map.mapEntryExpressions[i]
            final key = isConstX(entry.keyExpression)
            final value = isVariableX(entry.valueExpression)
            if( value && key?.text in ['emit', 'topic'] ) {
                map.mapEntryExpressions[i] = entryX(key, constX(value.text))
            }
        }
    }

    private Expression varToConstX(Expression expr, boolean withinTuple, boolean withinEach) {
        if( expr instanceof VariableExpression ) {
            final name = ((VariableExpression) expr).getName()

            if( name == 'stdin' && withinTuple )
                return createX( TokenStdinCall )

            if ( name == 'stdout' && withinTuple )
                return createX( TokenStdoutCall )

            return createX( TokenVar, constX(name) )
        }

        if( expr instanceof MethodCallExpression ) {
            final methodCall = (MethodCallExpression)expr
            final name = methodCall.methodAsString
            final arguments = methodCall.arguments

            if( name == 'env' && withinTuple )
                return createX( TokenEnvCall, (TupleExpression) varToStrX(arguments) )

            if( name == 'file' && (withinTuple || withinEach) )
                return createX( TokenFileCall, (TupleExpression) varToConstX(arguments, withinTuple, withinEach) )

            if( name == 'path' && (withinTuple || withinEach) )
                return createX( TokenPathCall, (TupleExpression) varToConstX(arguments, withinTuple, withinEach) )

            if( name == 'val' && withinTuple )
                return createX( TokenValCall, (TupleExpression) varToStrX(arguments) )
        }

        if( expr instanceof TupleExpression ) {
            final arguments = expr.getExpressions()
            int i = 0
            for( Expression item : arguments )
                arguments[i++] = varToConstX(item, withinTuple, withinEach)
            return expr
        }

        return expr
    }

    private Expression varToStrX(Expression expr) {
        if( expr instanceof VariableExpression ) {
            final name = ((VariableExpression) expr).getName()
            return createX( TokenVar, constX(name) )
        }

        if( expr instanceof TupleExpression ) {
            final arguments = expr.getExpressions()
            int i = 0
            for( Expression item : arguments )
                arguments[i++] = varToStrX(item)
            return expr
        }

        return expr
    }

    private Statement processWhen(Expression when) {
        if( when instanceof EmptyExpression )
            return EmptyStatement.INSTANCE
        return stmt(when)
    }

    private Statement processStub(Statement stub) {
        if( stub instanceof EmptyExpression )
            return EmptyStatement.INSTANCE
        return stmt(callThisX('stub', closureX(stub)))
    }

    @Override
    void visitWorkflow(WorkflowNode node) {
        convertWorkflowTakes(node.takes)
        convertWorkflowEmits(node.emits, node.main)

        final bodyDef = stmt(createX(
            BodyDef,
            args(
                closureX(node.main),
                constX(''), // TODO: source code formatting
                constX('workflow'),
                new ListExpression() // TODO: variable references (see VariableVisitor)
            )
        ))
        final closure = closureX(block(new VariableScope(), [
            node.takes,
            node.emits,
            bodyDef
        ]))
        final arguments = node.name
            ? args(constX(node.name), closure)
            : args(closure)
        node.expression = callThisX('workflow', arguments)
        moduleNode.addStatement(node)
    }

    private void convertWorkflowTakes(Statement takes) {
        if( takes !instanceof BlockStatement )
            return
        final code = (BlockStatement)takes
        for( final stmt : code.statements ) {
            final stmtX = (ExpressionStatement)stmt
            final take = (VariableExpression)stmtX.expression
            stmtX.expression = callThisX('_take_', args(constX(take.name)))
        }
    }

    private void convertWorkflowEmits(Statement emits, Statement main) {
        if( emits !instanceof BlockStatement )
            return
        final mainCode = (BlockStatement)main
        final emitCode = (BlockStatement)emits
        for( final stmt : emitCode.statements ) {
            final stmtX = (ExpressionStatement)stmt
            final emit = stmtX.expression
            if( emit instanceof VariableExpression ) {
                stmtX.expression = callThisX('_emit_', args(constX(emit.name)))
            }
            else if( emit instanceof BinaryExpression ) {
                final left = (VariableExpression)emit.leftExpression
                stmtX.expression = callThisX('_emit_', args(constX(left.name)))
                mainCode.addStatement(stmtX)
            }
        }
    }

    @Override
    void visitOutput(OutputNode node) {
        final closure = closureX(node.body)
        node.expression = callThisX('output', args(closure))
        moduleNode.addStatement(node)
    }

}
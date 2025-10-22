/*
 * Copyright 2024-2025, Seqera Labs
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
package nextflow.script.control;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import nextflow.lsp.ast.ASTNodeCache;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.Namespace;
import nextflow.script.dsl.Ops;
import nextflow.script.types.Channel;
import nextflow.script.types.ParamsMap;
import nextflow.script.types.Record;
import nextflow.script.types.Tuple;
import nextflow.script.types.TypesEx;
import nextflow.script.types.Value;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.ast.ASTUtils.*;
import static nextflow.script.types.TypeCheckingUtils.*;

/**
 * Resolve and validate the types of expressions.
 *
 * @see org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class TypeCheckingVisitorEx extends ScriptVisitorSupport {

    private static final ClassNode CHANNEL_TYPE = ClassHelper.makeCached(Channel.class);
    private static final ClassNode PATH_TYPE = ClassHelper.makeCached(Path.class);
    private static final ClassNode PARAMS_TYPE = ClassHelper.makeCached(ParamsMap.class);
    private static final ClassNode RECORD_TYPE = ClassHelper.makeCached(Record.class);
    private static final ClassNode TUPLE_TYPE = ClassHelper.makeCached(Tuple.class);
    private static final ClassNode VALUE_TYPE = ClassHelper.makeCached(Value.class);

    private SourceUnit sourceUnit;

    private boolean experimental;

    public TypeCheckingVisitorEx(SourceUnit sourceUnit, boolean experimental) {
        this.sourceUnit = sourceUnit;
        this.experimental = experimental;
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

    // script declarations

    @Override
    public void visitFeatureFlag(FeatureFlagNode node) {
        if( !experimental )
            return;
        var fn = node.target;
        if( fn == null )
            return;
        var expectedType = fn.getType();
        var actualType = node.value.getType();
        if( !TypesEx.isAssignableFrom(expectedType, actualType) )
            addError("Feature flag '" + node.name + "' expects a " + TypesEx.getName(expectedType) + " but received a " + TypesEx.getName(actualType), node);
    }

    private boolean hasParamsBlock;

    @Override
    public void visitParam(Parameter node) {
        visitParameter(node, true);
        hasParamsBlock = true;
    }

    private void visitParameter(Parameter node, boolean allowPathCoercion) {
        if( !experimental )
            return;
        if( !node.hasInitialExpression() )
            return;
        var expectedType = node.getType();
        var actualType = getType(node.getInitialExpression());
        if( TypesEx.isAssignableFrom(expectedType, actualType) )
            return;
        if( allowPathCoercion && TypesEx.isEqual(expectedType, PATH_TYPE) && TypesEx.isEqual(actualType, ClassHelper.STRING_TYPE) )
            return;
        addError("Parameter '" + node.getName() + "' with type " + TypesEx.getName(expectedType) + " cannot be assigned to default value with type " + TypesEx.getName(actualType), node);
    }

    private static WorkflowNode currentWorkflow;

    @Override
    public void visitWorkflow(WorkflowNode node) {
        if( !experimental ) {
            super.visitWorkflow(node);
            return;
        }

        currentWorkflow = node;

        visit(node.main);

        if( node.isEntry() )
            visitWorkflowOutputs(node);
        else
            visit(node.emits);

        visit(node.onComplete);
        visit(node.onError);

        currentWorkflow = null;
    }

    private void visitWorkflowOutputs(WorkflowNode node) {
        var sn = (ScriptNode) sourceUnit.getAST();
        var outputs = sn.getOutputs();
        if( outputs != null && node.publishers.isEmpty() )
            addError("Output block is defined but entry workflow does not define a `publish:` section", node);
        if( outputs == null && !node.publishers.isEmpty() )
            addError("Entry workflow defines a `publish:` section but no output block is defined", node.publishers);
        if( outputs == null )
            return;

        for( var publisher : asBlockStatements(node.publishers) ) {
            var es = (ExpressionStatement)publisher;
            var ae = (AssignmentExpression)es.getExpression();

            var target = (VariableExpression)ae.getLeftExpression();
            var decl = outputs.declarations.stream()
                .filter(output -> output.getName().equals(target.getName()))
                .findFirst().orElse(null);
            if( decl == null ) {
                addError("Workflow output '" + target.getName() + "' was assigned in the entry workflow but not declared in the output block", publisher);
                continue;
            }
            target.setAccessedVariable(decl);
            
            var source = ae.getRightExpression();
            visit(source);
            var sourceType = getType(source);
            var targetType = asDataflowType(target.getType(), sourceType);
            if( !TypesEx.isAssignableFrom(targetType, sourceType) )
                addError("Workflow output '" + target.getName() + "' with type " + TypesEx.getName(targetType) + " cannot be assigned to value with type " + TypesEx.getName(sourceType), ae);
        }
    }

    private static ClassNode asDataflowType(ClassNode type, ClassNode sourceType) {
        if( CHANNEL_TYPE.equals(type) || VALUE_TYPE.equals(type) )
            return type;
        if( VALUE_TYPE.equals(sourceType) )
            return makeType(VALUE_TYPE, type);
        return type;
    }

    @Override
    public void visitProcessV2(ProcessNodeV2 node) {
        visit(node.directives);
        visit(node.stagers);
        visit(node.when);
        visit(node.exec);
        visit(node.stub);
        visit(node.outputs);
        visitProcessTopics(node.topics);
    }

    private void visitProcessTopics(Statement block) {
        for( var stmt : asBlockStatements(block) ) {
            var es = (ExpressionStatement)stmt;
            var be = (BinaryExpression)es.getExpression();
            var source = be.getLeftExpression();
            var target = be.getRightExpression();
            visit(source);
            visit(target);
            var targetType = getType(target);
            if( !TypesEx.isEqual(ClassHelper.STRING_TYPE, targetType) )
                addError("Topic name should be a String but was specified as a " + TypesEx.getName(targetType), target);
        }
    }

    @Override
    public void visitProcessV1(ProcessNodeV1 node) {
        // don't try to type-check input/output directives
        visit(node.directives);
        visit(node.when);
        visit(node.exec);
        visit(node.stub);
    }

    @Override
    public void visitFunction(FunctionNode node) {
        // visit parameters and code
        for( var parameter : node.getParameters() )
            visitParameter(parameter, false);
        visit(node.getCode());

        // check return statements against declared return type
        if( !experimental )
            return;
            
        var visitor = new ReturnStatementVisitor();
        visitor.visit(node.getReturnType(), node.getCode());

        var inferredReturnType = visitor.getInferredReturnType();
        if( inferredReturnType != null && ClassHelper.isDynamicTyped(node.getReturnType()) )
            node.setReturnType(inferredReturnType);
    }

    private class ReturnStatementVisitor extends CodeVisitorSupport {

        private ClassNode returnType;

        private ClassNode inferredReturnType;

        public void visit(ClassNode returnType, Statement code) {
            this.returnType = returnType;
            visit(addReturnsIfNeeded(code));
            this.returnType = null;
        }

        private Statement addReturnsIfNeeded(Statement node) {
            if( node instanceof BlockStatement block && !block.isEmpty() ) {
                var statements = new ArrayList<>(block.getStatements());
                int lastIndex = statements.size() - 1;
                var last = addReturnsIfNeeded(statements.get(lastIndex));
                statements.set(lastIndex, last);
                return new BlockStatement(statements, block.getVariableScope());
            }

            if( node instanceof ExpressionStatement es ) {
                return new ReturnStatement(es.getExpression());
            }

            if( node instanceof IfStatement ies ) {
                return new IfStatement(
                    ies.getBooleanExpression(),
                    addReturnsIfNeeded(ies.getIfBlock()),
                    addReturnsIfNeeded(ies.getElseBlock()) );
            }

            return node;
        }

        @Override
        public void visitReturnStatement(ReturnStatement node) {
            var sourceType = getType(node.getExpression());
            if( inferredReturnType != null && !ClassHelper.isDynamicTyped(returnType) ) {
                if( !TypesEx.isAssignableFrom(inferredReturnType, sourceType) )
                    addError(String.format("Return value with type %s does not match previous return type (%s)", TypesEx.getName(sourceType), TypesEx.getName(inferredReturnType)), node);
            }
            else if( TypesEx.isAssignableFrom(returnType, sourceType) ) {
                inferredReturnType = sourceType;
            }
            else {
                addError(String.format("Return value with type %s does not match the declared return type (%s)", TypesEx.getName(sourceType), TypesEx.getName(returnType)), node);
            }
        }

        public ClassNode getInferredReturnType() {
            return inferredReturnType;
        }
    }

    @Override
    public void visitOutput(OutputNode node) {
        for( var stmt : asBlockStatements(node.body) ) {
            var call = asMethodCallX(stmt);
            if( checkPublishStatements(call) )
                continue;
            super.visitMethodCallExpression(call);
        }
    }

    private boolean checkPublishStatements(MethodCallExpression node) {
        if( !"path".equals(node.getMethodAsString()) )
            return false;
        var code = asDslBlock(node, 1);
        if( code == null )
            return false;
        for( var stmt : code.getStatements() ) {
            if( checkPublishStatement(stmt) )
                continue;
            visit(stmt);
        }
        return true;
    }

    private boolean checkPublishStatement(Statement node) {
        var publish = asPublishStatement(node);
        if( publish == null )
            return false;
        var source = publish.getLeftExpression();
        var target = publish.getRightExpression();
        visit(source);
        visit(target);
        var sourceType = getType(source);
        if( !isPathOrCollection(sourceType) )
            addError("Publish source should be a Path or Iterable<Path> but was specified as a " + TypesEx.getName(sourceType), source);
        var targetType = getType(target);
        if( !TypesEx.isAssignableFrom(ClassHelper.STRING_TYPE, targetType) )
            addError("Publish target should be a String but was specified as a " + TypesEx.getName(targetType), target);
        return true;
    }

    private BinaryExpression asPublishStatement(Statement node) {
        return node instanceof ExpressionStatement es
            && es.getExpression() instanceof BinaryExpression be
            && be.getOperation().getType() == Types.RIGHT_SHIFT ? be : null;
    }

    private boolean isPathOrCollection(ClassNode type) {
        return TypesEx.isAssignableFrom(PATH_TYPE, type)
            || TypesEx.isAssignableFrom(ClassHelper.ITERABLE_TYPE, type);
    }

    // statements

    @Override
    public void visitExpressionStatement(ExpressionStatement node) {
        var exp = node.getExpression();
        if( exp instanceof AssignmentExpression ae ) {
            applyAssignment(ae);
            return;
        }
        super.visitExpressionStatement(node);
    }

    // expressions

    @Override
    public void visitDeclarationExpression(DeclarationExpression node) {
        applyAssignment(node);
    }

    private void applyAssignment(BinaryExpression node) {
        if( !experimental )
            return;
        var target = node.getLeftExpression();
        var source = node.getRightExpression();
        if( source instanceof EmptyExpression )
            return;
        visit(target);
        visit(source);
        var targetType = getType(target);
        var sourceType = getType(source);
        if( TypesEx.isAssignableFrom(targetType, sourceType) ) {
            if( target instanceof VariableExpression ve && ve.isDynamicTyped() )
                target.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, sourceType);
            else if( target instanceof TupleExpression te )
                applyTupleAssignment(te, sourceType);
        }
        else {
            addError("Assignment target with type " + TypesEx.getName(targetType) + " cannot be assigned to value with type " + TypesEx.getName(sourceType), node);
        }
    }

    private void applyTupleAssignment(TupleExpression target, ClassNode sourceType) {
        var vars = target.getExpressions();

        if( TUPLE_TYPE.equals(sourceType) ) {
            var gts = sourceType.getGenericsTypes();
            if( gts == null )
                return;
            if( vars.size() == gts.length ) {
                for( int i = 0; i < vars.size(); i++ )
                    vars.get(i).putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, gts[i].getType());
            }
            else {
                addError("Assignment target with " + vars.size() + " components cannot be assigned to tuple with " + gts.length + " components", target);
            }
        }

        if( RECORD_TYPE.equals(sourceType) ) {
            var fields = sourceType.getFields();
            if( fields.size() == 0 )
                return;
            if( vars.size() == fields.size() ) {
                for( int i = 0; i < vars.size(); i++ )
                    vars.get(i).putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, fields.get(i).getType());
            }
            else {
                addError("Assignment target with " + vars.size() + " components cannot be assigned to record with " + fields.size() + " fields", target);
            }
        }
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        if( !experimental ) {
            super.visitMethodCallExpression(node);
            return;
        }

        // resolve argument types (except for closures)
        var arguments = asMethodCallArguments(node);
        boolean hasClosureArgs = false;
        for( var argument : arguments ) {
            if( argument instanceof ClosureExpression ) {
                hasClosureArgs = true;
                continue;
            }
            argument.visit(this);
        }

        // resolve receiver type
        var receiver = node.getObjectExpression();
        if( !node.isImplicitThis() ) {
            receiver.visit(this);
            if( node.isSpreadSafe() ) {
                checkSpreadMethodCall(node);
                return;
            }
            if( ClassHelper.isDynamicTyped(getType(receiver)) ) {
                return;
            }
        }

        // resolve dataflow inputs and outputs for process calls
        if( checkProcessCall(node) )
            return;

        // resolve tuple return type for tuple() calls
        if( checkTupleCall(node) )
            return;

        // resolve method target
        var receiverType = !node.isImplicitThis() ? getType(receiver) : null;
        var target = resolveMethodCall(node);
        if( target != null ) {
            if( arguments.size() > 0 && arguments.get(0) instanceof MapExpression me )
                checkNamedParams(target.getParameters()[0], me);

            if( hasClosureArgs )
                visitClosureArguments(receiverType, arguments, target);

            var dummyMethod = resolveGenericReturnType(receiverType, target, arguments);
            node.putNodeMetaData(ASTNodeMarker.METHOD_TARGET, dummyMethod);
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, dummyMethod.getReturnType());
        }
        else if( node.getNodeMetaData(ASTNodeMarker.METHOD_TARGET) instanceof MethodNode mn ) {
            var parameters = mn.getParameters();
            if( parameters.length != arguments.size() ) {
                addError(String.format("%s `%s` expects %d argument(s) but received %d", methodType(mn), node.getMethodAsString(), parameters.length, arguments.size()), node.getMethod());
            }
            else {
                checkArguments(parameters, arguments);

                if( arguments.size() > 0 && arguments.get(0) instanceof MapExpression me )
                    checkNamedParams(mn.getParameters()[0], me);
            }

            if( !(mn instanceof ProcessNode || mn instanceof WorkflowNode) ) {
                var dummyMethod = asDummyMethod(receiverType, mn, mn.getParameters(), mn.getReturnType());
                node.putNodeMetaData(ASTNodeMarker.METHOD_TARGET, dummyMethod);
            }
        }
        else if( experimental && node.getNodeMetaData(ASTNodeMarker.METHOD_OVERLOADS) != null ) {
            addError(String.format("Function `%s` (with multiple signatures) was called with incorrect number of arguments and/or incorrect argument types", node.getMethodAsString()), node.getMethod());
        }
        else if( experimental && !node.isImplicitThis() ) {
            var className = className(receiver);
            addError(String.format("Unrecognized method `%s` for %s", node.getMethodAsString(), className), node.getMethod());
        }
    }

    /**
     * Check a spread-dot method call against an Iterable receiver
     * by resolving the method name and arguments against the
     * receiver's element type.
     *
     * For example, given the following method call:
     *
     *   files('*.txt')*.size()
     *
     * The return type is inferred as follows:
     *
     * 1. The receiver type is Iterable<Path>
     * 2. The element type is Path
     * 3. The target method is Path::size(), which returns an Integer
     * 4. Therefore the return type is Iterable<Integer>
     *
     * @param node
     */
    private void checkSpreadMethodCall(MethodCallExpression node) {
        var receiverType = getType(node.getObjectExpression());
        if( !TypesEx.isAssignableFrom(ClassHelper.ITERABLE_TYPE, receiverType) ) {
            addError("Spread-dot is only supported for Iterable types", node);
            return;
        }
        var elementType = elementType(receiverType);
        var name = node.getMethodAsString();
        var proxyReceiver = new VariableExpression("_", elementType);
        var proxyCall = new MethodCallExpression(proxyReceiver, name, node.getArguments());
        proxyCall.setImplicitThis(false);
        var target = resolveMethodCall(proxyCall);
        if( target != null ) {
            var arguments = asMethodCallArguments(node);
            visitClosureArguments(elementType, arguments, target);

            var dummyMethod = resolveGenericReturnType(elementType, target, arguments);
            node.putNodeMetaData(ASTNodeMarker.METHOD_TARGET, dummyMethod);

            var resultType = makeType(receiverType, dummyMethod.getReturnType());
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);
        }
        else {
            addError(String.format("Unrecognized method `%s` for element type %s", name, TypesEx.getName(elementType)), node);
        }
    }

    /**
     * Check the arguments of an invalid method call and report appropriate
     * errors for each invalid argument.
     *
     * @param parameters
     * @param arguments
     */
    private void checkArguments(Parameter[] parameters, List<Expression> arguments) {
        for( int i = 0; i < parameters.length; i++ ) {
            var paramType = parameters[i].getType();
            var argument = arguments.get(i);
            var argType = getType(argument);
            if( TypesEx.isAssignableFrom(paramType, argType) )
                continue;
            if( argument instanceof ClosureExpression ce && TypesEx.isFunctionalInterface(paramType) ) {
                var parameterTypes = Arrays.stream(ce.getParameters())
                    .map(p -> p.getType())
                    .toArray(ClassNode[]::new);
                var returnType = ClassHelper.dynamicType();
                addError("Closure with signature " + TypesEx.getName(parameterTypes, returnType) + " is not compatible with expected signature: " + TypesEx.getName(paramType), argument);
            }
            else {
                addError("Argument with type " + TypesEx.getName(argType) + " is not compatible with parameter of type " + TypesEx.getName(paramType), argument);
            }
        }
    }

    /**
     * Check named arguments against the named parameters of a
     * method, if they are defined using the @NamedParams annotation.
     *
     * @param param
     * @param args
     */
    private void checkNamedParams(Parameter param, MapExpression args) {
        if( !TypesEx.isEqual(param.getType(), ClassHelper.MAP_TYPE) )
            return;

        var namedParams = asNamedParams(param);
        if( namedParams.isEmpty() )
            return;

        for( var entry : args.getMapEntryExpressions() ) {
            var name = entry.getKeyExpression().getText();
            var value = entry.getValueExpression();
            if( !namedParams.containsKey(name) ) {
                addError("Named param `" + name + "` is not defined", entry);
                continue;
            }
            var namedParam = asNamedParam(namedParams.get(name));
            var argType = getType(value);
            if( !TypesEx.isAssignableFrom(namedParam.getType(), argType) )
                addError("Named param `" + name + "` expects a " + TypesEx.getName(namedParam.getType()) + " but received a " + TypesEx.getName(argType), value);
            entry.putNodeMetaData("_NAMED_PARAM", namedParam);
        }
    }

    /**
     * Visit closure arguments after the target method is resolved,
     * so that closure parameters can be inferred from the corresponding
     * method parameter.
     *
     * @param receiverType
     * @param arguments
     * @param method
     */
    private void visitClosureArguments(ClassNode receiverType, List<Expression> arguments, MethodNode method) {
        var parameters = method.getParameters();
        for( int i = 0; i < arguments.size(); i++ ) {
            var argument = arguments.get(i);
            if( argument instanceof ClosureExpression source ) {
                var target = parameters[i];
                var resolvedPlaceholders = resolveGenericsConnections(receiverType, method, arguments);
                var samParameterTypes = resolveClosureParameterTypes(source, target, resolvedPlaceholders);
                argument.visit(this);
            }
        }
    }

    /**
     * Check process calls against the declared process inputs, and resolve
     * the return type based on the declared process outputs.
     *
     * When calling a process, each argument can be either the same type as
     * the declared input, channel of that type, or a dataflow value of that
     * type. For example, given a declared input with type String, the argument
     * should be either a String, Channel<String>, or Value<String>.
     *
     * The return type is determined by the argument types and the declared outputs.
     *
     * - When a process is called with a Channel argument, the output is wrapped
     *   in a Channel, otherwise it is wrapped in a Value.
     *
     * - When a process declares a single output expression, the return type contains
     *   only that type (e.g. T -> Channel<T>). When a process declares named outputs,
     *   the return type is a Record where each field is wrapped in the appropriate
     *   dataflow type.
     *
     * @param node
     */
    private boolean checkProcessCall(MethodCallExpression node) {
        var mn = (MethodNode) node.getNodeMetaData(ASTNodeMarker.METHOD_TARGET);
        if( !(mn instanceof ProcessNodeV2) )
            return false;

        var parameters = mn.getParameters();
        var arguments = asMethodCallArguments(node);
        if( parameters.length != arguments.size() )
            return false;

        for( int i = 0; i < parameters.length; i++ ) {
            var paramType = parameters[i].getType();
            var argType = getType(arguments.get(i));
            var elementType = dataflowElementType(argType);
            if( !TypesEx.isAssignableFrom(paramType, elementType) )
                addError("Argument with type " + TypesEx.getName(elementType) + " is not compatible with process input of type " + TypesEx.getName(paramType), arguments.get(i));
        }

        var numChannelArgs = arguments.stream()
            .filter((arg) -> CHANNEL_TYPE.equals(getType(arg)))
            .count();
        if( numChannelArgs > 1 )
            addError("Process `" + mn.getName() + "` was called with multiple channel arguments which can lead to non-deterministic behavior -- make sure that at most one argument is a channel and that all other arguments are dataflow values", node);

        var dataflowType = numChannelArgs > 0 ? CHANNEL_TYPE : VALUE_TYPE;
        var resultType = processOutputType(dataflowType, ((ProcessNodeV2) mn).outputs);
        node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);

        var methodVariable = currentWorkflow.getVariableScope().getReferencedClassVariable(mn.getName());
        if( methodVariable instanceof PropertyNode pn )
            pn.getType().getField("out").setType(resultType);

        return true;
    }

    private static ClassNode dataflowElementType(ClassNode type) {
        if( CHANNEL_TYPE.equals(type) || VALUE_TYPE.equals(type) )
            return elementType(type);
        return type;
    }

    private static ClassNode elementType(ClassNode type) {
        var gts = type.getGenericsTypes();
        if( gts == null || gts.length != 1 )
            return ClassHelper.dynamicType();
        return gts[0].getType();
    }

    private static ClassNode processOutputType(ClassNode dataflowType, Statement block) {
        var outputs = asBlockStatements(block);
        if( outputs.size() == 1 ) {
            var first = outputs.get(0);
            var output = ((ExpressionStatement) first).getExpression();
            if( outputTarget(output) == null )
                return processEmitType(dataflowType, getType(output));
        }
        var cn = new ClassNode(Record.class);
        for( var stmt : outputs ) {
            var output = ((ExpressionStatement) stmt).getExpression();
            var emitName = outputTarget(output).getName();
            var emitType = processEmitType(dataflowType, getType(output));
            var fn = new FieldNode(emitName, Modifier.PUBLIC, emitType, cn, null);
            fn.setDeclaringClass(cn);
            cn.addField(fn);
        }
        return cn;
    }

    private static ClassNode processEmitType(ClassNode dataflowType, ClassNode innerType) {
        return makeType(dataflowType, innerType);
    }

    private static VariableExpression outputTarget(Expression output) {
        if( output instanceof VariableExpression ve )
            return ve;
        if( output instanceof AssignmentExpression ae )
            return (VariableExpression)ae.getLeftExpression();
        return null;
    }

    private static String methodType(MethodNode node) {
        if( node instanceof ProcessNode )
            return "Process";
        if( node instanceof WorkflowNode )
            return "Workflow";
        return "Function";
    }

    private static String className(Expression node) {
        var receiverType = getType(node);
        return receiverType != null && receiverType.implementsInterface(ClassHelper.makeCached(Namespace.class))
            ? "namespace `" + node.getText() + "`"
            : "type " + TypesEx.getName(receiverType);
    }

    /**
     * Resolve tuple() calls by creating a custom Tuple type
     * that is parameterized based on the call arguments.
     *
     * For example, the return type of `tuple('hello', 42, true)`
     * should be Tuple<String, Integer, Boolean>.
     *
     * @param node
     */
    private boolean checkTupleCall(MethodCallExpression node) {
        if( !node.isImplicitThis() || !"tuple".equals(node.getMethodAsString()) )
            return false;

        var mn = (MethodNode) node.getNodeMetaData(ASTNodeMarker.METHOD_TARGET);
        if( mn == null || !TUPLE_TYPE.equals(mn.getReturnType()) )
            return false;

        var arguments = asMethodCallArguments(node);
        var dummyParams = IntStream.range(0, arguments.size())
            .mapToObj(i -> new Parameter(getType(arguments.get(i)), "arg" + i))
            .toArray(Parameter[]::new);
        var returnType = new ClassNode(Tuple.class);
        var genericsTypes = arguments.stream()
            .map(arg -> new GenericsType(getType(arg)))
            .toArray(GenericsType[]::new);
        returnType.setGenericsTypes(genericsTypes);

        var dummyMethod = asDummyMethod(null, mn, dummyParams, returnType);
        node.putNodeMetaData(ASTNodeMarker.METHOD_TARGET, dummyMethod);
        node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, returnType);
        return true;
    }

    @Override
    public void visitBinaryExpression(BinaryExpression node) {
        super.visitBinaryExpression(node);

        if( !experimental )
            return;

        var op = node.getOperation();
        if( op.getType() == Types.LEFT_SQUARE_BRACKET && checkTupleComponent(node) )
            return;

        var lhsType = getType(node.getLeftExpression());
        var rhsType = getType(node.getRightExpression());
        if( ClassHelper.isDynamicTyped(lhsType) || ClassHelper.isDynamicTyped(rhsType) )
            return;

        var lhsOps = resolveOpsType(lhsType);
        var rhsOps = resolveOpsType(rhsType);

        ClassNode resultType = null;        
        switch( op.getType() ) {
            case Types.POWER:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "power");
                break;

            case Types.MULTIPLY:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "multiply");
                break;

            case Types.DIVIDE:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "div");
                break;

            case Types.MOD:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "mod");
                break;

            case Types.PLUS:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "plus");
                break;

            case Types.MINUS:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "minus");
                break;

            case Types.LEFT_SHIFT:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "leftShift");
                break;

            case Types.RIGHT_SHIFT:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "rightShift");
                break;

            case Types.RIGHT_SHIFT_UNSIGNED:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "rightShiftUnsigned");
                break;

            case Types.COMPARE_LESS_THAN:
            case Types.COMPARE_LESS_THAN_EQUAL:
            case Types.COMPARE_GREATER_THAN:
            case Types.COMPARE_GREATER_THAN_EQUAL:
            case Types.COMPARE_TO:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "compareTo");
                break;

            case Types.KEYWORD_IN:
            case Types.COMPARE_NOT_IN:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "isCase");
                break;

            case Types.COMPARE_EQUAL:
            case Types.COMPARE_NOT_EQUAL:
                resultType = TypesEx.isEqual(lhsType, rhsType)
                    ? ClassHelper.Boolean_TYPE
                    : resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "compareTo");
                break;

            // TODO: =~: (String, String) -> Matcher
            case Types.FIND_REGEX:
                resultType = TypesEx.isEqual(ClassHelper.STRING_TYPE, lhsType) && TypesEx.isEqual(ClassHelper.STRING_TYPE, rhsType) ? ClassHelper.Boolean_TYPE : null;
                break;

            case Types.MATCH_REGEX:
                resultType = TypesEx.isEqual(ClassHelper.STRING_TYPE, lhsType) && TypesEx.isEqual(ClassHelper.STRING_TYPE, rhsType) ? ClassHelper.Boolean_TYPE : null;
                break;

            case Types.BITWISE_AND:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "and");
                break;

            case Types.BITWISE_XOR:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "xor");
                break;

            case Types.BITWISE_OR:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "or");
                break;

            case Types.LOGICAL_AND:
            case Types.LOGICAL_OR:
                return;

            case Types.LEFT_SQUARE_BRACKET:
                resultType = resolveOpResultType(lhsType, rhsType, lhsOps, rhsOps, "getAt");
                break;
        }

        if( resultType != null )
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);
        else
            addError(String.format("The `%s` operator is not defined for operands with types %s and %s", op.getText(), TypesEx.getName(lhsType), TypesEx.getName(rhsType)), node);
    }

    /**
     * Resolve the type of a tuple component expression.
     *
     * For example, the expression `tuple('hello', 42, true)[1]` has type Integer.
     *
     * @param node
     */
    private boolean checkTupleComponent(BinaryExpression node) {
        var lhs = node.getLeftExpression();
        var rhs = node.getRightExpression();
        var lhsType = getType(lhs);
        if( !TUPLE_TYPE.equals(lhsType) )
            return false;
        var index = rhs instanceof ConstantExpression ce && ce.getValue() instanceof Integer i ? i : null;
        if( index == null ) {
            addError("Tuple component index should be an integer literal", rhs);
            return true;
        }
        var gts = lhsType.getGenericsTypes();
        if( gts == null )
            return true;
        if( index < 0 || index >= gts.length ) {
            addError("Tuple component index is out of range -- it should range between 0 and " + (gts.length - 1), node);
            return true;
        }
        node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, gts[index].getType());
        return true;
    }

    @Override
    public void visitTernaryExpression(TernaryExpression node) {
        super.visitTernaryExpression(node);
        applyConditionalExpression(node);
    }

    @Override
    public void visitShortTernaryExpression(ElvisOperatorExpression node) {
        super.visitShortTernaryExpression(node);
        applyConditionalExpression(node);
    }

    private void applyConditionalExpression(TernaryExpression node) {
        var trueExpr = node.getTrueExpression();
        var falseExpr = node.getFalseExpression();
        var trueType = getType(trueExpr);
        var falseType = getType(falseExpr);

        if( ClassHelper.isDynamicTyped(trueType) || ClassHelper.isDynamicTyped(falseType) )
            return;

        ClassNode resultType;
        boolean nullable = true;
        if( isNullConstant(trueExpr) && isNullConstant(falseExpr) ) {
            resultType = null;
        }
        else if( !isNullConstant(trueExpr) && isNullConstant(falseExpr) ) {
            resultType = trueType;
        }
        else if( isNullConstant(trueExpr) && !isNullConstant(falseExpr) ) {
            resultType = falseType;
        }
        else if( TypesEx.isEqual(trueType, falseType) ) {
            resultType = trueType;
            nullable = isNullable(trueType) || isNullable(falseType);
        }
        else {
            if( experimental )
                addError(String.format("Conditional expression has inconsistent types -- true branch has type %s but false branch has type %s", TypesEx.getName(trueType), TypesEx.getName(falseType)), node);
            return;
        }

        if( nullable && resultType != null ) {
            resultType = new ClassNode(resultType.getTypeClass());
            resultType.putNodeMetaData(ASTNodeMarker.NULLABLE, Boolean.TRUE);
        }
        node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);
    }

    private static boolean isNullable(ClassNode cn) {
        return cn == null || cn.getNodeMetaData(ASTNodeMarker.NULLABLE) != null;
    }

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        super.visitClosureExpression(node);

        // resolve return type and check against declared return type
        if( !experimental )
            return;
        var returnType = (ClassNode) node.getNodeMetaData(ASTNodeMarker.INFERRED_RETURN_TYPE);
        if( returnType != null ) {
            var visitor = new ReturnStatementVisitor();
            visitor.visit(returnType, node.getCode());

            var inferredReturnType = visitor.getInferredReturnType();
            if( inferredReturnType != null )
                node.putNodeMetaData(ASTNodeMarker.INFERRED_RETURN_TYPE, inferredReturnType);
        }
    }

    @Override
    public void visitListExpression(ListExpression node) {
        super.visitListExpression(node);

        if( !experimental )
            return;

        ClassNode elementType = null;
        for( var el : node.getExpressions() ) {
            var type = getType(el);
            if( elementType == null ) {
                elementType = type;
            }
            else if( !TypesEx.isEqual(elementType, type) ) {
                addError(String.format("List expression has inconsistent element types -- some elements have type %s while others have type %s", TypesEx.getName(elementType), TypesEx.getName(type)), node);
                break;
            }
        }

        if( elementType != null ) {
            var resultType = makeType(ClassHelper.LIST_TYPE, elementType);
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);
        }
    }

    @Override
    public void visitMapExpression(MapExpression node) {
        super.visitMapExpression(node);
        node.setType(ClassHelper.MAP_TYPE.getPlainNodeReference());
    }

    @Override
    public void visitRangeExpression(RangeExpression node) {
        super.visitRangeExpression(node);

        var lhs = node.getFrom();
        var rhs = node.getTo();
        var lhsType = getType(lhs);
        var rhsType = getType(rhs);

        if( !TypesEx.isEqual(lhsType, rhsType) ) {
            addError("Lower bound and upper bound of range expression should have the same type", node);
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, ClassHelper.dynamicType());
            return;
        }

        var elementType = Arrays.stream(RANGE_TYPES)
            .filter(type -> TypesEx.isEqual(type, lhsType))
            .findFirst().orElse(null);

        if( elementType != null ) {
            var resultType = makeType(ClassHelper.LIST_TYPE, elementType);
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);
        }
        else {
            addError("Range expression with elements of type " + TypesEx.getName(lhsType) + " is not supported", node);
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, ClassHelper.dynamicType());
        }
    }

    private static final ClassNode[] RANGE_TYPES = new ClassNode[] { ClassHelper.Integer_TYPE, ClassHelper.STRING_TYPE };

    @Override
    public void visitUnaryMinusExpression(UnaryMinusExpression node) {
        super.visitUnaryMinusExpression(node);
        if( !experimental )
            return;
        resolveUnaryOpOrFail(node.getExpression(), "-", "negative", node);
    }

    @Override
    public void visitUnaryPlusExpression(UnaryPlusExpression node) {
        super.visitUnaryPlusExpression(node);
        if( !experimental )
            return;
        resolveUnaryOpOrFail(node.getExpression(), "+", "positive", node);
    }

    @Override
    public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
        super.visitBitwiseNegationExpression(node);
        if( !experimental )
            return;
        resolveUnaryOpOrFail(node.getExpression(), "~", "bitwiseNegate", node);
    }

    private void resolveUnaryOpOrFail(Expression operand, String op, String method, ASTNode node) {
        var type = getType(operand);
        var opsType = resolveOpsType(type);
        var resultType = resolveOpResultType(opsType, method);
        if( resultType != null )
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);
        else
            addError(String.format("The `%s` operator is not defined for an operand with type %s", op, TypesEx.getName(type)), node);
    }

    @Override
    public void visitCastExpression(CastExpression node) {
        super.visitCastExpression(node);
        if( !experimental )
            return;
        var sourceType = getType(node.getExpression());
        var targetType = node.getType();
        if( ClassHelper.isObjectType(sourceType) || TypesEx.isAssignableFrom(targetType, sourceType) )
            return;
        var opsType = resolveOpsType(targetType);
        if( resolveOpResultType(sourceType, opsType, "ofType") == null )
            addError(String.format("Value of type %s cannot be cast to %s", TypesEx.getName(sourceType), TypesEx.getName(targetType)), node);
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        super.visitPropertyExpression(node);

        if( !experimental )
            return;

        var receiver = node.getObjectExpression();
        var receiverType = getType(receiver);
        if( ClassHelper.isDynamicTyped(receiverType) )
            return;
        if( RECORD_TYPE.equals(receiverType) && receiverType.getFields().isEmpty() )
            return;

        if( node.isSpreadSafe() ) {
            checkSpreadProperty(node);
            return;
        }

        var type = getType(node);
        if( !ClassHelper.isDynamicTyped(type) )
            return;

        var mn = asMethodNamedOutput(node);
        var property = node.getPropertyAsString();
        if( mn instanceof ProcessNode pn ) {
            addError("Unrecognized output `" + property + "` for process `" + pn.getName() + "`", node);
        }
        else if( mn instanceof WorkflowNode wn ) {
            addError("Unrecognized output `" + property + "` for workflow `" + wn.getName() + "`", node);
        }
        else if( TypesEx.isEqual(receiverType, PARAMS_TYPE) ) {
            if( hasParamsBlock )
                addError("Unrecognized parameter `" + property + "`", node);
        }
        else {
            var className = className(receiver);
            addError(String.format("Unrecognized property `%s` for %s", property, className), node);
        }
    }

    /**
     * Check a spread-dot property access against an Iterable receiver
     * by resolving the method name and arguments against the
     * receiver's element type.
     *
     * For example, given the following property access:
     *
     *   files('*.txt')*.name
     *
     * The result type is inferred as follows:
     *
     * 1. The receiver type is Iterable<Path>
     * 2. The element type is Path
     * 3. The target field is Path::name, whose type is String
     * 4. Therefore the result type is Iterable<String>
     *
     * @param node
     */
    private void checkSpreadProperty(PropertyExpression node) {
        var receiverType = getType(node.getObjectExpression());
        if( !TypesEx.isAssignableFrom(ClassHelper.ITERABLE_TYPE, receiverType) ) {
            addError("Spread-dot is only supported for Iterable types", node);
            return;
        }
        var elementType = elementType(receiverType);
        var property = node.getPropertyAsString();
        var fn = resolveProperty(elementType, property);
        if( fn != null ) {
            var resultType = makeType(receiverType, fn.getType());
            node.putNodeMetaData(ASTNodeMarker.PROPERTY_TARGET, fn);
            node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, resultType);
        }
        else {
            addError(String.format("Unrecognized property `%s` for element type %s", property, TypesEx.getName(elementType)), node);
        }
    }

    private static MethodNode asMethodNamedOutput(PropertyExpression node) {
        if( node.getObjectExpression() instanceof PropertyExpression pe )
            return asMethodOutput(pe);
        return null;
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new TypeError(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private class TypeError extends SyntaxException implements PhaseAware {

        public TypeError(String message, ASTNode node) {
            super(message, node);
        }

        @Override
        public int getPhase() {
            return Phases.TYPE_CHECKING;
        }
    }
}

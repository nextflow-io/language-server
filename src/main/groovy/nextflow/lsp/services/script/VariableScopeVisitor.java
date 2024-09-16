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

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import groovy.json.JsonSlurper;
import nextflow.lsp.compiler.SyntaxWarning;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.EntryWorkflowDsl;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.FeatureFlagDsl;
import nextflow.script.dsl.Function;
import nextflow.script.dsl.OutputDsl;
import nextflow.script.dsl.ParamsMap;
import nextflow.script.dsl.ProcessDsl;
import nextflow.script.dsl.ProcessDirectiveDsl;
import nextflow.script.dsl.ProcessInputDsl;
import nextflow.script.dsl.ProcessOutputDsl;
import nextflow.script.dsl.ScriptDsl;
import nextflow.script.dsl.WorkflowDsl;
import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.IncludeNode;
import nextflow.script.v2.OutputNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.ScriptNode;
import nextflow.script.v2.ScriptVisitorSupport;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.v2.ASTHelpers.*;

/**
 * Initialize the variable scopes for an AST.
 *
 * See: org.codehaus.groovy.classgen.VariableScopeVisitor
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class VariableScopeVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private Map<String,Variable> includes = new HashMap<>();

    private MethodNode currentDefinition;

    private VariableScope currentScope;

    private ClassNode paramsType;

    public VariableScopeVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
        this.currentScope = new VariableScope();
        this.currentScope.setClassScope(new ClassNode(ScriptDsl.class));
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( !(moduleNode instanceof ScriptNode) )
            return;
        var scriptNode = (ScriptNode) moduleNode;

        // declare top-level names
        for( var includeNode : scriptNode.getIncludes() )
            declareInclude(includeNode);
        for( var workflowNode : scriptNode.getWorkflows() ) {
            if( !workflowNode.isEntry() )
                declareMethod(workflowNode);
        }
        for( var processNode : scriptNode.getProcesses() )
            declareMethod(processNode);
        for( var functionNode : scriptNode.getFunctions() )
            declareMethod(functionNode);

        // visit top-level definitions
        super.visit(scriptNode);
    }

    private void declareInclude(IncludeNode node) {
        for( var module : node.modules ) {
            var name = module.getName();
            if( includes.containsKey(name) )
                addError("`" + name + "` is already included", node);
            includes.put(name, module);
        }
    }

    private void declareMethod(MethodNode mn) {
        var cn = currentScope.getClassScope();
        var name = mn.getName();
        if( includes.containsKey(name) ) {
            addError("`" + name + "` is already included", mn);
        }
        if( cn.getDeclaredMethods(name).size() > 0 ) {
            addError("`" + name + "` is already declared", mn);
            return;
        }
        cn.addMethod(mn);
    }

    @Override
    public void visitFeatureFlag(FeatureFlagNode node) {
        var cn = ClassHelper.makeCached(FeatureFlagDsl.class);
        var result = cn.getFields().stream()
            .filter(fn ->
                findAnnotation(fn, FeatureFlag.class)
                    .map(an -> an.getMember("name").getText())
                    .map(name -> name.equals(node.name))
                    .orElse(false)
            )
            .findFirst();

        if( result.isPresent() )
            node.accessedVariable = result.get();
        else
            addError("Unrecognized feature flag '" + node.name + "'", node);
    }

    @Override
    public void visitWorkflow(WorkflowNode node) {
        if( node.isEntry() )
            declareParameters();

        pushState(node.isEntry() ? EntryWorkflowDsl.class : WorkflowDsl.class);
        currentDefinition = node;
        node.setVariableScope(currentScope);

        declareWorkflowInputs(node.takes);

        visit(node.main);
        if( node.main instanceof BlockStatement block )
            copyVariableScope(block.getVariableScope());

        visit(node.emits);
        visit(node.publishers);

        currentDefinition = null;
        popState();

        if( node.isEntry() )
            this.paramsType = null;
    }

    private void declareParameters() {
        // load parameter schema
        var uri = sourceUnit.getSource().getURI();
        var schemaPath = Path.of(uri).getParent().resolve("nextflow_schema.json");
        if( !Files.exists(schemaPath) )
            return;
        var schemaJson = getParameterSchema(schemaPath);

        var defs = Optional.ofNullable(schemaJson)
            .flatMap(json -> asMap(json))
            .flatMap(json ->
                json.containsKey("defs")
                    ? asMap(json.get("defs")) :
                json.containsKey("definitions")
                    ? asMap(json.get("definitions"))
                    : Optional.empty()
            )
            .orElse(Collections.emptyMap());

        var entries = (List<Map.Entry>) defs.values().stream()
            .filter(defn -> defn instanceof Map)
            .map(defn -> ((Map) defn).get("properties"))
            .filter(props -> props instanceof Map)
            .flatMap(props -> ((Map) props).entrySet().stream())
            .collect(Collectors.toList());

        if( entries.isEmpty() )
            return;

        // create synthetic params type
        var cn = new ClassNode(ParamsMap.class);

        for( var entry : entries ) {
            var name = (String) entry.getKey();
            var attrs = asMap(entry.getValue()).orElse(null);
            if( attrs == null )
                continue;
            var type = getTypeClassFromString((String) attrs.get("type"));
            var description = (String) attrs.get("description");
            var fn = new FieldNode(name, Modifier.PUBLIC, type, cn, null);
            fn.setHasNoRealSourcePosition(true);
            fn.setDeclaringClass(cn);
            fn.setSynthetic(true);
            var an = new AnnotationNode(ClassHelper.makeCached(Constant.class));
            an.addMember("value", new ConstantExpression(description));
            fn.addAnnotation(an);
            cn.addField(fn);
        }

        this.paramsType = cn;
    }

    private Object getParameterSchema(Path schemaPath) {
        try {
            var schemaText = IOGroovyMethods.getText(Files.newInputStream(schemaPath));
            return new JsonSlurper().parseText(schemaText);
        }
        catch( IOException e ) {
            System.err.println("Failed to read parameter schema: " + e.toString());
            return null;
        }
    }

    private static Optional<Map> asMap(Object value) {
        return value instanceof Map
            ? Optional.of((Map) value)
            : Optional.empty();
    }

    private ClassNode getTypeClassFromString(String type) {
        if( "boolean".equals(type) )
            return ClassHelper.boolean_TYPE;
        if( "integer".equals(type) )
            return ClassHelper.long_TYPE;
        if( "number".equals(type) )
            return ClassHelper.double_TYPE;
        if( "string".equals(type) )
            return ClassHelper.STRING_TYPE;
        return ClassHelper.dynamicType();
    }

    private void declareWorkflowInputs(Statement takes) {
        for( var stmt : asBlockStatements(takes) ) {
            var varX = asVarX(stmt);
            if( varX == null )
                continue;
            declare(varX);
        }
    }

    @Override
    public void visitProcess(ProcessNode node) {
        pushState(ProcessDsl.class);
        currentDefinition = node;
        node.setVariableScope(currentScope);

        declareProcessInputs(node.inputs);

        pushState(ProcessInputDsl.class);
        checkDirectives(node.inputs, "process input qualifier");
        visit(node.inputs);
        popState();

        if( !(node.when instanceof EmptyExpression) )
            addWarning("Process `when` section will not be supported in a future version", node.when);
        visit(node.when);

        visit(node.exec);
        visit(node.stub);

        pushState(ProcessDirectiveDsl.class);
        checkDirectives(node.directives, "process directive");
        visit(node.directives);
        popState();

        pushState(ProcessOutputDsl.class);
        if( node.exec instanceof BlockStatement block )
            copyVariableScope(block.getVariableScope());
        checkDirectives(node.outputs, "process output qualifier");
        visit(node.outputs);
        popState();

        currentDefinition = null;
        popState();
    }

    private void declareProcessInputs(Statement inputs) {
        for( var stmt : asBlockStatements(inputs) ) {
            var call = asMethodCallX(stmt);
            if( call == null )
                continue;
            if( "tuple".equals(call.getMethodAsString()) ) {
                for( var arg : asMethodCallArguments(call) ) {
                    if( arg instanceof MethodCallExpression mce )
                        declareProcessInput(mce);
                }
            }
            else if( "each".equals(call.getMethodAsString()) ) {
                var args = asMethodCallArguments(call);
                if( args.size() != 1 )
                    continue;
                var firstArg = args.get(0);
                if( firstArg instanceof MethodCallExpression mce )
                    declareProcessInput(mce);
                else if( firstArg instanceof VariableExpression ve )
                    declare(ve);
            }
            else {
                declareProcessInput(call);
            }
        }
    }

    private static final List<String> DECLARING_INPUT_TYPES = List.of("val", "file", "path");

    private void declareProcessInput(MethodCallExpression call) {
        if( !DECLARING_INPUT_TYPES.contains(call.getMethodAsString()) )
            return;
        var args = asMethodCallArguments(call);
        if( args.isEmpty() )
            return;
        if( args.get(args.size() - 1) instanceof VariableExpression ve )
            declare(ve);
    }

    private void checkDirectives(Statement node, String typeLabel, boolean checkSyntaxErrors) {
        for( var stmt : asBlockStatements(node) ) {
            var call = asMethodCallX(stmt);
            if( call == null ) {
                if( checkSyntaxErrors )
                    addError("Invalid " + typeLabel, stmt);
                continue;
            }
            var name = call.getMethodAsString();
            var variable = findClassMember(currentScope.getClassScope(), name, call.getMethod());
            if( variable == null )
                addError("Invalid " + typeLabel + " `" + name + "`", stmt);
        }
    }

    private void checkDirectives(Statement node, String typeLabel) {
        checkDirectives(node, typeLabel, false);
    }

    private void copyVariableScope(VariableScope source) {
        for( var it = source.getDeclaredVariablesIterator(); it.hasNext(); ) {
            var variable = it.next();
            currentScope.putDeclaredVariable(variable);
        }
    }

    private static final List<String> EMIT_AND_TOPIC = List.of("emit", "topic");

    @Override
    public void visitMapEntryExpression(MapEntryExpression node) {
        var classScope = currentScope.getClassScope();
        if( classScope != null && classScope.getTypeClass() == ProcessOutputDsl.class ) {
            var key = node.getKeyExpression();
            if( key instanceof ConstantExpression && EMIT_AND_TOPIC.contains(key.getText()) )
                return;
        }
        super.visitMapEntryExpression(node);
    }

    @Override
    public void visitFunction(FunctionNode node) {
        pushState();
        currentDefinition = node;
        node.setVariableScope(currentScope);

        for( var parameter : node.getParameters() ) {
            if( parameter.hasInitialExpression() )
                visit(parameter.getInitialExpression());
            declare(parameter, node);
        }
        visit(node.getCode());

        currentDefinition = null;
        popState();
    }

    @Override
    public void visitOutput(OutputNode node) {
        pushState(OutputDsl.class);

        if( node.body instanceof BlockStatement block )
            visitOutputBody(block);

        popState();
    }

    private void visitOutputBody(BlockStatement block) {
        block.setVariableScope(currentScope);

        asDirectives(block).forEach((call) -> {
            // treat as regular directive
            var name = call.getMethodAsString();
            var variable = findClassMember(currentScope.getClassScope(), name, call.getMethod());
            if( variable != null ) {
                visit(call);
                return;
            }

            // treat as target definition
            var code = asDslBlock(call, 1);
            if( code != null ) {
                pushState(OutputDsl.TargetDsl.class);
                checkDirectives(code, "output target directive", true);
                visitTargetBody(code);
                popState();
                return;
            }

            addError("Invalid output directive `" + name + "`", call);
        });
    }

    private void visitTargetBody(BlockStatement block) {
        block.setVariableScope(currentScope);

        asDirectives(block).forEach((call) -> {
            // treat as index definition
            var name = call.getMethodAsString();
            if( "index".equals(name) ) {
                var code = asDslBlock(call, 1);
                if( code != null ) {
                    pushState(OutputDsl.IndexDsl.class);
                    code.setVariableScope(null);
                    checkDirectives(code, "output index directive", true);
                    visit(code);
                    popState();
                    return;
                }
            }

            // treat as regular directive
            visit(call);
        });
    }

    // statements

    @Override
    public void visitBlockStatement(BlockStatement node) {
        var newScope = node.getVariableScope() != null;
        if( newScope ) pushState();
        node.setVariableScope(currentScope);
        super.visitBlockStatement(node);
        if( newScope ) popState();
    }

    @Override
    public void visitCatchStatement(CatchStatement node) {
        pushState();
        declare(node.getVariable(), node);
        super.visitCatchStatement(node);
        popState();
    }

    // statements

    private static final List<String> KEYWORDS = List.of("for", "switch", "while");

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        if( currentDefinition instanceof WorkflowNode ) {
            visitAssignmentOperator(node);
        }
        if( node.isImplicitThis() && node.getMethod() instanceof ConstantExpression ) {
            var name = node.getMethodAsString();
            var variable = findVariableDeclaration(name, node);
            if( variable == null ) {
                if( !KEYWORDS.contains(name) )
                    addError("`" + name + "` is not defined", node.getMethod());
            }
        }
        super.visitMethodCallExpression(node);
    }

    /**
     * Treat `set` operator as an assignment.
     */
    private void visitAssignmentOperator(MethodCallExpression node) {
        var name = node.getMethodAsString();
        if( !("set".equals(name) || "tap".equals(name)) )
            return;
        var code = asDslBlock(node, 1);
        if( code == null || code.getStatements().size() != 1 )
            return;
        var varX = asVarX(code.getStatements().get(0));
        if( varX == null )
            return;
        currentScope.putDeclaredVariable(varX);
    }

    @Override
    public void visitBinaryExpression(BinaryExpression node) {
        if( node.getOperation().isA(Types.ASSIGNMENT_OPERATOR) ) {
            visit(node.getRightExpression());
            visitAssignment(node.getLeftExpression());
            visit(node.getLeftExpression());
        }
        else {
            super.visitBinaryExpression(node);
        }
    }

    /**
     * In processes and workflows, variables can be declared without `def`
     * and are treated as variables scoped to the process or workflow.
     *
     * @param node
     */
    private void visitAssignment(Expression node) {
        if( node instanceof TupleExpression te ) {
            for( var el : te.getExpressions() )
                declareAssignedVariable((VariableExpression) el);
        }
        else if( node instanceof VariableExpression ve ) {
            declareAssignedVariable(ve);
        }
        else {
            visitMutatedVariable(node);
        }
    }

    private void declareAssignedVariable(VariableExpression ve) {
        var variable = findVariableDeclaration(ve.getName(), ve);
        if( variable != null ) {
            if( variable instanceof FieldNode fn && findAnnotation(fn, Constant.class).isPresent() )
                addError("Built-in variable cannot be re-assigned", ve);
        }
        else if( currentDefinition instanceof ProcessNode || currentDefinition instanceof WorkflowNode ) {
            if( inClosure )
                addError("Local variables in a closure should be declared with `def`", ve);
            else
                addWarning("Local variables should be declared with `def`", ve);
            var scope = currentScope;
            currentScope = currentDefinition.getVariableScope();
            declare(ve);
            currentScope = scope;
        }
        else {
            addError("`" + ve.getName() + "` was assigned but not declared", ve);
        }
    }

    private void visitMutatedVariable(Expression node) {
        VariableExpression target = null;
        while( true ) {
            // e.g. obj.prop = 123
            if( node instanceof PropertyExpression pe ) {
                node = pe.getObjectExpression();
            }
            // e.g. list[1] = 123 OR map['a'] = 123
            else if( node instanceof BinaryExpression be && be.getOperation().getType() == Types.LEFT_SQUARE_BRACKET ) {
                node = be.getLeftExpression();
            }
            else {
                if( node instanceof VariableExpression ve )
                    target = ve;
                break;
            }
        }
        if( target == null )
            return;
        var variable = findVariableDeclaration(target.getName(), target);
        if( variable instanceof FieldNode fn && findAnnotation(fn, Constant.class).isPresent() ) {
            if( "params".equals(variable.getName()) )
                addWarning("Assigning params in the script will not be supported in a future version", target);
            else
                addError("Built-in variable cannot be mutated", target);
        }
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression node) {
        visit(node.getRightExpression());

        if( node.isMultipleAssignmentDeclaration() ) {
            for( var el : node.getTupleExpression() )
                declare((VariableExpression) el);
        }
        else {
            declare(node.getVariableExpression());
        }
    }

    private boolean inClosure;

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        var ic = inClosure;
        inClosure = true;

        pushState();
        node.setVariableScope(currentScope);
        if( node.getParameters() != null ) {
            for( var parameter : node.getParameters() ) {
                declare(parameter, parameter);
                if( parameter.hasInitialExpression() )
                    visit(parameter.getInitialExpression());
            }
        }
        super.visitClosureExpression(node);
        for( var it = currentScope.getReferencedLocalVariablesIterator(); it.hasNext(); ) {
            var variable = it.next();
            variable.setClosureSharedVariable(true);
        }
        popState();

        inClosure = false;
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        super.visitPropertyExpression(node);

        // validate parameter against schema if applicable
        // NOTE: should be incorporated into type-checking visitor
        if( paramsType == null )
            return;
        if( !(node.getObjectExpression() instanceof VariableExpression) )
            return;
        var varX = (VariableExpression) node.getObjectExpression();
        if( !"params".equals(varX.getName()) )
            return;
        var property = node.getPropertyAsString();
        if( findClassMember(paramsType, property, node) == null ) {
            addError("Unrecognized parameter `" + property + "`", node);
            return;
        }
        var variable = varX.getAccessedVariable();
        if( variable instanceof FieldNode fn )
            fn.setType(paramsType);
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        var name = node.getName();
        Variable variable = findVariableDeclaration(name, node);
        if( variable == null ) {
            if( "it".equals(name) ) {
                addWarning("Implicit variable `it` in closure will not be supported in a future version", node);
            }
            else if( "args".equals(name) ) {
                addWarning("The use of `args` outside the entry workflow will not be supported in a future version", node);
            }
            else if( "params".equals(name) ) {
                addWarning("The use of `params` outside the entry workflow will not be supported in a future version", node);
            }
            else {
                variable = new DynamicVariable(name, false);
            }
        }
        if( variable != null )
            node.setAccessedVariable(variable);
    }

    // helpers

    private void pushState(Class classScope) {
        currentScope = new VariableScope(currentScope);
        if( classScope != null )
            currentScope.setClassScope(ClassHelper.makeCached(classScope));
    }

    private void pushState() {
        pushState(null);
    }

    private void popState() {
        currentScope = currentScope.getParent();
    }

    private void declare(VariableExpression variable) {
        declare(variable, variable);
        variable.setAccessedVariable(variable);
    }

    private void declare(Variable variable, ASTNode context) {
        var name = variable.getName();
        for( var scope = currentScope; scope != null; scope = scope.getParent() ) {
            if( scope.getDeclaredVariable(name) != null ) {
                addError("`" + name + "` is already declared", context);
                break;
            }
        }
        currentScope.putDeclaredVariable(variable);
    }

    /**
     * Find the declaration of a given variable.
     *
     * @param name
     * @param node
     */
    private Variable findVariableDeclaration(String name, ASTNode node) {
        Variable variable = null;
        VariableScope scope = currentScope;
        boolean isClassVariable = false;
        while( scope != null ) {
            variable = scope.getDeclaredVariable(name);
            if( variable != null )
                break;
            variable = scope.getReferencedLocalVariable(name);
            if( variable != null )
                break;
            variable = scope.getReferencedClassVariable(name);
            if( variable != null ) {
                isClassVariable = true;
                break;
            }
            variable = findClassMember(scope.getClassScope(), name, node);
            if( variable != null ) {
                isClassVariable = true;
                break;
            }
            variable = includes.get(name);
            if( variable != null ) {
                isClassVariable = true;
                break;
            }
            scope = scope.getParent();
        }
        if( variable == null )
            return null;
        VariableScope end = scope;
        scope = currentScope;
        while( true ) {
            if( isClassVariable )
                scope.putReferencedClassVariable(variable);
            else
                scope.putReferencedLocalVariable(variable);
            if( scope == end )
                break;
            scope = scope.getParent();
        }
        return variable;
    }

    private Variable findClassMember(ClassNode cn, String name, ASTNode node) {
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            var fn = cn.getDeclaredField(name);
            if( fn != null && findAnnotation(fn, Constant.class).isPresent() ) {
                if( findAnnotation(fn, Deprecated.class).isPresent() )
                    addWarning("`" + name + "` is deprecated and will be removed in a future version", node);
                return fn;
            }

            var methods = cn.getDeclaredMethods(name);
            var mn = methods.size() > 0 ? methods.get(0) : null;
            if( mn != null ) {
                if( mn instanceof FunctionNode || mn instanceof ProcessNode || mn instanceof WorkflowNode ) {
                    return wrapMethodAsVariable(mn, cn);
                }
                if( findAnnotation(mn, Function.class).isPresent() ) {
                    if( findAnnotation(mn, Deprecated.class).isPresent() )
                        addWarning("`" + name + "` is deprecated and will be removed in a future version", node);
                    return wrapMethodAsVariable(mn, cn);
                }
            }

            cn = cn.getSuperClass();
        }

        return null;
    }

    private Variable wrapMethodAsVariable(MethodNode mn, ClassNode cn) {
        var fn = new FieldNode(mn.getName(), mn.getModifiers() & 0xF, ClassHelper.dynamicType(), cn, null);
        fn.setHasNoRealSourcePosition(true);
        fn.setDeclaringClass(cn);
        fn.setSynthetic(true);
        var pn = new PropertyNode(fn, fn.getModifiers(), null, null);
        pn.putNodeMetaData("access.method", mn);
        pn.setDeclaringClass(cn);
        return pn;
    }

    @Override
    public void addError(String message, ASTNode node) {
        var cause = new SyntaxException(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    protected void addWarning(String message, ASTNode node) {
        var cause = new SyntaxWarning(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

}

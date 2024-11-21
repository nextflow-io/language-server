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
package nextflow.script.control;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.IncludeNode;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.EntryWorkflowDsl;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.FeatureFlagDsl;
import nextflow.script.dsl.OutputDsl;
import nextflow.script.dsl.ProcessDsl;
import nextflow.script.dsl.ScriptDsl;
import nextflow.script.dsl.WorkflowDsl;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
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
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.ast.ASTHelpers.*;

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

    private Set<Variable> declaredVariables = Collections.newSetFromMap(new IdentityHashMap<>());

    public VariableScopeVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
        this.currentScope = new VariableScope();
        this.currentScope.setClassScope(new ClassNode(ScriptDsl.class));
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public void declare() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ScriptNode sn ) {
            for( var includeNode : sn.getIncludes() )
                declareInclude(includeNode);
            for( var workflowNode : sn.getWorkflows() ) {
                if( !workflowNode.isEntry() )
                    declareMethod(workflowNode);
            }
            for( var processNode : sn.getProcesses() )
                declareMethod(processNode);
            for( var functionNode : sn.getFunctions() )
                declareMethod(functionNode);
        }
    }

    private void declareInclude(IncludeNode node) {
        for( var module : node.modules ) {
            var name = module.getName();
            var otherInclude = includes.get(name);
            if( otherInclude != null )
                addError("`" + name + "` is already included", node, "First included here", (ASTNode) otherInclude);
            includes.put(name, module);
        }
    }

    private void declareMethod(MethodNode mn) {
        var cn = currentScope.getClassScope();
        var name = mn.getName();
        var otherInclude = includes.get(name);
        if( otherInclude != null ) {
            addError("`" + name + "` is already included", mn, "First included here", (ASTNode) otherInclude);
        }
        var otherMethods = cn.getDeclaredMethods(name);
        if( otherMethods.size() > 0 ) {
            var other = otherMethods.get(0);
            var first = mn.getLineNumber() < other.getLineNumber() ? mn : other;
            var second = mn.getLineNumber() < other.getLineNumber() ? other : mn;
            addError("`" + name + "` is already declared", second, "First declared here", first);
            return;
        }
        cn.addMethod(mn);
    }

    public void visit() {
        var moduleNode = sourceUnit.getAST();
        if( moduleNode instanceof ScriptNode sn ) {
            // visit top-level definitions
            super.visit(sn);

            // warn about any unused local variables
            for( var variable : declaredVariables ) {
                if( variable instanceof ASTNode node && !variable.getName().startsWith("_") ) {
                    var message = variable instanceof Parameter
                        ? "Parameter was not used -- prefix with `_` to suppress warning"
                        : "Variable was declared but not used";
                    sourceUnit.addWarning(message, node);
                }
            }
        }
    }

    @Override
    public void visitFeatureFlag(FeatureFlagNode node) {
        var cn = ClassHelper.makeCached(FeatureFlagDsl.class);
        var result = cn.getFields().stream()
            .filter(fn ->
                findAnnotation(fn, FeatureFlag.class)
                    .map(an -> an.getMember("value").getText())
                    .map(name -> name.equals(node.name))
                    .orElse(false)
            )
            .findFirst();

        if( result.isPresent() ) {
            var ffn = result.get();
            if( findAnnotation(ffn, Deprecated.class).isPresent() )
                addFutureWarning("`" + node.name + "` is deprecated and will be removed in a future version", node.name, node);
            node.target = ffn;
        }
        else {
            addError("Unrecognized feature flag '" + node.name + "'", node);
        }
    }

    private boolean inWorkflowEmit;

    @Override
    public void visitWorkflow(WorkflowNode node) {
        pushState(node.isEntry() ? EntryWorkflowDsl.class : WorkflowDsl.class);
        currentDefinition = node;
        node.setVariableScope(currentScope);

        declareWorkflowInputs(node.takes);

        visit(node.main);
        if( node.main instanceof BlockStatement block )
            copyVariableScope(block.getVariableScope());

        visitWorkflowEmits(node.emits);
        visit(node.publishers);

        currentDefinition = null;
        popState();
    }

    private void declareWorkflowInputs(Statement takes) {
        for( var stmt : asBlockStatements(takes) ) {
            var varX = asVarX(stmt);
            if( varX == null )
                continue;
            declare(varX);
        }
    }

    private void copyVariableScope(VariableScope source) {
        for( var it = source.getDeclaredVariablesIterator(); it.hasNext(); ) {
            var variable = it.next();
            currentScope.putDeclaredVariable(variable);
        }
    }

    private void visitWorkflowEmits(Statement emits) {
        var declaredEmits = new HashMap<String,ASTNode>();
        for( var stmt : asBlockStatements(emits) ) {
            var stmtX = (ExpressionStatement)stmt;
            var emit = stmtX.getExpression();
            if( emit instanceof AssignmentExpression assign ) {
                visit(assign.getRightExpression());

                var target = (VariableExpression)assign.getLeftExpression();
                var name = target.getName();
                var other = declaredEmits.get(name);
                if( other != null )
                    addError("Workflow emit `" + name + "` is already declared", target, "First declared here", other);
                else
                    declaredEmits.put(name, target);
            }
            else {
                visit(emit);
            }
        }
    }

    @Override
    public void visitProcess(ProcessNode node) {
        pushState(ProcessDsl.class);
        currentDefinition = node;
        node.setVariableScope(currentScope);

        declareProcessInputs(node.inputs);

        pushState(ProcessDsl.InputDsl.class);
        visitDirectives(node.inputs, "process input qualifier", false);
        popState();

        if( !(node.when instanceof EmptyExpression) )
            addFutureWarning("Process `when` section will not be supported in a future version", node.when);
        visit(node.when);

        visit(node.exec);
        visit(node.stub);

        pushState(ProcessDsl.DirectiveDsl.class);
        visitDirectives(node.directives, "process directive", false);
        popState();

        pushState(ProcessDsl.OutputDsl.class);
        visitDirectives(node.outputs, "process output qualifier", false);
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

    private void visitDirectives(Statement node, String typeLabel, boolean checkSyntaxErrors) {
        if( node instanceof BlockStatement block )
            block.setVariableScope(currentScope);
        for( var stmt : asBlockStatements(node) ) {
            var call = checkDirective(stmt, typeLabel, checkSyntaxErrors);
            if( call != null )
                super.visitMethodCallExpression(call);
        }
    }

    private MethodCallExpression checkDirective(Statement node, String typeLabel, boolean checkSyntaxErrors) {
        var call = asMethodCallX(node);
        if( call == null ) {
            if( checkSyntaxErrors )
                addSyntaxError("Invalid " + typeLabel, node);
            return null;
        }
        var name = call.getMethodAsString();
        var variable = findDslMember(currentScope.getClassScope(), name, call.getMethod());
        if( variable != null )
            currentScope.putReferencedClassVariable(variable);
        else
            addError("Invalid " + typeLabel + " `" + name + "`", node);
        return call;
    }

    private static final List<String> EMIT_AND_TOPIC = List.of("emit", "topic");

    @Override
    public void visitMapEntryExpression(MapEntryExpression node) {
        var classScope = currentScope.getClassScope();
        if( classScope != null && classScope.getTypeClass() == ProcessDsl.OutputDsl.class ) {
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
        if( node.body instanceof BlockStatement block )
            visitOutputBody(block);
    }

    private void visitOutputBody(BlockStatement block) {
        block.setVariableScope(currentScope);

        asDirectives(block).forEach((call) -> {
            var code = asDslBlock(call, 1);
            if( code != null )
                visitTargetBody(code);
        });
    }

    private void visitTargetBody(BlockStatement block) {
        pushState(OutputDsl.class);
        block.setVariableScope(currentScope);

        asBlockStatements(block).forEach((stmt) -> {
            // validate target directive
            var call = checkDirective(stmt, "output target directive", true);
            if( call == null )
                return;

            // treat as index definition
            var name = call.getMethodAsString();
            if( "index".equals(name) ) {
                var code = asDslBlock(call, 1);
                if( code != null ) {
                    pushState(OutputDsl.IndexDsl.class);
                    visitDirectives(code, "output index directive", true);
                    popState();
                    return;
                }
            }

            // treat as regular directive
            super.visitMethodCallExpression(call);
        });
        popState();
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

    // expressions

    private static final List<String> KEYWORDS = List.of(
        "case",
        "for",
        "switch",
        "while"
    );

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
        if( node instanceof AssignmentExpression ) {
            visit(node.getRightExpression());
            visitAssignmentTarget(node.getLeftExpression());
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
    private void visitAssignmentTarget(Expression node) {
        if( node instanceof TupleExpression te ) {
            for( var el : te.getExpressions() )
                declareAssignedVariable((VariableExpression) el);
        }
        else if( node instanceof VariableExpression ve ) {
            declareAssignedVariable(ve);
        }
        else {
            visitMutatedVariable(node);
            visit(node);
        }
    }

    private void declareAssignedVariable(VariableExpression ve) {
        var variable = findVariableDeclaration(ve.getName(), ve);
        if( variable != null ) {
            if( variable instanceof PropertyNode pn && pn.getNodeMetaData("access.method") != null )
                addError("Built-in variable cannot be re-assigned", ve);
            else
                checkExternalWriteInClosure(ve, variable);
        }
        else if( currentDefinition instanceof ProcessNode || currentDefinition instanceof WorkflowNode ) {
            if( currentClosure != null )
                addError("Variables in a closure should be declared with `def`", ve);
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
        if( variable instanceof PropertyNode pn && pn.getNodeMetaData("access.method") != null ) {
            if( "params".equals(variable.getName()) )
                sourceUnit.addWarning("Params should be declared at the top-level (i.e. outside the workflow)", target);
            // TODO: re-enable after workflow.onComplete bug is fixed
            // else
            //     addError("Built-in variable cannot be mutated", target);
        }
        else if( variable != null ) {
            checkExternalWriteInClosure(target, variable);
        }
    }

    private void checkExternalWriteInClosure(VariableExpression target, Variable variable) {
        if( currentClosure == null )
            return;
        var scope = currentClosure.getVariableScope();
        var name = variable.getName();
        if( scope.isReferencedLocalVariable(name) && scope.getDeclaredVariable(name) == null )
            addFutureWarning("Mutating an external variable in a closure may lead to a race condition", target, "External variable declared here", (ASTNode) variable);
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

    private ClosureExpression currentClosure;

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        var cl = currentClosure;
        currentClosure = node;

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

        currentClosure = cl;
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        var name = node.getName();
        Variable variable = findVariableDeclaration(name, node);
        if( variable == null ) {
            if( "it".equals(name) ) {
                addFutureWarning("Implicit variable `it` in closure will not be supported in a future version", node);
            }
            else if( "args".equals(name) ) {
                addFutureWarning("The use of `args` outside the entry workflow will not be supported in a future version", node);
            }
            else if( "params".equals(name) ) {
                addFutureWarning("The use of `params` outside the entry workflow will not be supported in a future version", node);
            }
            else {
                variable = new DynamicVariable(name, false);
            }
        }
        if( variable != null ) {
            checkGlobalVariableInProcess(variable, node);
            node.setAccessedVariable(variable);
        }
    }

    private static final List<String> WARN_GLOBALS = List.of(
        "baseDir",
        "launchDir",
        "projectDir",
        "workDir"
    );

    private void checkGlobalVariableInProcess(Variable variable, ASTNode context) {
        if( !(currentDefinition instanceof ProcessNode) )
            return;
        if( variable instanceof PropertyNode pn && pn.getDeclaringClass().getTypeClass() == ScriptDsl.class ) {
            if( WARN_GLOBALS.contains(variable.getName()) )
                sourceUnit.addWarning("The use of `" + variable.getName() + "` in a process is discouraged -- input files should be provided as process inputs", context);
        }
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
            var other = scope.getDeclaredVariable(name);
            if( other != null ) {
                addError("`" + name + "` is already declared", context, "First declared here", (ASTNode) other);
                break;
            }
        }
        currentScope.putDeclaredVariable(variable);
        declaredVariables.add(variable);
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
            variable = findDslMember(scope.getClassScope(), name, node);
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
        declaredVariables.remove(variable);
        return variable;
    }

    private Variable findDslMember(ClassNode cn, String name, ASTNode node) {
        while( cn != null ) {
            for( var mn : cn.getMethods() ) {
                var an = findAnnotation(mn, Constant.class);
                var memberName = an.isPresent()
                    ? an.get().getMember("value").getText()
                    : mn.getName();
                if( !name.equals(memberName) )
                    continue;
                if( findAnnotation(mn, Deprecated.class).isPresent() )
                    addFutureWarning("`" + name + "` is deprecated and will be removed in a future version", node);
                return wrapMethodAsVariable(mn, memberName);
            }

            cn = cn.getInterfaces().length > 0
                ? cn.getInterfaces()[0]
                : null;
        }

        return null;
    }

    private Variable wrapMethodAsVariable(MethodNode mn, String name) {
        var cn = mn.getDeclaringClass();
        var fn = new FieldNode(name, mn.getModifiers() & 0xF, mn.getReturnType(), cn, null);
        fn.setHasNoRealSourcePosition(true);
        fn.setDeclaringClass(cn);
        fn.setSynthetic(true);
        var pn = new PropertyNode(fn, fn.getModifiers(), null, null);
        pn.putNodeMetaData("access.method", mn);
        pn.setDeclaringClass(cn);
        return pn;
    }

    protected void addSyntaxError(String message, ASTNode node) {
        var cause = new SyntaxException(message, node);
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    protected void addFutureWarning(String message, String tokenText, ASTNode node, String otherMessage, ASTNode otherNode) {
        var token = new Token(0, tokenText, node.getLineNumber(), node.getColumnNumber()); // ASTNode to CSTNode
        var warning = new FutureWarning(WarningMessage.POSSIBLE_ERRORS, message, token, sourceUnit);
        if( otherNode != null )
            warning.setRelatedInformation(otherMessage, otherNode);
        sourceUnit.getErrorCollector().addWarning(warning);
    }

    protected void addFutureWarning(String message, ASTNode node, String otherMessage, ASTNode otherNode) {
        addFutureWarning(message, "", node, otherMessage, otherNode);
    }

    protected void addFutureWarning(String message, String tokenText, ASTNode node) {
        addFutureWarning(message, tokenText, node, null, null);
    }

    protected void addFutureWarning(String message, ASTNode node) {
        addFutureWarning(message, "", node, null, null);
    }

    @Override
    public void addError(String message, ASTNode node) {
        addError(new VariableScopeError(message, node));
    }

    protected void addError(String message, ASTNode node, String otherMessage, ASTNode otherNode) {
        var cause = new VariableScopeError(message, node);
        if( otherNode != null )
            cause.setRelatedInformation(otherMessage, otherNode);
        addError(cause);
    }

    protected void addError(SyntaxException cause) {
        var errorMessage = new SyntaxErrorMessage(cause, sourceUnit);
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage);
    }

    private class VariableScopeError extends SyntaxException implements PhaseAware, RelatedInformationAware {

        private String otherMessage;

        private ASTNode otherNode;

        public VariableScopeError(String message, ASTNode node) {
            super(message, node);
        }

        public void setRelatedInformation(String otherMessage, ASTNode otherNode) {
            this.otherMessage = otherMessage;
            this.otherNode = otherNode;
        }

        @Override
        public int getPhase() {
            return Phases.NAME_RESOLUTION;
        }

        @Override
        public String getOtherMessage() {
            return otherMessage;
        }

        @Override
        public ASTNode getOtherNode() {
            return otherNode;
        }
    }

}

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

import java.lang.reflect.Modifier
import java.nio.file.Path

import groovy.json.JsonSlurper
import groovy.lang.groovydoc.Groovydoc
import groovy.lang.groovydoc.GroovydocHolder
import groovy.transform.CompileStatic
import nextflow.lsp.compiler.SyntaxWarning
import nextflow.script.dsl.Constant
import nextflow.script.dsl.EntryWorkflowDsl
import nextflow.script.dsl.FeatureFlag
import nextflow.script.dsl.FeatureFlagDsl
import nextflow.script.dsl.Function
import nextflow.script.dsl.Operator
import nextflow.script.dsl.OutputDsl
import nextflow.script.dsl.ParamsMap
import nextflow.script.dsl.ProcessDsl
import nextflow.script.dsl.ProcessDirectiveDsl
import nextflow.script.dsl.ProcessInputDsl
import nextflow.script.dsl.ProcessOutputDsl
import nextflow.script.dsl.ScriptDsl
import nextflow.script.dsl.WorkflowDsl
import nextflow.script.v2.FeatureFlagNode
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.IncludeNode
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.ScriptVisitor
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Types

/**
 * Initialize the variable scopes for an AST.
 *
 * See: org.codehaus.groovy.classgen.VariableScopeVisitor
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class VariableScopeVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

    private SourceUnit sourceUnit

    private ASTNode currentTopLevelNode

    private VariableScope currentScope

    private ClassNode paramsType

    VariableScopeVisitor(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
        this.currentScope = new VariableScope()
        this.currentScope.setClassScope(new ClassNode(ScriptDsl))
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    private void pushState(Class classScope=null) {
        currentScope = new VariableScope(currentScope)
        if( classScope )
            currentScope.setClassScope(new ClassNode(classScope))
    }

    private void popState() {
        currentScope = currentScope.parent
    }

    private void declare(MethodNode node) {
        final cn = currentScope.getClassScope()
        for( final mn : cn.getMethods() ) {
            if( mn.getName() == node.getName() ) {
                addError("`${node.getName()}` is already defined", node)
                break
            }
        }
        cn.addMethod(node)
    }

    private void declare(VariableExpression variable) {
        declare(variable, variable)
        variable.setAccessedVariable(variable)
    }

    private void declare(Variable variable, ASTNode context) {
        VariableScope scope = currentScope
        while( scope != null ) {
            if( variable.name in scope.getDeclaredVariables() ) {
                addError("`${variable.name}` is already declared", context)
                break
            }
            scope = scope.parent
        }
        currentScope.putDeclaredVariable(variable)
    }

    /**
     * Find the declaration of a given variable.
     *
     * @param name
     * @param node
     */
    private Variable findVariableDeclaration(String name, ASTNode node) {
        Variable variable = null
        VariableScope scope = currentScope
        while( scope != null ) {
            variable = scope.getDeclaredVariable(name)
            if( variable )
                break
            variable = scope.getReferencedLocalVariable(name)
            if( variable )
                break
            variable = scope.getReferencedClassVariable(name)
            if( variable )
                break
            variable = findClassMember(scope.getClassScope(), name, node)
            if( variable )
                break
            scope = scope.parent
        }
        if( !variable )
            return null
        final isClassVariable = !scope.getDeclaredVariable(name) && ((scope.isClassScope() && !scope.isReferencedLocalVariable(name)) || scope.isReferencedClassVariable(name))
        VariableScope end = scope
        scope = currentScope
        while( true ) {
            if( isClassVariable )
                scope.putReferencedClassVariable(variable)
            else
                scope.putReferencedLocalVariable(variable)
            if( scope == end )
                break
            scope = scope.parent
        }
        return variable
    }

    private Variable findClassMember(ClassNode cn, String name, ASTNode node) {
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            for( final fn : cn.getFields() ) {
                if( fn.getName() != name )
                    continue
                final annot = findAnnotation(fn, Constant)
                if( !annot )
                    continue
                if( findAnnotation(fn, Deprecated) )
                    addWarning("`${name}` is deprecated and will be removed in a future version", node)
                final description = annot.getMember('value').getText().stripIndent(true).trim()
                final content = wrapDescriptionAsGroovydoc(description)
                fn.putNodeMetaData(GroovydocHolder.DOC_COMMENT, new Groovydoc(content, fn))
                return fn
            }
            for( final mn : cn.getMethods() ) {
                if( mn.getName() != name )
                    continue
                if( mn instanceof FunctionNode || mn instanceof ProcessNode || mn instanceof WorkflowNode )
                    return wrapMethodAsVariable(mn, cn)
                final annot = findAnnotation(mn, Function)
                if( !annot )
                    continue
                if( findAnnotation(mn, Deprecated) )
                    addWarning("`${name}` is deprecated and will be removed in a future version", node)
                final documentation = annot.getMember('value').getText().stripIndent(true).trim()
                final mnWithDocs = new FunctionNode(name, documentation)
                final methodTypeLabel = getMethodTypeLabel(mn, cn)
                if( methodTypeLabel )
                    mnWithDocs.putNodeMetaData('type.label', methodTypeLabel)
                return wrapMethodAsVariable(mnWithDocs, cn)
            }
            cn = cn.getSuperClass()
        }

        return null
    }

    private AnnotationNode findAnnotation(AnnotatedNode node, Class annotation) {
        return node.getAnnotations().find(an -> an.getClassNode().getName() == annotation.name)
    }

    private String getMethodTypeLabel(MethodNode mn, ClassNode cn) {
        if( findAnnotation(mn, Operator) )
            return 'operator'
        if( cn.getTypeClass() == ProcessDirectiveDsl )
            return 'process directive'
        if( cn.getTypeClass() == ProcessInputDsl )
            return 'process input'
        if( cn.getTypeClass() == ProcessOutputDsl )
            return 'process output'
        if( cn.getTypeClass() == OutputDsl )
            return 'output directive'
        return null
    }

    private Variable wrapMethodAsVariable(MethodNode mn, ClassNode cn) {
        final fn = new FieldNode(mn.getName(), mn.getModifiers() & 0xF, ClassHelper.dynamicType(), cn, null)
        fn.setHasNoRealSourcePosition(true)
        fn.setDeclaringClass(cn)
        fn.setSynthetic(true)
        final pn = new PropertyNode(fn, fn.getModifiers(), null, null)
        pn.putNodeMetaData('access.method', mn)
        pn.setDeclaringClass(cn)
        return pn
    }

    private String wrapDescriptionAsGroovydoc(String description) {
        final builder = new StringBuilder()
        builder.append('/**\n')
        for( final line : description.split('\n') ) {
            builder.append(' * ')
            builder.append(line)
            builder.append('\n')
        }
        builder.append(' */\n')
        return builder.toString()
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode !instanceof ScriptNode )
            return
        final scriptNode = (ScriptNode) moduleNode

        // declare top-level names
        for( final includeNode : scriptNode.getIncludes() )
            visitInclude(includeNode)
        for( final functionNode : scriptNode.getFunctions() )
            declare(functionNode)
        for( final processNode : scriptNode.getProcesses() )
            declare(processNode)
        for( final workflowNode : scriptNode.getWorkflows() ) {
            if( !workflowNode.isEntry() )
                declare(workflowNode)
        }

        // visit top-level definitions
        for( final featureFlag : scriptNode.getFeatureFlags() )
            visitFeatureFlag(featureFlag)
        for( final functionNode : scriptNode.getFunctions() )
            visitFunction(functionNode)
        for( final processNode : scriptNode.getProcesses() )
            visitProcess(processNode)
        for( final workflowNode : scriptNode.getWorkflows() )
            visitWorkflow(workflowNode)
        if( scriptNode.getOutput() )
            visitOutput(scriptNode.getOutput())
    }

    @Override
    void visitFeatureFlag(FeatureFlagNode node) {
        final cn = new ClassNode(FeatureFlagDsl)
        for( final fn : cn.getFields() ) {
            final annot = findAnnotation(fn, FeatureFlag)
            if( !annot )
                continue
            final name = annot.getMember('name').getText()
            if( name == node.name ) {
                node.accessedVariable = fn
                return
            }
        }
        addError("Unrecognized feature flag '${node.name}'", node)
    }

    @Override
    void visitInclude(IncludeNode node) {
        for( final module : node.modules ) {
            declare(module, node)
        }
    }

    @Override
    void visitFunction(FunctionNode node) {
        pushState()
        currentTopLevelNode = node
        node.variableScope = currentScope

        for( final parameter : node.parameters ) {
            if( parameter.hasInitialExpression() )
                visit(parameter.initialExpression)
            declare(parameter, node)
        }
        visit(node.code)

        currentTopLevelNode = null
        popState()
    }

    @Override
    void visitProcess(ProcessNode node) {
        pushState(ProcessDsl)
        currentTopLevelNode = node
        node.variableScope = currentScope

        if( node.inputs instanceof BlockStatement )
            declareProcessInputs((BlockStatement) node.inputs)

        pushState(ProcessInputDsl)
        checkDirectives(node.inputs, 'process input qualifier')
        visit(node.inputs)
        popState()

        if( node.when !instanceof EmptyExpression )
            addWarning('Process `when` section will not be supported in a future version', node.when)
        visit(node.when)

        visit(node.exec)
        visit(node.stub)

        pushState(ProcessDirectiveDsl)
        checkDirectives(node.directives, 'process directive')
        visit(node.directives)
        popState()

        pushState(ProcessOutputDsl)
        if( node.exec instanceof BlockStatement )
            copyVariableScope(node.exec.variableScope)
        checkDirectives(node.outputs, 'process output qualifier')
        visit(node.outputs)
        popState()

        currentTopLevelNode = null
        popState()
    }

    private void declareProcessInputs(BlockStatement block) {
        for( final stmt : block.statements ) {
            final call = asMethodCallX(stmt)
            if( !call )
                continue
            if( call.getMethodAsString() == 'tuple' ) {
                final args = (ArgumentListExpression) call.arguments
                for( final arg : args ) {
                    if( arg !instanceof MethodCallExpression )
                        continue
                    declareProcessInput((MethodCallExpression) arg)
                }
            }
            else if( call.getMethodAsString() == 'each' ) {
                final args = (ArgumentListExpression) call.arguments
                if( args.size() != 1 )
                    continue
                final firstArg = args.first()
                if( firstArg instanceof MethodCallExpression )
                    declareProcessInput((MethodCallExpression) firstArg)
                else if( firstArg instanceof VariableExpression )
                    declare(firstArg)
            }
            else {
                declareProcessInput(call)
            }
        }
    }

    static private final List<String> DECLARING_INPUT_TYPES = ['val', 'file', 'path']

    private void declareProcessInput(MethodCallExpression call) {
        if( call.getMethodAsString() !in DECLARING_INPUT_TYPES )
            return
        final args = (ArgumentListExpression) call.arguments
        if( args.size() < 1 || args.last() !instanceof VariableExpression )
            return
        final varX = (VariableExpression) args.last()
        declare(varX)
    }

    private void checkDirectives(Statement node, String typeLabel, boolean checkSyntaxErrors=false) {
        if( node !instanceof BlockStatement )
            return
        final block = (BlockStatement) node
        for( final stmt : block.statements ) {
            final call = asMethodCallX(stmt)
            if( !call ) {
                if( checkSyntaxErrors )
                    addError("Invalid ${typeLabel}", stmt)
                continue
            }
            final name = call.getMethodAsString()
            if( !findClassMember(currentScope.getClassScope(), name, call.getMethod()) )
                addError("Invalid ${typeLabel} `${name}`", stmt)
        }
    }

    private void copyVariableScope(VariableScope source) {
        for( final var : source.getDeclaredVariables().values() )
            currentScope.putDeclaredVariable(var)
    }

    @Override
    void visitMapEntryExpression(MapEntryExpression node) {
        if( currentScope.getClassScope()?.getTypeClass() == ProcessOutputDsl ) {
            final key = node.keyExpression
            if( key instanceof ConstantExpression && key.text in ['emit', 'topic'] )
                return
        }
        super.visitMapEntryExpression(node)
    }

    @Override
    void visitWorkflow(WorkflowNode node) {
        if( node.isEntry() )
            declareParameters()

        pushState(node.name ? WorkflowDsl : EntryWorkflowDsl)
        currentTopLevelNode = node
        node.variableScope = currentScope

        if( node.takes instanceof BlockStatement )
            declareWorkflowInputs((BlockStatement) node.takes)

        visit(node.main)
        if( node.main instanceof BlockStatement )
            copyVariableScope(node.main.variableScope)

        visit(node.emits)
        visit(node.publishers)

        currentTopLevelNode = null
        popState()

        if( node.isEntry() )
            this.paramsType = null
    }

    private void declareParameters() {
        // load parameter schema
        final uri = sourceUnit.getSource().getURI()
        final schemaPath = Path.of(uri).getParent().resolve('nextflow_schema.json')
        if( !schemaPath.exists() )
            return
        final schemaJson = new JsonSlurper().parseText(schemaPath.text) as Map
        final defs = (schemaJson.defs ?: schemaJson.definitions) as Map<String,Map>

        // create synthetic params type
        final cn = new ClassNode(ParamsMap)
        defs.values().stream()
            .flatMap((defn) -> {
                final props = defn.properties as Map<String, Map<String,String>>
                return props.entrySet().stream()
            })
            .forEach((entry) -> {
                final name = entry.key
                final attrs = entry.value
                final type = getTypeClassFromString(attrs.type)
                final description = attrs.description
                final fn = new FieldNode(name, Modifier.PUBLIC, type, cn, null)
                fn.setHasNoRealSourcePosition(true)
                fn.setDeclaringClass(cn)
                fn.setSynthetic(true)
                final an = new AnnotationNode(new ClassNode(Constant))
                an.addMember('value', new ConstantExpression(description))
                fn.addAnnotation(an)
                cn.addField(fn)
            })

        this.paramsType = cn
    }

    private ClassNode getTypeClassFromString(String type) {
        if( type == 'boolean' )
            return ClassHelper.boolean_TYPE
        if( type == 'integer' )
            return ClassHelper.long_TYPE
        if( type == 'number' )
            return ClassHelper.double_TYPE
        if( type == 'string' )
            return ClassHelper.STRING_TYPE
        return ClassHelper.dynamicType()
    }

    private void declareWorkflowInputs(BlockStatement block) {
        for( final stmt : block.statements ) {
            final varX = asVarX(stmt)
            if( !varX )
                continue
            declare(varX)
        }
    }

    @Override
    void visitOutput(OutputNode node) {
        pushState(OutputDsl)
        currentTopLevelNode = node

        if( node.body instanceof BlockStatement )
            visitOutputBody((BlockStatement) node.body)

        currentTopLevelNode = null
        popState()
    }

    private void visitOutputBody(BlockStatement block) {
        block.variableScope = currentScope

        for( final stmt : block.statements ) {
            final call = asMethodCallX(stmt)
            if( !call )
                continue

            // treat as regular directive
            final name = call.getMethodAsString()
            if( findClassMember(currentScope.getClassScope(), name, call.getMethod()) ) {
                visit(call)
                continue
            }

            // treat as target definition
            final code = asDslBlock(call, 1)
            if( code != null ) {
                pushState(OutputDsl.TargetDsl)
                checkDirectives(code, 'output target directive', true)
                visitTargetBody(code)
                popState()
                continue
            }

            addError("Invalid output directive `${name}`", stmt)
        }
    }

    private void visitTargetBody(BlockStatement block) {
        block.variableScope = currentScope

        for( final stmt : block.statements ) {
            final call = asMethodCallX(stmt)
            if( !call )
                continue

            // treat as index definition
            final name = call.getMethodAsString()
            if( name == 'index' ) {
                final code = asDslBlock(call, 1)
                if( code != null ) {
                    pushState(OutputDsl.IndexDsl)
                    code.variableScope = null
                    checkDirectives(code, 'output index directive', true)
                    visit(code)
                    popState()
                    continue
                }
            }

            // treat as regular directive
            visit(call)
        }
    }

    @Override
    void visitBlockStatement(BlockStatement node) {
        final newScope = node.variableScope != null
        if( newScope ) pushState()
        node.variableScope = currentScope
        super.visitBlockStatement(node)
        if( newScope ) popState()
    }

    @Override
    void visitCatchStatement(CatchStatement node) {
        pushState()
        declare(node.variable, node)
        super.visitCatchStatement(node)
        popState()
    }

    @Override
    void visitClosureExpression(ClosureExpression node) {
        pushState()
        node.variableScope = currentScope
        if( node.parameters != null ) {
            for( final parameter : node.parameters ) {
                declare(parameter, parameter)
                if( parameter.hasInitialExpression() )
                    visit(parameter.initialExpression)
            }
        }
        super.visitClosureExpression(node)
        for( final variable : currentScope.getReferencedLocalVariablesIterator() ) {
            variable.setClosureSharedVariable(true)
        }
        popState()
    }

    @Override
    void visitBinaryExpression(BinaryExpression node) {
        if( node.getOperation().isA(Types.ASSIGNMENT_OPERATOR) ) {
            visit(node.rightExpression)
            declareAssignedVariable(node.leftExpression)
            visit(node.leftExpression)
        }
        else
            super.visitBinaryExpression(node)
    }

    /**
     * In processes and workflows, variables can be declared without `def`
     * and are treated as variables scoped to the process or workflow.
     *
     * @param node
     */
    void declareAssignedVariable(Expression node) {
        if( node instanceof TupleExpression ) {
            for( final el : node.expressions ) {
                if( el instanceof VariableExpression )
                    declareAssignedVariable(el)
            }
            return
        }

        final varX = getAssignmentTarget(node)
        if( varX == null )
            return
        final name = varX.name
        if( findVariableDeclaration(name, varX) )
            return
        if( currentTopLevelNode instanceof ProcessNode || currentTopLevelNode instanceof WorkflowNode ) {
            final scope = currentScope
            currentScope = currentTopLevelNode.variableScope
            declare(varX)
            currentScope = scope
        }
        else {
            addError("`${name}` was assigned but not declared", varX)
        }
    }

    private VariableExpression getAssignmentTarget(Expression node) {
        // e.g. p = 123
        if( node instanceof VariableExpression )
            return node
        // e.g. obj.p = 123
        if( node instanceof PropertyExpression )
            return getAssignmentTarget(node.objectExpression)
        // e.g. list[1] = 123 OR map['a'] = 123
        if( node instanceof BinaryExpression && node.operation.type == Types.LEFT_SQUARE_BRACKET )
            return getAssignmentTarget(node.leftExpression)
        return null
    }

    @Override
    void visitDeclarationExpression(DeclarationExpression node) {
        visit(node.rightExpression)

        if( node.isMultipleAssignmentDeclaration() ) {
            for( final el : node.tupleExpression )
                declare((VariableExpression) el)
        }
        else {
            declare(node.variableExpression)
        }
    }

    @Override
    void visitMethodCallExpression(MethodCallExpression node) {
        if( currentTopLevelNode instanceof WorkflowNode ) {
            visitAssignmentOperator(node)
        }
        if( node.isImplicitThis() && node.method instanceof ConstantExpression ) {
            final method = node.method
            final name = method.text
            final variable = findVariableDeclaration(name, node)
            if( !variable ) {
                if( name == 'for' || name == 'while' )
                    addError("`${name}` loops are no longer supported", node)
                else if( name == 'switch' )
                    addError("switch statements are no longer supported", node)
                else
                    addError("`${name}` is not defined", method)
            }
        }
        if( !node.isImplicitThis() )
            visit(node.getObjectExpression())
        visit(node.getMethod())
        visit(node.getArguments())
    }

    /**
     * Treat `set` operator as an assignment.
     */
    void visitAssignmentOperator(MethodCallExpression node) {
        final name = node.getMethodAsString()
        if( !(name == 'set' || name == 'tap') )
            return
        final code = asDslBlock(node, 1)
        if( !code || code.statements.size() != 1 )
            return
        final varX = asVarX(code.statements.first())
        if( !varX )
            return
        currentScope.putDeclaredVariable(varX)
    }

    @Override
    void visitPropertyExpression(PropertyExpression node) {
        super.visitPropertyExpression(node)

        // validate parameter against schema if applicable
        // NOTE: should be incorporated into type-checking visitor
        if( !paramsType )
            return
        if( node.objectExpression !instanceof VariableExpression )
            return
        final varX = (VariableExpression) node.objectExpression
        if( varX.name != 'params' )
            return
        final property = node.getPropertyAsString()
        if( !findClassMember(paramsType, property, node) ) {
            addError("Unrecognized parameter `${property}`", node)
            return
        }
        final variable = varX.getAccessedVariable()
        if( variable instanceof FieldNode )
            variable.setType(paramsType)
    }

    @Override
    void visitVariableExpression(VariableExpression node) {
        final name = node.name
        Variable variable = findVariableDeclaration(name, node)
        if( !variable ) {
            if( name == 'it' ) {
                addWarning('Implicit variable `it` in closure will not be supported in a future version', node)
            }
            else if( name == 'args' ) {
                addWarning('The use of `args` outside the entry workflow will not be supported in a future version', node)
            }
            else if( name == 'params' ) {
                addWarning('The use of `params` outside the entry workflow will not be supported in a future version', node)
            }
            else {
                variable = new DynamicVariable(name, false)
            }
        }
        if( variable )
            node.setAccessedVariable(variable)
    }

    private BlockStatement asDslBlock(MethodCallExpression call, int nArgs) {
        final args = (ArgumentListExpression) call.arguments
        if( args.size() != nArgs )
            return null
        if( args.last() !instanceof ClosureExpression )
            return null
        final closure = (ClosureExpression) args.last()
        return (BlockStatement) closure.code
    }

    private MethodCallExpression asMethodCallX(Statement stmt) {
        if( stmt !instanceof ExpressionStatement )
            return null
        final stmtX = (ExpressionStatement) stmt
        if( stmtX.expression !instanceof MethodCallExpression )
            return null
        return (MethodCallExpression) stmtX.expression
    }

    private VariableExpression asVarX(Statement stmt) {
        if( stmt !instanceof ExpressionStatement )
            return null
        final stmtX = (ExpressionStatement) stmt
        if( stmtX.expression !instanceof VariableExpression )
            return null
        return (VariableExpression) stmtX.expression
    }

    @Override
    void addError(String message, ASTNode node) {
        final cause = new SyntaxException(message, node)
        final errorMessage = new SyntaxErrorMessage(cause, sourceUnit)
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage)
    }

    void addWarning(String message, ASTNode node) {
        final cause = new SyntaxWarning(message, node)
        final errorMessage = new SyntaxErrorMessage(cause, sourceUnit)
        sourceUnit.getErrorCollector().addErrorAndContinue(errorMessage)
    }

}

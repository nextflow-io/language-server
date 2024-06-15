package nextflow.lsp.services.script

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
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
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
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
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

    private void declare(String name) {
        declare(new VariableExpression(name))
    }

    private void declare(MethodNode methodNode) {
        final classNode = currentScope.getClassScope()
        classNode.addMethod(methodNode)
    }

    private void declare(VariableExpression variable) {
        declare(variable, variable)
        variable.setAccessedVariable(variable)
    }

    private void declare(Variable variable, ASTNode context) {
        VariableScope scope = currentScope
        while( scope != null ) {
            if( variable.name in scope.getDeclaredVariables() ) {
                addError("The variable `${variable.name}` is already declared", context)
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
     */
    private Variable findVariableDeclaration(String name) {
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
            variable = findClassMember(scope.getClassScope(), name)
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

    private Variable findClassMember(ClassNode cn, String name) {
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            for( final fn : cn.getFields() ) {
                if( fn.getName() != name )
                    continue
                final annot = findAnnotation(fn, Constant)
                if( !annot )
                    continue
                final description = annot.getMember('value').getText().stripIndent(true).trim()
                final content = wrapDescriptionAsGroovydoc(description)
                fn.putNodeMetaData(GroovydocHolder.DOC_COMMENT, new Groovydoc(content, fn))
                return fn
            }
            for( final mn : cn.getMethods() ) {
                if( mn.getName() != name )
                    continue
                final annot = findAnnotation(mn, Function)
                if( annot ) {
                    final documentation = annot.getMember('value').getText().stripIndent(true).trim()
                    final mnWithDocs = new FunctionNode(name, documentation)
                    if( findAnnotation(mn, Operator) )
                        mnWithDocs.putNodeMetaData('type.label', 'operator')
                    if( cn.getTypeClass() == ProcessDirectiveDsl )
                        mnWithDocs.putNodeMetaData('type.label', 'process directive')
                    if( cn.getTypeClass() == ProcessInputDsl )
                        mnWithDocs.putNodeMetaData('type.label', 'process input')
                    if( cn.getTypeClass() == ProcessOutputDsl )
                        mnWithDocs.putNodeMetaData('type.label', 'process output')
                    if( cn.getTypeClass() == OutputDsl )
                        mnWithDocs.putNodeMetaData('type.label', 'output directive')
                    return wrapMethodAsVariable(mnWithDocs, cn)
                }
                if( mn instanceof FunctionNode || mn instanceof ProcessNode || mn instanceof WorkflowNode )
                    return wrapMethodAsVariable(mn, cn)
            }
            cn = cn.getSuperClass()
        }

        return null
    }

    private AnnotationNode findAnnotation(AnnotatedNode node, Class annotation) {
        return node.getAnnotations().find(an -> an.getClassNode().getName() == annotation.name)
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
            if( workflowNode != scriptNode.getEntry() )
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
        final clazz = FeatureFlagDsl.class
        for( final field : clazz.getDeclaredFields() ) {
            final annot = field.getAnnotation(FeatureFlag)
            if( annot && annot.name() == node.name ) {
                node.resolved = true
                return
            }
        }
        addError("Unrecognized feature flag '${node.name}'", node)
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
        super.visitMethod(node)

        currentTopLevelNode = null
        popState()
    }

    @Override
    void visitInclude(IncludeNode node) {
        for( final module : node.modules ) {
            final name = module.alias ?: module.name
            declare(name)
        }
    }

    @Override
    void visitProcess(ProcessNode node) {
        pushState(ProcessDsl)
        currentTopLevelNode = node
        node.variableScope = currentScope
        new DeclareInputsVisitor().visit(node.inputs)

        pushState(ProcessDirectiveDsl)
        visit(node.directives)
        popState()

        pushState(ProcessInputDsl)
        visit(node.inputs)
        popState()

        visit(node.when)
        visit(node.exec)
        visit(node.stub)

        pushState(ProcessOutputDsl)
        if( node.exec instanceof BlockStatement )
            copyVariableScope(node.exec.variableScope)
        visit(node.outputs)
        popState()

        currentTopLevelNode = null
        popState()
    }

    private class DeclareInputsVisitor extends ClassCodeVisitorSupport {

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        @Override
        void visitVariableExpression(VariableExpression node) {
            if( node.name == 'this' )
                return
            declare(node)
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
        pushState(node.name ? WorkflowDsl : EntryWorkflowDsl)
        currentTopLevelNode = node
        node.variableScope = currentScope
        new DeclareInputsVisitor().visit(node.takes)

        visit(node.main)
        if( node.main instanceof BlockStatement )
            copyVariableScope(node.main.variableScope)

        visit(node.emits)
        visit(node.publishers)

        currentTopLevelNode = null
        popState()
    }

    @Override
    void visitOutput(OutputNode node) {
        pushState(OutputDsl)
        currentTopLevelNode = node

        visit(node.body)

        currentTopLevelNode = null
        popState()
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
                declare(parameter, node)
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
        if( Types.isAssignment(node.operation.type) ) {
            visit(node.rightExpression)
            visitAssignment(node.leftExpression)
        }
        else
            super.visitBinaryExpression(node)
    }

    /**
     * Treat a variable assignment as a declaration if it has not
     * been declared yet.
     */
    void visitAssignment(Expression left) {
        if( left instanceof TupleExpression ) {
            for( final el : left.expressions ) {
                if( el instanceof VariableExpression )
                    visitAssignment(el)
            }
        }

        if( left instanceof VariableExpression ) {
            VariableScope scope = currentScope
            while( scope != null ) {
                if( left.name in scope.getDeclaredVariables() )
                    return
                scope = scope.parent
            }
            declare(left)
        }
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
            final variable = findVariableDeclaration(name)
            if( !variable ) {
                if( name in ['for', 'while'] )
                    addError("`${name}` loops are no longer supported", node)
                else
                    addError("`${name}` is not defined", node)
            }
        }
        super.visitMethodCallExpression(node)
    }

    /**
     * Treat `set` operator as an assignment.
     */
    void visitAssignmentOperator(MethodCallExpression node) {
        if( node.methodAsString !in ['set', 'tap'] )
            return
        final args = (ArgumentListExpression)node.arguments
        if( args.size() != 1 || args.first() !instanceof ClosureExpression )
            return
        final closure = (ClosureExpression)args.first()
        final code = (BlockStatement)closure.code
        if( code.statements.size() != 1 )
            return
        final stmt = code.statements.first()
        if( stmt !instanceof ExpressionStatement )
            return
        final expr = ((ExpressionStatement)stmt).expression
        if( expr !instanceof VariableExpression )
            return
        currentScope.putDeclaredVariable((VariableExpression) expr)
    }

    @Override
    void visitVariableExpression(VariableExpression node) {
        final name = node.name
        if( name == 'this' )
            return
        final variable = findVariableDeclaration(name)
        if( !variable ) {
            if( name == 'it' ) {
                addWarning('Implicit variable `it` in closure will be deprecated in a future version', node)
            }
            else if( name == 'args' ) {
                addWarning('The use of `args` outside the entry workflow will be deprecated in a future version', node)
            }
            else if( name == 'params' ) {
                addWarning('The use of `params` outside the entry workflow will be deprecated in a future version', node)
            }
            else {
                addError("`${name}` is not defined", node)
            }
            return
        }
        node.setAccessedVariable(variable)
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

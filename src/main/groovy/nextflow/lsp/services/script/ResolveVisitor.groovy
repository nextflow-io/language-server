package nextflow.lsp.services.script

import java.lang.reflect.Modifier

import groovy.lang.GroovyClassLoader
import groovy.lang.Tuple2
import groovy.transform.CompileStatic
import nextflow.lsp.compiler.SyntaxWarning
import nextflow.script.dsl.ScriptDsl
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.control.ClassNodeResolver
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.runtime.memoize.UnlimitedConcurrentCache
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.vmplugin.VMPluginFactory

import static groovy.lang.Tuple.tuple
import static org.codehaus.groovy.ast.tools.ClosureUtils.getParametersSafe

@CompileStatic
class ResolveVisitor extends ClassCodeExpressionTransformer implements ScriptVisitor {

    static final String[] DEFAULT_IMPORTS = [ 'java.lang.', 'java.util.', 'java.io.', 'java.net.', 'groovy.lang.', 'groovy.util.' ]
    static final String[] EMPTY_STRING_ARRAY = new String[0]

    private SourceUnit sourceUnit

    private CompilationUnit compilationUnit

    private ClassNodeResolver classNodeResolver = new ClassNodeResolver()

    ResolveVisitor(SourceUnit sourceUnit, CompilerConfiguration config, GroovyClassLoader classLoader) {
        this.sourceUnit = sourceUnit
        this.compilationUnit = new CompilationUnit(config, null, classLoader)
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode !instanceof ScriptNode )
            return
        final scriptNode = (ScriptNode) moduleNode

        // initialize variable scopes
        new VariableScopeVisitor(sourceUnit).visit()

        // resolve type names
        for( final functionNode : scriptNode.getFunctions() )
            visitFunction(functionNode)
        for( final processNode : scriptNode.getProcesses() )
            visitProcess(processNode)
        for( final workflowNode : scriptNode.getWorkflows() )
            visitWorkflow(workflowNode)
        if( scriptNode.getOutput() )
            visitOutput(scriptNode.getOutput())

        // report errors for any unresolved variable references
        new DynamicVariablesVisitor().visit(scriptNode)
    }

    @Override
    void visitFunction(FunctionNode node) {
        for( final param : node.getParameters() ) {
            param.setInitialExpression(transform(param.getInitialExpression()))
            resolveOrFail(param.getType(), param.getType())
        }
        resolveOrFail(node.getReturnType(), node)
        visit(node.code)
    }

    @Override
    void visitCatchStatement(CatchStatement cs) {
        resolveOrFail(cs.getExceptionType(), cs)
        if( ClassHelper.isDynamicTyped(cs.getExceptionType()) )
            cs.getVariable().setType(ClassHelper.make(Exception.class))
        super.visitCatchStatement(cs)
    }

    protected void resolveOrFail(ClassNode type, ASTNode node) {
        if( !resolve(type) )
            addError("`${type.toString(false)}` is not defined", node)
    }

    protected boolean resolve(ClassNode type) {
        final genericsTypes = type.getGenericsTypes()
        resolveGenericsTypes(genericsTypes)

        if( type.isPrimaryClassNode() )
            return true
        if( type.isResolved() )
            return true
        if( !type.hasPackageName() && resolveFromDefaultImports(type) )
            return true
        return resolveFromClassResolver(type.getName()) != null
    }

    private boolean resolveGenericsTypes(GenericsType[] types) {
        if( types == null )
            return true
        boolean resolved = true
        for( final type : types ) {
            if( !resolveGenericsType(type) )
                resolved = false
        }
        return resolved
    }

    private boolean resolveGenericsType(GenericsType genericsType) {
        if( genericsType.isResolved() )
            return true
        final type = genericsType.getType()
        resolveOrFail(type, genericsType)
        if( resolveGenericsTypes(type.getGenericsTypes()) )
            genericsType.setResolved(genericsType.getType().isResolved())
        return genericsType.isResolved()
    }

    protected boolean resolveFromDefaultImports(ClassNode type) {
        // resolve from script imports
        final typeName = type.getName()
        for( final cn : ScriptDsl.TYPES ) {
            if( typeName == cn.getNameWithoutPackage() || typeName == cn.getName() ) {
                type.setRedirect(cn)
                return true
            }
        }
        // resolve from default imports cache
        final packagePrefixSet = DEFAULT_IMPORT_CLASS_AND_PACKAGES_CACHE.get(typeName)
        if( packagePrefixSet != null ) {
            if( resolveFromDefaultImports(type, packagePrefixSet.toArray(EMPTY_STRING_ARRAY)) )
                return true
        }
        // resolve from default imports
        if( resolveFromDefaultImports(type, DEFAULT_IMPORTS) ) {
            return true
        }
        if( typeName == 'BigInteger' ) {
            type.setRedirect(ClassHelper.BigInteger_TYPE)
            return true
        }
        if( typeName == 'BigDecimal' ) {
            type.setRedirect(ClassHelper.BigDecimal_TYPE)
            return true
        }
        return false
    }

    private static final Map<String, Set<String>> DEFAULT_IMPORT_CLASS_AND_PACKAGES_CACHE = new UnlimitedConcurrentCache<>()
    static {
        DEFAULT_IMPORT_CLASS_AND_PACKAGES_CACHE.putAll(VMPluginFactory.getPlugin().getDefaultImportClasses(DEFAULT_IMPORTS))
    }

    protected boolean resolveFromDefaultImports(ClassNode type, String[] packagePrefixes) {
        final typeName = type.getName()
        for( final packagePrefix : packagePrefixes ) {
            final redirect = resolveFromClassResolver(packagePrefix + typeName)
            if( redirect ) {
                type.setRedirect(redirect)
                // don't update cache when using a cached lookup
                if( packagePrefixes == DEFAULT_IMPORTS ) {
                    final packagePrefixSet = DEFAULT_IMPORT_CLASS_AND_PACKAGES_CACHE.computeIfAbsent(typeName, key -> new HashSet<>(2))
                    packagePrefixSet.add(packagePrefix)
                }
                return true
            }
        }
        return false
    }

    protected ClassNode resolveFromClassResolver(String name) {
        if( !name.contains('.') )
            return null
        final lookupResult = classNodeResolver.resolveName(name, compilationUnit)
        if( !lookupResult )
            return null
        if( !lookupResult.isClassNode() )
            throw new GroovyBugError('class resolver lookup result is not a class node')
        return lookupResult.getClassNode()
    }

    @Override
    Expression transform(Expression exp) {
        if( !exp )
            return null
        Expression result
        if( exp instanceof VariableExpression ) {
            result = transformVariableExpression((VariableExpression) exp)
        }
        else if( exp instanceof PropertyExpression ) {
            result = transformPropertyExpression((PropertyExpression) exp)
        }
        else if( exp instanceof DeclarationExpression ) {
            result = transformDeclarationExpression((DeclarationExpression) exp)
        }
        else if( exp instanceof BinaryExpression ) {
            result = transformBinaryExpression((BinaryExpression) exp)
        }
        else if( exp instanceof MethodCallExpression ) {
            result = transformMethodCallExpression((MethodCallExpression) exp)
        }
        else if( exp instanceof ClosureExpression ) {
            result = transformClosureExpression((ClosureExpression) exp)
        }
        else if( exp instanceof ConstructorCallExpression ) {
            result = transformConstructorCallExpression((ConstructorCallExpression) exp)
        }
        else {
            resolveOrFail(exp.getType(), exp)
            result = exp.transformExpression(this)
        }
        if( result != null && result != exp ) {
            result.setSourcePosition(exp)
        }
        return result
    }

    protected Expression transformVariableExpression(VariableExpression ve) {
        final v = ve.getAccessedVariable()
        if( v instanceof DynamicVariable ) {
            // attempt to resolve variable as type name
            final name = ve.getName()
            final type = ClassHelper.make(name)
            if( !type.isResolved() && !inPropertyExpression )
                resolveOrFail(type, ve)
            if( type.isResolved() )
                return new ClassExpression(type)
        }
        if( inVariableDeclaration ) {
            // resolve type of variable declaration
            resolveOrFail(ve.getType(), ve)
            final origin = ve.getOriginType()
            if( origin != ve.getType() )
                resolveOrFail(origin, ve)
        }
        // if the variable is still dynamic (i.e. unresolved), it will be handled by DynamicVariablesVisitor
        return ve
    }

    private boolean inPropertyExpression

    protected Expression transformPropertyExpression(PropertyExpression pe) {
        final ipe = inPropertyExpression
        Expression objectExpression
        Expression property
        try {
            inPropertyExpression = true
            objectExpression = transform(pe.getObjectExpression())
            inPropertyExpression = false
            property = transform(pe.getProperty())
        }
        finally {
            inPropertyExpression = ipe
        }
        final result = new PropertyExpression(objectExpression, property, pe.isSafe())
        // attempt to resolve property expression as a fully-qualified class name
        final className = lookupClassName(result)
        if( className != null ) {
            final type = ClassHelper.make(className)
            if( resolve(type) )
                return new ClassExpression(type)
        }
        return result
    }

    private static String lookupClassName(PropertyExpression pe) {
        boolean doInitialClassTest = true
        StringBuilder name = new StringBuilder(32)
        Expression expr = pe
        while( expr != null && name != null ) {
            if( expr instanceof VariableExpression ) {
                if( expr.isThisExpression() )
                    return null
                final varName = expr.getName()
                final classNameInfo = makeClassName(doInitialClassTest, name, varName)
                name = classNameInfo.getV1()
                doInitialClassTest = classNameInfo.getV2()
                break
            }

            if( expr !instanceof PropertyExpression )
                return null
            final property = ((PropertyExpression) expr).getPropertyAsString()
            if( !property )
                return null
            final classNameInfo = makeClassName(doInitialClassTest, name, property)
            name = classNameInfo.getV1()
            doInitialClassTest = classNameInfo.getV2()
            expr = ((PropertyExpression) expr).getObjectExpression()
        }
        if( name == null || name.length() == 0 )
            return null
        return name.toString()
    }

    private static Tuple2<StringBuilder, Boolean> makeClassName(boolean doInitialClassTest, StringBuilder name, String varName) {
        if( doInitialClassTest ) {
            return isValidClassName(varName)
                ? tuple(new StringBuilder(varName), Boolean.FALSE)
                : tuple(null, Boolean.TRUE)
        }
        name.insert(0, varName + ".")
        return tuple(name, Boolean.FALSE)
    }

    private static boolean isValidClassName(String name) {
        if( name == null || name.length() == 0 )
            return false
        return !Character.isLowerCase(name.charAt(0))
    }

    private boolean inVariableDeclaration

    protected Expression transformDeclarationExpression(DeclarationExpression de) {
        inVariableDeclaration = true
        final left = transform(de.getLeftExpression())
        inVariableDeclaration = false
        if( left instanceof ClassExpression ) {
            addError("`${left.getType().getName()}` is already defined as a type", de.getLeftExpression())
            return de
        }
        final right = transform(de.getRightExpression())
        if( right == de.getRightExpression() )
            return de
        final result = new DeclarationExpression(left, de.getOperation(), right)
        result.setDeclaringClass(de.getDeclaringClass())
        return result
    }

    protected Expression transformBinaryExpression(BinaryExpression be) {
        final left = transform(be.getLeftExpression())
        if( be.getOperation().isA(Types.ASSIGNMENT_OPERATOR) && left instanceof ClassExpression ) {
            addError("`${left.getType().getName()}` is already defined as a type", be.getLeftExpression())
            return be
        }
        be.setLeftExpression(left)
        be.setRightExpression(transform(be.getRightExpression()))
        return be
    }

    protected Expression transformMethodCallExpression(MethodCallExpression mce) {
        final args = transform(mce.getArguments())
        final method = transform(mce.getMethod())
        final object = transform(mce.getObjectExpression())
        final result = new MethodCallExpression(object, method, args)
        result.setMethodTarget(mce.getMethodTarget())
        result.setImplicitThis(mce.isImplicitThis())
        result.setSafe(mce.isSafe())
        return result
    }

    protected Expression transformClosureExpression(ClosureExpression ce) {
        for( final param : getParametersSafe(ce) ) {
            resolveOrFail(param.getType(), ce)
            if( param.hasInitialExpression() )
                param.setInitialExpression(transform(param.getInitialExpression()))
        }
        visit(ce.getCode())
        return ce
    }

    protected Expression transformConstructorCallExpression(ConstructorCallExpression cce) {
        final cceType = cce.getType()
        resolveOrFail(cceType, cce)
        if( cceType.isAbstract() )
            addError("`${cceType.getName()}` is an abstract type and cannot be constructed directly", cce)
        return cce.transformExpression(this)
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

    private class DynamicVariablesVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        @Override
        void visitVariableExpression(VariableExpression node) {
            final variable = node.getAccessedVariable()
            if( variable instanceof DynamicVariable )
                addError("`${node.getName()}` is not defined", node)
        }
    }

}

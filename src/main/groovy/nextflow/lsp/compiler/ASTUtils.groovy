package nextflow.lsp.compiler

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCall
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement

/**
 * Utility methods for querying an AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ASTUtils {

    /**
     * Get the nearest ancestor of an "offset" node that is
     * of the given type.
     *
     * @param offsetNode
     * @param nodeType
     * @param astCache
     */
    static ASTNode getEnclosingNodeOfType(ASTNode offsetNode, Class<? extends ASTNode> nodeType, ASTNodeCache astCache) {
        ASTNode current = offsetNode
        while( current != null ) {
            if( nodeType.isInstance(current) )
                return current
            current = astCache.getParent(current)
        }
        return null
    }

    /**
     * Get the ast node corresponding to the definition of a given
     * class, method, or variable.
     *
     * @param node
     * @param strict
     * @param astCache
     */
    static ASTNode getDefinition(ASTNode node, boolean strict, ASTNodeCache astCache) {
        if( node == null )
            return null

        final parentNode = astCache.getParent(node)

        if( node instanceof ExpressionStatement )
            node = node.getExpression()

        if( node instanceof ClassNode ) {
            return tryToResolveOriginalClassNode(node, strict, astCache)
        }
        else if( node instanceof ConstructorCallExpression ) {
            return ASTUtils.getMethodFromCallExpression(node, astCache)
        }
        else if( node instanceof DeclarationExpression && !node.isMultipleAssignmentDeclaration() ) {
            final originType = node.getVariableExpression().getOriginType()
            return tryToResolveOriginalClassNode(originType, strict, astCache)
        }
        else if( node instanceof ClassExpression ) {
            return tryToResolveOriginalClassNode(node.getType(), strict, astCache)
        }
        else if( node instanceof MethodNode ) {
            return node
        }
        else if( node instanceof ConstantExpression && parentNode != null ) {
            if( parentNode instanceof MethodCallExpression ) {
                return ASTUtils.getMethodFromCallExpression(parentNode, astCache)
            }
            else if( parentNode instanceof PropertyExpression ) {
                final propertyNode = ASTUtils.getPropertyFromExpression(parentNode, astCache)
                return propertyNode != null
                    ? propertyNode
                    : ASTUtils.getFieldFromExpression(parentNode, astCache)
            }
        }
        else if( node instanceof VariableExpression ) {
            // DynamicVariable is not an ASTNode
            final accessedVariable = node.getAccessedVariable()
            return accessedVariable instanceof ASTNode
                ? accessedVariable
                : null
        }
        else if( node instanceof Variable ) {
            return node
        }
        return null
    }

    /**
     * Get the ast node corresponding to the definition of the type of
     * a given method or variable.
     *
     * @param node
     * @param astCache
     */
    static ASTNode getTypeDefinition(ASTNode node, ASTNodeCache astCache) {
        final defNode = getDefinition(node, false, astCache)
        if( defNode == null )
            return null

        if( defNode instanceof MethodNode ) {
            return tryToResolveOriginalClassNode(defNode.getReturnType(), true, astCache)
        }
        else if( defNode instanceof Variable ) {
            return tryToResolveOriginalClassNode(defNode.getOriginType(), true, astCache)
        }
        return null
    }

    /**
     * Get the ast nodes corresponding to references of a definition node.
     *
     * @param node
     * @param ast
     */
    static List<ASTNode> getReferences(ASTNode node, ASTNodeCache ast) {
        final defNode = getDefinition(node, true, ast)
        if( defNode == null )
            return Collections.emptyList()

        return ast.getNodes().findAll { otherNode ->
            return otherNode.getLineNumber() != -1 && otherNode.getColumnNumber() != -1 && defNode == getDefinition(otherNode, false, ast)
        }
    }

    private static ClassNode tryToResolveOriginalClassNode(ClassNode node, boolean strict, ASTNodeCache ast) {
        for( final originalNode : ast.getClassNodes() ) {
            if( originalNode == node )
                return originalNode
        }
        return strict ? null : node
    }

    /**
     * Get the property definition for a property expression.
     *
     * @param node
     * @param astCache
     */
    static PropertyNode getPropertyFromExpression(PropertyExpression node, ASTNodeCache astCache) {
        final classNode = getTypeOfNode(node.getObjectExpression(), astCache)
        if( classNode == null )
            return Collections.emptyList()
        return classNode.getProperty(node.getProperty().getText())
    }

    /**
     * Get the field definition for a property expression.
     *
     * @param node
     * @param astCache
     */
    static FieldNode getFieldFromExpression(PropertyExpression node, ASTNodeCache astCache) {
        final classNode = getTypeOfNode(node.getObjectExpression(), astCache)
        if( classNode == null )
            return Collections.emptyList()
        return classNode.getField(node.getProperty().getText())
    }

    /**
     * Get the set of available fields for the left-hand side of a
     * property expression (i.e. by inspecting the type of the left-hand side).
     *
     * @param node
     * @param astCache
     */
    static List<FieldNode> getFieldsForLeftSideOfPropertyExpression(Expression node, ASTNodeCache astCache) {
        final classNode = getTypeOfNode(node, astCache)
        if( classNode == null )
            return Collections.emptyList()
        final isStatic = node instanceof ClassExpression
        return classNode.getFields().findAll { fieldNode -> isStatic ? fieldNode.isStatic() : !fieldNode.isStatic() }
    }

    /**
     * Get the set of available properties for the left-hand side of a
     * property expression (i.e. by inspecting the type of the left-hand side).
     *
     * @param node
     * @param astCache
     */
    static List<PropertyNode> getPropertiesForLeftSideOfPropertyExpression(Expression node, ASTNodeCache astCache) {
        final classNode = getTypeOfNode(node, astCache)
        if( classNode == null )
            return Collections.emptyList()
        final isStatic = node instanceof ClassExpression
        return classNode.getProperties().findAll { propertyNode -> isStatic ? propertyNode.isStatic() : !propertyNode.isStatic() }
    }

    /**
     * Get the set of available methods for the left-hand side of a
     * property expression (i.e. by inspecting the type of the left-hand side).
     *
     * @param node
     * @param astCache
     */
    static List<MethodNode> getMethodsForLeftSideOfPropertyExpression(Expression node, ASTNodeCache astCache) {
        final classNode = getTypeOfNode(node, astCache)
        if( classNode == null )
            return Collections.emptyList()
        final isStatic = node instanceof ClassExpression
        return classNode.getMethods().findAll { methodNode -> isStatic ? methodNode.isStatic() : !methodNode.isStatic() }
    }

    /**
     * Get the type (i.e. class node) of an ast node.
     *
     * @param node
     * @param astCache
     */
    static ClassNode getTypeOfNode(ASTNode node, ASTNodeCache astCache) {
        if( node instanceof BinaryExpression ) {
            final leftExpr = node.getLeftExpression()
            if( node.getOperation().getText().equals("[") && leftExpr.getType().isArray() )
                return leftExpr.getType().getComponentType()
        }
        else if( node instanceof ClassExpression ) {
            // SomeClass.someProp -> SomeClass
            return node.getType()
        }
        else if( node instanceof ConstructorCallExpression ) {
            // new SomeClass() -> SomeClass
            return node.getType()
        }
        else if( node instanceof MethodCallExpression ) {
            final methodNode = ASTUtils.getMethodFromCallExpression(node, astCache)
            return methodNode != null
                ? methodNode.getReturnType()
                : node.getType()
        }
        else if( node instanceof PropertyExpression ) {
            final propertyNode = ASTUtils.getPropertyFromExpression(node, astCache)
            return propertyNode != null
                ? getTypeOfNode(propertyNode, astCache)
                : node.getType()
        }
        else if( node instanceof Variable ) {
            if( node.getName() == 'this' ) {
                final enclosingClass = (ClassNode) getEnclosingNodeOfType(node, ClassNode.class, astCache)
                if( enclosingClass != null )
                    return enclosingClass
            }
            else if( node.isDynamicTyped() ) {
                final defNode = ASTUtils.getDefinition(node, false, astCache)
                if( defNode instanceof Variable ) {
                    if( defNode.hasInitialExpression() ) {
                        return getTypeOfNode(defNode.getInitialExpression(), astCache)
                    }
                    else {
                        final declNode = astCache.getParent(defNode)
                        if( declNode instanceof DeclarationExpression )
                            return getTypeOfNode(declNode.getRightExpression(), astCache)
                    }
                }
            }

            if( node.getOriginType() != null )
                return node.getOriginType()
        }

        return node instanceof Expression
            ? node.getType()
            : null
    }

    /**
     * Get the list of available method overloads for a method call expression.
     *
     * @param node
     * @param astCache
     */
    static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, ASTNodeCache astCache) {
        if( node instanceof MethodCallExpression ) {
            final leftType = getTypeOfNode(node.getObjectExpression(), astCache)
            if( leftType != null )
                return leftType.getMethods(node.getMethod().getText())
        }
        else if( node instanceof ConstructorCallExpression ) {
            final constructorType = node.getType()
            if( constructorType != null )
                return constructorType.getDeclaredConstructors().collect { ctor -> (MethodNode) ctor }
        }
        return Collections.emptyList()
    }

    /**
     * Get the method node for a method call expression.
     *
     * @param node
     * @param astCache
     */
    static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache astCache) {
        return getMethodFromCallExpression(node, astCache, -1)
    }

    /**
     * Find the method node which most closely matches a method call expression.
     *
     * @param node
     * @param astCache
     * @param argIndex
     */
    static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache astCache, int argIndex) {
        final methods = getMethodOverloadsFromCallExpression(node, astCache)
        if( methods.isEmpty() || node.getArguments() !instanceof ArgumentListExpression )
            return null

        final callArguments = (ArgumentListExpression) node.getArguments()
        final result = methods.max { MethodNode m1, MethodNode m2 ->
            final score1 = getArgumentsScore(m1.getParameters(), callArguments, argIndex)
            final score2 = getArgumentsScore(m2.getParameters(), callArguments, argIndex)
            return score1 <=> score2
        }

        return result ?: null
    }

    private static int getArgumentsScore(Parameter[] parameters, ArgumentListExpression arguments, int argIndex) {
        final paramsCount = parameters.length
        final expressionsCount = arguments.getExpressions().size()
        final argsCount = argIndex >= expressionsCount
            ? argIndex + 1
            : expressionsCount
        final minCount = Math.min(paramsCount, argsCount)

        int score = 0
        if( minCount == 0 && paramsCount == argsCount )
            score++

        for( int i = 0; i < minCount; i++ ) {
            final paramType = (i < paramsCount) ? parameters[i].getType() : null
            final argType = (i < expressionsCount) ? arguments.getExpression(i).getType() : null
            if( argType != null && paramType != null ) {
                // equal types > subtypes > different types
                if( argType.equals(paramType) )
                    score += 1000
                else if( argType.isDerivedFrom(paramType) )
                    score += 100
                else
                    score++
            }
            else if( paramType != null ) {
                score++
            }
        }
        return score
    }

}

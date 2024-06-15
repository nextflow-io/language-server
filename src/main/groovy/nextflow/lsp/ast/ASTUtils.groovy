package nextflow.lsp.ast

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.VariableScope
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
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement

/**
 * Utility methods for querying an AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ASTUtils {

    /**
     * Get the ast node corresponding to the definition of a given
     * class, method, or variable.
     *
     * @param node
     * @param strict
     * @param ast
     */
    static ASTNode getDefinition(ASTNode node, boolean strict, ASTNodeCache ast) {
        if( node instanceof VariableExpression ) {
            final accessedVariable = node.getAccessedVariable()
            return accessedVariable instanceof ASTNode
                ? accessedVariable
                : null
        }

        if( node instanceof ConstantExpression ) {
            final parentNode = ast.getParent(node)
            if( parentNode instanceof MethodCallExpression ) {
                return getMethodFromCallExpression(parentNode, ast)
            }
            if( parentNode instanceof PropertyExpression ) {
                return getPropertyFromExpression(parentNode, ast)
                    ?: getFieldFromExpression(parentNode, ast)
            }
        }

        if( node instanceof ClassExpression )
            return getOriginalClassNode(node.type, strict, ast)

        if( node instanceof ConstructorCallExpression )
            return getMethodFromCallExpression(node, ast)

        if( node instanceof Variable )
            return node

        return null
    }

    /**
     * Get the ast node corresponding to the definition of the type of
     * a given method or variable.
     *
     * @param node
     * @param ast
     */
    static ASTNode getTypeDefinition(ASTNode node, ASTNodeCache ast) {
        final defNode = getDefinition(node, false, ast)
        if( defNode == null )
            return null

        if( defNode instanceof MethodNode ) {
            return getOriginalClassNode(defNode.getReturnType(), true, ast)
        }
        else if( defNode instanceof Variable ) {
            return getOriginalClassNode(defNode.getOriginType(), true, ast)
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

    private static ClassNode getOriginalClassNode(ClassNode node, boolean strict, ASTNodeCache ast) {
        // TODO: built-in types
        return strict ? null : node
    }

    private static PropertyNode getPropertyFromExpression(PropertyExpression node, ASTNodeCache ast) {
        final classNode = getTypeOfNode(node.getObjectExpression(), ast)
        if( classNode == null )
            return null
        return classNode.getProperty(node.getProperty().getText())
    }

    private static FieldNode getFieldFromExpression(PropertyExpression node, ASTNodeCache ast) {
        final classNode = getTypeOfNode(node.getObjectExpression(), ast)
        if( classNode == null )
            return null
        return classNode.getField(node.getProperty().getText())
    }

    /**
     * Get the set of available fields for the left-hand side of a
     * property expression (i.e. by inspecting the type of the left-hand side).
     *
     * @param node
     * @param ast
     */
    static List<FieldNode> getFieldsForObjectExpression(Expression node, ASTNodeCache ast) {
        final classNode = getTypeOfNode(node, ast)
        if( classNode == null )
            return Collections.emptyList()
        final isStatic = node instanceof ClassExpression
        return classNode.getFields().findAll { fieldNode -> isStatic ? fieldNode.isStatic() : !fieldNode.isStatic() }
    }

    /**
     * Get the set of available methods for the left-hand side of a
     * property expression (i.e. by inspecting the type of the left-hand side).
     *
     * @param node
     * @param ast
     */
    static List<MethodNode> getMethodsForObjectExpression(Expression node, ASTNodeCache ast) {
        final classNode = getTypeOfNode(node, ast)
        if( classNode == null )
            return Collections.emptyList()
        final isStatic = node instanceof ClassExpression
        return classNode.getMethods().findAll { methodNode -> isStatic ? methodNode.isStatic() : !methodNode.isStatic() }
    }

    /**
     * Get the type (i.e. class node) of an ast node.
     *
     * @param node
     * @param ast
     */
    static ClassNode getTypeOfNode(ASTNode node, ASTNodeCache ast) {
        if( node instanceof ClassExpression ) {
            // SomeClass.someProp -> SomeClass
            return node.getType()
        }

        if( node instanceof ConstructorCallExpression ) {
            // new SomeClass() -> SomeClass
            return node.getType()
        }

        if( node instanceof MethodCallExpression ) {
            final methodNode = getMethodFromCallExpression(node, ast)
            return methodNode != null
                ? methodNode.getReturnType()
                : node.getType()
        }

        if( node instanceof PropertyExpression ) {
            final propertyNode = getPropertyFromExpression(node, ast)
            return propertyNode != null
                ? getTypeOfNode(propertyNode, ast)
                : node.getType()
        }

        if( node instanceof Variable ) {
            if( node.isDynamicTyped() ) {
                final defNode = getDefinition(node, false, ast)
                if( defNode instanceof Variable ) {
                    if( defNode.hasInitialExpression() ) {
                        return getTypeOfNode(defNode.getInitialExpression(), ast)
                    }
                    else {
                        final declNode = ast.getParent(defNode)
                        if( declNode instanceof DeclarationExpression )
                            return getTypeOfNode(declNode.getRightExpression(), ast)
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
     * Find the method node which most closely matches a call expression.
     *
     * @param node
     * @param ast
     * @param argIndex
     */
    static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache ast, int argIndex=-1) {
        final methods = getMethodOverloadsFromCallExpression(node, ast)
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

    /**
     * Get the list of available method overloads for a method call expression.
     *
     * @param node
     * @param ast
     */
    static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, ASTNodeCache ast) {
        if( node instanceof MethodCallExpression ) {
            if( node.isImplicitThis() ) {
                final defNode = getDefinitionFromScope(node, node.getMethodAsString(), ast)
                if( defNode instanceof PropertyNode ) {
                    final methodNode = (MethodNode) defNode.getNodeMetaData('access.method')
                    return List.of(methodNode)
                }
            }
            final leftType = getTypeOfNode(node.getObjectExpression(), ast)
            if( leftType != null )
                return leftType.getMethods(node.getMethod().getText())
        }

        if( node instanceof ConstructorCallExpression ) {
            final constructorType = node.getType()
            if( constructorType != null )
                return constructorType.getDeclaredConstructors().collect { ctor -> (MethodNode) ctor }
        }

        return Collections.emptyList()
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

    private static ASTNode getDefinitionFromScope(ASTNode node, String name, ASTNodeCache ast) {
        Variable variable = null
        VariableScope scope = getVariableScope(node, ast)
        while( scope != null && variable == null ) {
            variable = scope.getDeclaredVariable(name)
            if( variable )
                break
            variable = scope.getReferencedLocalVariable(name)
            if( variable )
                break
            variable = scope.getReferencedClassVariable(name)
            if( variable )
                break
            scope = scope.parent
        }
        return variable instanceof ASTNode ? variable : null
    }

    /**
     * Get the nearest variable scope of a node.
     *
     * @param node
     * @param ast
     */
    static VariableScope getVariableScope(ASTNode node, ASTNodeCache ast) {
        ASTNode current = node
        while( current != null ) {
            if( current instanceof BlockStatement )
                return current.variableScope
            current = ast.getParent(current)
        }
        return null
    }

}

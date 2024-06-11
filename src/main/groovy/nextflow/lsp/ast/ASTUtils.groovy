package nextflow.lsp.ast

import groovy.transform.CompileStatic
import nextflow.lsp.services.script.ScriptAstCache
import nextflow.script.dsl.Function
import nextflow.script.dsl.Operator
import nextflow.script.dsl.ScriptDsl
import nextflow.script.dsl.WorkflowDsl
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.OperatorNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
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
@CompileStatic
class ASTUtils {

    /**
     * Get the nearest ancestor of an "offset" node that is
     * of the given type.
     *
     * @param offsetNode
     * @param nodeType
     * @param ast
     */
    static ASTNode getEnclosingNodeOfType(ASTNode offsetNode, Class<? extends ASTNode> nodeType, ASTNodeCache ast) {
        ASTNode current = offsetNode
        while( current != null ) {
            if( nodeType.isInstance(current) )
                return current
            current = ast.getParent(current)
        }
        return null
    }

    /**
     * Get the ast node corresponding to the definition of a given
     * class, method, or variable.
     *
     * @param node
     * @param strict
     * @param ast
     */
    static ASTNode getDefinition(ASTNode node, boolean strict, ASTNodeCache ast) {
        if( node == null )
            return null
        if( node instanceof FunctionNode || node instanceof ProcessNode || node instanceof WorkflowNode )
            return null

        final parentNode = ast.getParent(node)

        if( node instanceof ClassExpression ) {
            return getOriginalClassNode(node.type, strict, ast)
        }
        else if( node instanceof ConstructorCallExpression ) {
            return getMethodFromCallExpression(node, ast)
        }
        else if( node instanceof ConstantExpression && parentNode != null ) {
            if( parentNode instanceof MethodCallExpression ) {
                final object = parentNode.objectExpression
                return object instanceof VariableExpression && object.name == 'this' && ast instanceof ScriptAstCache
                    ? getComponentFromCallExpression(parentNode, ast)
                    : getMethodFromCallExpression(parentNode, ast)
            }
            else if( parentNode instanceof PropertyExpression ) {
                return getPropertyFromExpression(parentNode, ast)
                    ?: getFieldFromExpression(parentNode, ast)
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
     * Get the set of available properties for the left-hand side of a
     * property expression (i.e. by inspecting the type of the left-hand side).
     *
     * @param node
     * @param ast
     */
    static List<PropertyNode> getPropertiesForObjectExpression(Expression node, ASTNodeCache ast) {
        final classNode = getTypeOfNode(node, ast)
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
        else if( node instanceof ConstructorCallExpression ) {
            // new SomeClass() -> SomeClass
            return node.getType()
        }
        else if( node instanceof MethodCallExpression ) {
            final methodNode = getMethodFromCallExpression(node, ast)
            return methodNode != null
                ? methodNode.getReturnType()
                : node.getType()
        }
        else if( node instanceof PropertyExpression ) {
            final propertyNode = getPropertyFromExpression(node, ast)
            return propertyNode != null
                ? getTypeOfNode(propertyNode, ast)
                : node.getType()
        }
        else if( node instanceof Variable ) {
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

    private static ASTNode getComponentFromCallExpression(MethodCallExpression call, ScriptAstCache ast) {
        final name = call.methodAsString
        final uri = ast.getURI(call)

        // TODO: add function imports
        // TODO: handle function overloads
        for( final method : ScriptDsl.getDeclaredMethods() ) {
            final annot = method.getAnnotation(Function)
            if( !annot )
                continue
            if( name != method.getName() )
                continue
            final documentation = annot.value()
            return new FunctionNode(name, documentation.stripIndent(true).trim())
        }

        final functionNode = ast.getFunctionNodes(uri).find { node -> node.name == name }
        if( functionNode )
            return functionNode

        // TODO: add process, workflow imports
        final inWorkflow = getEnclosingNodeOfType(call, WorkflowNode.class, ast) != null
        if( inWorkflow ) {
            for( final method : WorkflowDsl.getDeclaredMethods() ) {
                final annot = method.getAnnotation(Operator)
                if( !annot )
                    continue
                if( name != method.getName() )
                    continue
                final documentation = annot.value()
                return new OperatorNode(name, documentation.stripIndent(true).trim())
            }

            final processNode = ast.getProcessNodes(uri).find { node -> node.name == name }
            if( processNode )
                return processNode

            final workflowNode = ast.getWorkflowNodes(uri).find { node -> node.name == name }
            if( workflowNode )
                return workflowNode
        }

        return null
    }

    /**
     * Get the list of available method overloads for a method call expression.
     *
     * @param node
     * @param ast
     */
    static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, ASTNodeCache ast) {
        if( node instanceof MethodCallExpression ) {
            final leftType = getTypeOfNode(node.getObjectExpression(), ast)
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
     * @param ast
     */
    static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache ast) {
        return getMethodFromCallExpression(node, ast, -1)
    }

    /**
     * Find the method node which most closely matches a method call expression.
     *
     * @param node
     * @param ast
     * @param argIndex
     */
    static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache ast, int argIndex) {
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

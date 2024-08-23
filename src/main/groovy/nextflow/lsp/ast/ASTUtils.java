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
package nextflow.lsp.ast;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.IncludeVariable;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;

/**
 * Utility methods for querying an AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ASTUtils {

    /**
     * Get the ast node corresponding to the definition of a given
     * class, method, or variable.
     *
     * @param node
     * @param strict
     * @param ast
     */
    public static ASTNode getDefinition(ASTNode node, boolean strict, ASTNodeCache ast) {
        if( node instanceof VariableExpression ve ) {
            var variable = ve.getAccessedVariable();
            return getDefinitionFromVariable(variable);
        }

        if( node instanceof ConstantExpression ce ) {
            var parentNode = ast.getParent(ce);
            if( parentNode instanceof MethodCallExpression mce )
                return getMethodFromCallExpression(mce, ast);
            if( parentNode instanceof PropertyExpression pe )
                return getFieldFromExpression(pe, ast);
        }

        if( node instanceof ClassExpression ce )
            return getOriginalClassNode(ce.getType(), strict, ast);

        if( node instanceof ConstructorCallExpression cce )
            return getOriginalClassNode(cce.getType(), strict, ast);

        if( node instanceof FeatureFlagNode ffn )
            return ffn.accessedVariable != null ? ffn : null;

        if( node instanceof IncludeVariable iv )
            return iv.getMethod();

        if( node instanceof MethodNode )
            return node;

        if( node instanceof Variable )
            return node;

        return null;
    }

    /**
     * Get the ast nodes corresponding to references of a node.
     *
     * @param node
     * @param ast
     * @param includeDeclaration
     * @param includeIncludes
     */
    public static Iterator<ASTNode> getReferences(ASTNode node, ASTNodeCache ast, boolean includeDeclaration, boolean includeIncludes) {
        var defNode = getDefinition(node, true, ast);
        if( defNode == null )
            return Collections.emptyIterator();
        return ast.getNodes().stream()
            .filter(otherNode -> {
                if( otherNode.getLineNumber() == -1 || otherNode.getColumnNumber() == -1 )
                    return false;
                if( defNode == otherNode )
                    return includeDeclaration;
                if( !includeIncludes && otherNode instanceof IncludeVariable )
                    return false;
                return defNode == getDefinition(otherNode, false, ast);
            })
            .iterator();
    }

    /**
     * Get the type (i.e. class node) of an ast node.
     *
     * @param node
     * @param ast
     */
    public static ClassNode getTypeOfNode(ASTNode node, ASTNodeCache ast) {
        if( node instanceof ClassExpression ce ) {
            // type(Foo.bar) -> type(Foo)
            return ce.getType();
        }

        if( node instanceof ConstructorCallExpression cce ) {
            // type(new Foo()) -> type(Foo)
            return cce.getType();
        }

        if( node instanceof MethodCallExpression mce ) {
            var methodNode = getMethodFromCallExpression(mce, ast);
            return methodNode != null
                ? methodNode.getReturnType()
                : mce.getType();
        }

        if( node instanceof PropertyExpression pe ) {
            var fieldNode = getFieldFromExpression(pe, ast);
            return fieldNode != null
                ? getTypeOfNode(fieldNode, ast)
                : pe.getType();
        }

        if( node instanceof Variable variable ) {
            if( variable.isDynamicTyped() ) {
                var defNode = getDefinition(node, false, ast);
                if( defNode instanceof Variable defVar ) {
                    if( defVar.hasInitialExpression() ) {
                        return getTypeOfNode(defVar.getInitialExpression(), ast);
                    }
                    var declNode = ast.getParent(defNode);
                    if( declNode instanceof DeclarationExpression declExp )
                        return getTypeOfNode(declExp.getRightExpression(), ast);
                }
            }

            if( variable.getOriginType() != null )
                return variable.getOriginType();
        }

        return node instanceof Expression exp
            ? exp.getType()
            : null;
    }

    private static ClassNode getOriginalClassNode(ClassNode node, boolean strict, ASTNodeCache ast) {
        // TODO: built-in types
        return strict ? null : node;
    }

    /**
     * Get the set of available fields for a type.
     *
     * @param classNode
     * @param ast
     */
    public static Iterator<FieldNode> getFieldsForType(ClassNode classNode, boolean isStatic, ASTNodeCache ast) {
        return classNode.getFields().stream()
            .filter(fieldNode -> {
                if( !fieldNode.isPublic() )
                    return false;
                return isStatic ? fieldNode.isStatic() : !fieldNode.isStatic();
            })
            .iterator();
    }

    /**
     * Get the set of available methods for a type.
     *
     * @param classNode
     * @param ast
     */
    public static Iterator<MethodNode> getMethodsForType(ClassNode classNode, boolean isStatic, ASTNodeCache ast) {
        return classNode.getMethods().stream()
            .filter(methodNode -> {
                if( !methodNode.isPublic() )
                    return false;
                return isStatic ? methodNode.isStatic() : !methodNode.isStatic();
            })
            .iterator();
    }

    private static FieldNode getFieldFromExpression(PropertyExpression node, ASTNodeCache ast) {
        var classNode = getTypeOfNode(node.getObjectExpression(), ast);
        if( classNode == null )
            return null;
        return classNode.getField(node.getPropertyAsString());
    }

    /**
     * Find the method node which most closely matches a call expression.
     *
     * @param node
     * @param ast
     * @param argIndex
     */
    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache ast, int argIndex) {
        if( !(node.getArguments() instanceof ArgumentListExpression) )
            return null;

        var methods = getMethodOverloadsFromCallExpression(node, ast);
        var arguments = (ArgumentListExpression) node.getArguments();
        var result = methods.stream().max((MethodNode m1, MethodNode m2) -> {
            var score1 = getArgumentsScore(m1.getParameters(), arguments, argIndex);
            var score2 = getArgumentsScore(m2.getParameters(), arguments, argIndex);
            return Integer.compare(score1, score2);
        });

        return result.orElse(null);
    }

    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache ast) {
        return getMethodFromCallExpression(node, ast, -1);
    }

    /**
     * Get the list of available method overloads for a method call expression.
     *
     * @param node
     * @param ast
     */
    public static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, ASTNodeCache ast) {
        if( node instanceof MethodCallExpression mce ) {
            if( mce.isImplicitThis() ) {
                var defNode = getDefinitionFromScope(mce, mce.getMethodAsString(), ast);
                if( defNode instanceof MethodNode mn )
                    return List.of(mn);
            }
            else {
                var leftType = getTypeOfNode(mce.getObjectExpression(), ast);
                if( leftType != null )
                    return leftType.getMethods(mce.getMethodAsString());
            }
        }

        if( node instanceof ConstructorCallExpression cce ) {
            var constructorType = cce.getType();
            if( constructorType != null ) {
                return constructorType.getDeclaredConstructors().stream()
                    .map(ctor -> (MethodNode) ctor)
                    .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    private static int getArgumentsScore(Parameter[] parameters, ArgumentListExpression arguments, int argIndex) {
        var paramsCount = parameters.length;
        var expressionsCount = arguments.getExpressions().size();
        var argsCount = argIndex >= expressionsCount
            ? argIndex + 1
            : expressionsCount;
        var minCount = Math.min(paramsCount, argsCount);

        int score = 0;
        if( minCount == 0 && paramsCount == argsCount )
            score++;

        for( int i = 0; i < minCount; i++ ) {
            var paramType = (i < paramsCount) ? parameters[i].getType() : null;
            var argType = (i < expressionsCount) ? arguments.getExpression(i).getType() : null;
            if( argType != null && paramType != null ) {
                // equal types > subtypes > different types
                if( argType.equals(paramType) )
                    score += 1000;
                else if( argType.isDerivedFrom(paramType) )
                    score += 100;
                else
                    score++;
            }
            else if( paramType != null ) {
                score++;
            }
        }
        return score;
    }

    private static ASTNode getDefinitionFromScope(ASTNode node, String name, ASTNodeCache ast) {
        Variable variable = null;
        VariableScope scope = getVariableScope(node, ast);
        while( scope != null && variable == null ) {
            variable = scope.getDeclaredVariable(name);
            if( variable != null )
                break;
            variable = scope.getReferencedLocalVariable(name);
            if( variable != null )
                break;
            variable = scope.getReferencedClassVariable(name);
            if( variable != null )
                break;
            scope = scope.getParent();
        }
        return getDefinitionFromVariable(variable);
    }

    private static ASTNode getDefinitionFromVariable(Variable variable) {
        if( variable instanceof IncludeVariable iv )
            return iv.getMethod();
        if( variable instanceof PropertyNode pn )
            return (MethodNode) pn.getNodeMetaData("access.method");
        if( variable instanceof ASTNode node )
            return node;
        return null;
    }

    /**
     * Get the nearest variable scope of a node.
     *
     * @param node
     * @param ast
     */
    public static VariableScope getVariableScope(ASTNode node, ASTNodeCache ast) {
        ASTNode current = node;
        while( current != null ) {
            if( current instanceof BlockStatement block )
                return block.getVariableScope();
            current = ast.getParent(current);
        }
        return null;
    }

}

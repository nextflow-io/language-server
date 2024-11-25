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

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.IncludeVariable;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.Description;
import nextflow.script.types.Channel;
import nextflow.script.types.NamedTuple;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
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
import org.codehaus.groovy.ast.stmt.ExpressionStatement;

import static nextflow.script.ast.ASTHelpers.*;

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
     * @param ast
     */
    public static ASTNode getDefinition(ASTNode node, ASTNodeCache ast) {
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
            return ce.getType().redirect();

        if( node instanceof ConstructorCallExpression cce )
            return cce.getType().redirect();

        if( node instanceof FeatureFlagNode ffn )
            return ffn.target != null ? ffn : null;

        if( node instanceof IncludeVariable iv )
            return iv.getMethod();

        if( node instanceof ClassNode cn )
            return node;

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
     */
    public static Iterator<ASTNode> getReferences(ASTNode node, ASTNodeCache ast, boolean includeDeclaration) {
        var defNode = getDefinition(node, ast);
        if( defNode == null )
            return Collections.emptyIterator();
        return ast.getNodes().stream()
            .filter((otherNode) -> {
                if( otherNode.getLineNumber() == -1 || otherNode.getColumnNumber() == -1 )
                    return false;
                if( defNode == otherNode )
                    return includeDeclaration;
                return defNode == getDefinition(otherNode, ast);
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
            var mn = getMethodFromCallExpression(mce, ast);
            return mn != null
                ? mn.getReturnType()
                : mce.getType();
        }

        if( node instanceof PropertyExpression pe ) {
            var mn = asMethodOutput(pe, ast);
            if( mn instanceof ProcessNode pn )
                return asProcessOut(pn);
            if( mn instanceof WorkflowNode wn )
                return asWorkflowOut(wn);
            var fn = getFieldFromExpression(pe, ast);
            return fn != null
                ? getTypeOfNode(fn, ast)
                : pe.getType();
        }

        if( node instanceof Variable variable ) {
            if( variable.isDynamicTyped() ) {
                var defNode = getDefinition(node, ast);
                if( defNode instanceof Variable defVar ) {
                    if( defVar.hasInitialExpression() ) {
                        return getTypeOfNode(defVar.getInitialExpression(), ast);
                    }
                    var declNode = ast.getParent(defNode);
                    if( declNode instanceof DeclarationExpression de )
                        return getTypeOfNode(de.getRightExpression(), ast);
                }
            }

            if( variable.getOriginType() != null )
                return variable.getOriginType();
        }

        return node instanceof Expression exp
            ? exp.getType()
            : null;
    }

    private static MethodNode asMethodOutput(PropertyExpression node, ASTNodeCache ast) {
        if( node.getObjectExpression() instanceof VariableExpression ve && "out".equals(node.getPropertyAsString()) ) {
            var defNode = ASTUtils.getDefinition(ve, ast);
            if( defNode instanceof MethodNode mn )
                return mn;
        }
        return null;
    }

    private static ClassNode asProcessOut(ProcessNode pn) {
        var cn = new ClassNode(NamedTuple.class);
        asDirectives(pn.outputs)
            .map(call -> getProcessEmitName(call))
            .filter(name -> name != null)
            .forEach((name) -> {
                var type = ClassHelper.makeCached(Channel.class);
                var fn = new FieldNode(name, Modifier.PUBLIC, type, cn, null);
                fn.setDeclaringClass(cn);
                cn.addField(fn);
            });
        return cn;
    }

    private static String getProcessEmitName(MethodCallExpression output) {
        return Optional.of(output)
            .flatMap(call -> Optional.ofNullable(asNamedArgs(call)))
            .flatMap(namedArgs ->
                namedArgs.stream()
                    .filter(entry -> "emit".equals(entry.getKeyExpression().getText()))
                    .findFirst()
            )
            .flatMap(entry -> Optional.ofNullable(
                entry.getValueExpression() instanceof VariableExpression ve ? ve.getName() : null
            ))
            .orElse(null);
    }

    private static ClassNode asWorkflowOut(WorkflowNode wn) {
        var cn = new ClassNode(NamedTuple.class);
        asBlockStatements(wn.emits).stream()
            .map(stmt -> ((ExpressionStatement) stmt).getExpression())
            .map(emit -> getWorkflowEmitName(emit))
            .filter(name -> name != null)
            .forEach((name) -> {
                var type = ClassHelper.dynamicType();
                var fn = new FieldNode(name, Modifier.PUBLIC, type, cn, null);
                fn.setDeclaringClass(cn);
                cn.addField(fn);
            });
        return cn;
    }

    private static String getWorkflowEmitName(Expression emit) {
        if( emit instanceof VariableExpression ve ) {
            return ve.getName();
        }
        else if( emit instanceof AssignmentExpression ae ) {
            var left = (VariableExpression)ae.getLeftExpression();
            return left.getName();
        }
        return null;
    }

    private static FieldNode getFieldFromExpression(PropertyExpression node, ASTNodeCache ast) {
        var cn = getTypeOfNode(node.getObjectExpression(), ast);
        if( cn == null )
            return null;
        var name = node.getPropertyAsString();
        var result = cn.getField(name);
        if( result != null )
            return result;
        return cn.getMethods().stream()
            .filter((mn) -> {
                if( !mn.isPublic() )
                    return false;
                var an = findAnnotation(mn, Constant.class);
                if( !an.isPresent() )
                    return false;
                return name.equals(an.get().getMember("value").getText());
            })
            .map((mn) -> {
                var fn = new FieldNode(name, 0xF, mn.getReturnType(), mn.getDeclaringClass(), null);
                findAnnotation(mn, Description.class).ifPresent((an) -> {
                    fn.addAnnotation(an);
                });
                return fn;
            })
            .findFirst().orElse(null);
    }

    /**
     * Find the method node which most closely matches a call expression.
     *
     * @param node
     * @param ast
     * @param argIndex
     */
    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeCache ast, int argIndex) {
        var methods = getMethodOverloadsFromCallExpression(node, ast);
        var arguments = (ArgumentListExpression) node.getArguments();
        return methods.stream()
            .max((MethodNode m1, MethodNode m2) -> {
                var score1 = getArgumentsScore(m1.getParameters(), arguments, argIndex);
                var score2 = getArgumentsScore(m2.getParameters(), arguments, argIndex);
                return Integer.compare(score1, score2);
            })
            .orElse(null);
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

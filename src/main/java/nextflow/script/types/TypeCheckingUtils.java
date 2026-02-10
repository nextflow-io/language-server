/*
 * Copyright 2024-2025, Seqera Labs
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
package nextflow.script.types;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.dsl.Ops;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.Description;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.tools.GenericsUtils;
import org.codehaus.groovy.ast.tools.ParameterUtils;

import static nextflow.script.ast.ASTUtils.*;
import static org.codehaus.groovy.ast.GenericsType.GenericsTypeName;

/**
 * Utility functions for resolving Nextflow types.
 *
 * @see org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport
 * @see org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class TypeCheckingUtils {

    /**
     * Get the type (i.e. class node) of a variable.
     *
     * @param variable
     */
    public static ClassNode getType(Variable variable) {
        return variable instanceof Expression node
            ? getType(node)
            : variable.getType();
    }

    /**
     * Get the type (i.e. class node) of an expression.
     *
     * @param node
     */
    public static ClassNode getType(Expression node) {
        if( node.getNodeMetaData(ASTNodeMarker.INFERRED_TYPE) instanceof ClassNode cn )
            return cn;
        var result = resolveType(node);
        node.putNodeMetaData(ASTNodeMarker.INFERRED_TYPE, result);
        return result;
    }

    private static ClassNode resolveType(Expression node) {
        if( node instanceof ClassExpression ce )
            return ce.getType();

        if( node instanceof ConstructorCallExpression cce )
            return cce.getType();

        if( node instanceof MethodCallExpression mce ) {
            var mn = resolveMethodCall(mce);
            return mn != null ? mn.getReturnType() : ClassHelper.dynamicType();
        }

        if( node instanceof PropertyExpression pe ) {
            var fn = resolveProperty(pe);
            return fn != null ? fn.getType() : ClassHelper.dynamicType();
        }

        if( node instanceof VariableExpression ve ) {
            var accessedVariable = ve.getAccessedVariable();
            if( accessedVariable != null && accessedVariable != ve )
                return getType(accessedVariable);
        }

        if( node instanceof Variable variable && variable.getOriginType() != null )
            return variable.getOriginType();

        if( node instanceof AssignmentExpression ae )
            return getType(ae.getLeftExpression());

        return node.getType();
    }

    /**
     * Find the field node targeted by a property expression.
     *
     * @param node
     */
    public static FieldNode resolveProperty(PropertyExpression node) {
        return resolveProperty(getType(node.getObjectExpression()), node.getPropertyAsString());
    }

    public static FieldNode resolveProperty(ClassNode receiverType, String property) {
        receiverType = TypesEx.normalize(receiverType);
        if( receiverType == null )
            return null;
        var result = receiverType.getField(property);
        if( result != null )
            return result;
        return receiverType.getMethods().stream()
            .filter((mn) -> {
                if( !mn.isPublic() )
                    return false;
                var an = findAnnotation(mn, Constant.class);
                if( !an.isPresent() )
                    return false;
                return property.equals(an.get().getMember("value").getText());
            })
            .map((mn) -> {
                var fn = new FieldNode(property, 0xF, mn.getReturnType(), mn.getDeclaringClass(), null);
                findAnnotation(mn, Description.class).ifPresent((an) -> {
                    fn.addAnnotation(an);
                });
                return fn;
            })
            .findFirst().orElse(resolveMapProperty(receiverType, property));
    }

    private static FieldNode resolveMapProperty(ClassNode cn, String name) {
        if( !TypesEx.isAssignableFrom(ClassHelper.MAP_TYPE, cn) )
            return null;
        var gts = cn.getGenericsTypes();
        if( gts == null )
            return new FieldNode(name, 0xF, ClassHelper.dynamicType(), cn, null);
        var keyType = gts[0].getType();
        if( !TypesEx.isEqual(ClassHelper.STRING_TYPE, keyType) )
            return null;
        var valueType = gts[1].getType();
        return new FieldNode(name, 0xF, valueType, cn, null);
    }

    /**
     * Find the method node targeted by a call expression.
     *
     * @param node
     */
    public static MethodNode resolveMethodCall(MethodCallExpression node) {
        var receiverType = getType(node.getObjectExpression());
        var resolvedPlaceholders = GenericsUtils.extractPlaceholders(receiverType);
        var methods = methodOverloads(node);
        var arguments = asMethodCallArguments(node);
        return methods.stream()
            .filter((mn) -> isCompatibleMethod(mn, arguments, resolvedPlaceholders))
            .findFirst()
            .orElse(null);
    }

    private static final ClassNode TUPLE_TYPE = ClassHelper.makeCached(Tuple.class);

    private static List<MethodNode> methodOverloads(MethodCallExpression node) {
        if( node.getNodeMetaData(ASTNodeMarker.METHOD_TARGET) instanceof MethodNode mn )
            return List.of(mn);

        var methods = (List<MethodNode>) node.getNodeMetaData(ASTNodeMarker.METHOD_OVERLOADS);
        if( methods != null )
            return methods;

        if( !node.isImplicitThis() ) {
            var receiverType = TypesEx.normalize(getType(node.getObjectExpression()));
            if( receiverType != null ) {
                methods = methodsForType(receiverType, node.getMethodAsString());
                if( methods.size() == 1 )
                    node.putNodeMetaData(ASTNodeMarker.METHOD_TARGET, methods.get(0));
                else if( !methods.isEmpty() )
                    node.putNodeMetaData(ASTNodeMarker.METHOD_OVERLOADS, methods);
                return methods;
            }
        }

        return Collections.emptyList();
    }

    private static List<MethodNode> methodsForType(ClassNode cn, String name) {
        return cn.getAllDeclaredMethods().stream()
            .filter(mn -> name.equals(mn.getName()))
            .filter(mn -> !ClassHelper.isObjectType(mn.getDeclaringClass()))
            .toList();
    }

    /**
     * Determine whether a method is compatible with the given call arguments.
     *
     * In order for a method to be compatible, each method parameter must be
     * assignable to the corresponding argument.
     *
     * When assigning a closure to a parameter with a functional type, and the
     * functional type accepts one parameter, the closure can instead accept
     * multiple parameters if the target parameter type is a tuple with the same
     * number of components. The Groovy runtime will automatically destructure
     * tuple arguments when calling this closure.
     *
     * @param mn
     * @param arguments
     * @param resolvedPlaceholders
     */
    private static boolean isCompatibleMethod(MethodNode mn, List<Expression> arguments, Map<GenericsTypeName, GenericsType> resolvedPlaceholders) {
        var parameters = expandVargs(mn.getParameters(), arguments.size());
        if( parameters.length != arguments.size() ) {
            return false;
        }
        for( int i = 0; i < parameters.length; i++ ) {
            var paramType = applyGenericsContext(resolvedPlaceholders, parameters[i].getType());
            var source = arguments.get(i);

            if( source instanceof ClosureExpression ce && TypesEx.isFunctionalInterface(paramType) ) {
                var samParameterTypes = GenericsUtils.parameterizeSAM(paramType).getV1();
                var closureParams = ce.getParameters();
                if( samParameterTypes.length == 1 && closureParams.length > 1 ) {
                    if( !isTupleDestructure(samParameterTypes[0], closureParams) )
                        return false;
                }
                else if( samParameterTypes.length != closureParams.length ) {
                    return false;
                }
                else {
                    for( int j = 0; j < closureParams.length; j++ ) {
                        if( !TypesEx.isAssignableFrom(closureParams[j].getType(), samParameterTypes[j]) )
                            return false;
                    }
                }
            }
            else if( !TypesEx.isAssignableFrom(paramType, getType(source)) ) {
                return false;
            }
        }
        return true;
    }

    private static Parameter[] expandVargs(Parameter[] params, int argCount) {
        if( !ParameterUtils.isVargs(params) )
            return params;
        if( argCount < params.length - 1 )
            return params;
        var result = new Parameter[argCount];
        int vargIndex = params.length - 1;
        var varg = params[vargIndex];
        var vargParam = new Parameter(varg.getType().getComponentType(), varg.getName());
        for( int i = 0; i < vargIndex; i++ )
            result[i] = params[i];
        for( int i = vargIndex; i < result.length; i++ )
            result[i] = vargParam;
        return result;
    }

    private static boolean isTupleDestructure(ClassNode samParameterType, Parameter[] closureParams) {
        if( ClassHelper.isDynamicTyped(samParameterType) )
            return true;
        if( !TUPLE_TYPE.equals(samParameterType) )
            return false;
        var gts = samParameterType.getGenericsTypes();
        if( gts == null )
            return true;
        if( gts.length != closureParams.length )
            return false;
        for( int i = 0; i < closureParams.length; i++ ) {
            if( !TypesEx.isAssignableFrom(closureParams[i].getType(), gts[i].getType()) )
                return false;
        }
        return true;
    }

    /**
     * Determine whether an expression is the `null` constant.
     *
     * @param node
     */
    public static boolean isNullConstant(Expression node) {
        return node instanceof ConstantExpression ce && ce.isNullExpression();
    }

    /**
     * Resolve the generics connections for a method call.
     *
     * @param receiverType
     * @param method
     * @param arguments
     */
    public static Map<GenericsTypeName, GenericsType> resolveGenericsConnections(ClassNode receiverType, MethodNode method, List<Expression> arguments) {
        // extract placeholders from declaring type
        var resolvedPlaceholders = GenericsUtils.extractPlaceholders(receiverType);

        // extract placeholders from method
        if( method.getGenericsTypes() == null )
            return resolvedPlaceholders;

        var methodTypeParameters = applyGenericsContext(resolvedPlaceholders, method.getGenericsTypes());
        var parameters = expandVargs(method.getParameters(), arguments.size());

        for( int i = 0; i < arguments.size(); i++ ) {
            var paramType = parameters[i].getType();
            if( !GenericsUtils.hasUnresolvedGenerics(paramType) )
                continue;
            var argument = arguments.get(i);
            if( isNullConstant(argument) )
                continue;

            var connections = new HashMap<GenericsTypeName, GenericsType>();

            // extract generics connections from closure parameter types (if specified)
            if( argument instanceof ClosureExpression ce && TypesEx.isFunctionalInterface(paramType) ) {
                var samParameterTypes = GenericsUtils.parameterizeSAM(paramType).getV1();
                var closureParams = ce.getParameters();
                for( int j = 0; j < closureParams.length; j++ ) {
                    if( !closureParams[j].isDynamicTyped() )
                        extractGenericsConnections(connections, closureParams[j].getType(), samParameterTypes[j]);
                }
            }

            // extract generics connections from argument types
            else {
                extractGenericsConnections(connections, getType(argument), paramType);
            }

            // apply connections to placeholders mapping
            connections.forEach((key, gt) -> {
                for( var tp : methodTypeParameters ) {
                    if( tp.getName().equals(key.getName()) ) {
                        resolvedPlaceholders.putIfAbsent(key, gt);
                        break;
                    }
                }
            });
        }

        return resolvedPlaceholders;
    }

    /**
     * Resolve the parameter types of a closure argument based on the
     * surrounding method call.
     *
     * For example, given the following code:
     *
     *   [1, 2, 3].collect { v -> v * 2 }
     *
     * The type of `v` is resolved as Integer, since the receiver type is
     * List<E> (where E=Integer) and the `collect` method expects a closure
     * with type (E) -> R.
     *
     * @param source
     * @param target
     * @param resolvedPlaceholders
     */
    public static ClassNode[] resolveClosureParameterTypes(ClosureExpression source, Parameter target, Map<GenericsTypeName, GenericsType> resolvedPlaceholders) {
        if( !TypesEx.isFunctionalInterface(target.getType()) )
            return null;

        var targetType = applyGenericsContext(resolvedPlaceholders, target.getType());
        var samTypeInfo = GenericsUtils.parameterizeSAM(targetType);
        var samParameterTypes = samTypeInfo.getV1();
        var samReturnType = samTypeInfo.getV2();
        var closureParams = source.getParameters();

        if( samParameterTypes.length == 1 && closureParams.length > 1 ) {
            var gts = samParameterTypes[0].getGenericsTypes();
            for( int i = 0; gts != null && i < closureParams.length; i++ )
                applyTargetType(closureParams[i], gts[i].getType());
        }
        else {
            for( int i = 0; i < closureParams.length; i++ )
                applyTargetType(closureParams[i], samParameterTypes[i]);
        }

        source.putNodeMetaData(ASTNodeMarker.INFERRED_RETURN_TYPE, samReturnType);

        return samParameterTypes;
    }

    private static void applyTargetType(Parameter parameter, ClassNode type) {
        if( parameter.isDynamicTyped() )
            parameter.setType(type);
    }

    /**
     * Resolve a parameterized method based on method call arguments.
     *
     * For example, `channel.of(E...) -> Channel<E>` when called with
     * arguments (1, 2, 3) has return type Channel<Integer>.
     *
     * @param receiverType
     * @param method
     * @param arguments
     */
    public static MethodNode resolveGenericReturnType(ClassNode receiverType, MethodNode method, List<Expression> arguments) {
        var returnType = method.getReturnType();
        if( !GenericsUtils.hasUnresolvedGenerics(returnType) )
            return method;

        var context = GenericsUtils.extractPlaceholders(receiverType);
        var methodTypeParameters = applyGenericsContext(context, method.getGenericsTypes());

        // resolve type parameters of method
        var parameters = method.getParameters();

        if( methodTypeParameters != null ) {
            var resolvedPlaceholders = new HashMap<GenericsTypeName, GenericsType>();
            for( var gt : methodTypeParameters )
                resolvedPlaceholders.put(new GenericsTypeName(gt.getName()), gt);

            for( int i = 0; i < parameters.length; i++ ) {
                var paramType = parameters[i].getType();
                if( !TypesEx.isFunctionalInterface(paramType) )
                    paramType = applyGenericsContext(context, paramType);
                parameters[i] = new Parameter(paramType, parameters[i].getName());
            }

            var expandedParams = expandVargs(parameters, arguments.size());
            var connections = extractGenericsConnectionsFromArguments(methodTypeParameters, expandedParams, arguments);
            applyGenericsConnections(connections, resolvedPlaceholders);

            returnType = applyGenericsContext(resolvedPlaceholders, returnType);
        }

        // resolve type parameters of declaring type
        returnType = applyGenericsContext(context, returnType);

        return asDummyMethod(receiverType, method, parameters, returnType);
    }

    /**
     * Create a "dummy" method node with resolved receiver type,
     * parameters, and return type.
     * 
     * @param receiverType
     * @param method
     * @param parameters
     * @param returnType
     */
    public static MethodNode asDummyMethod(ClassNode receiverType, MethodNode method, Parameter[] parameters, ClassNode returnType) {
        var result = new MethodNode(
            method.getName(),
            method.getModifiers(),
            returnType,
            parameters,
            ClassNode.EMPTY_ARRAY,
            method.getCode() );
        result.setDeclaringClass(receiverType != null ? receiverType : method.getDeclaringClass());
        return result;
    }

    /**
     * Resolve type parameters declared by method from declaring type or call arguments.
     *
     * @param methodTypeParameters
     * @param parameters
     * @param arguments
     */
    private static Map<GenericsTypeName, GenericsType> extractGenericsConnectionsFromArguments(GenericsType[] methodTypeParameters, Parameter[] parameters, List<Expression> arguments) {
        var result = new HashMap<GenericsTypeName, GenericsType>();

        for( int i = 0; i < arguments.size(); i++ ) {
            var paramType = parameters[i].getType();
            if( !GenericsUtils.hasUnresolvedGenerics(paramType) )
                continue;
            var argument = arguments.get(i);
            if( isNullConstant(argument) )
                continue;

            // extract generics connections from argument types
            var connections = new HashMap<GenericsTypeName, GenericsType>();

            if( argument instanceof ClosureExpression && TypesEx.isFunctionalInterface(paramType) ) {
                var samTypeInfo = GenericsUtils.parameterizeSAM(paramType);
                var samReturnType = samTypeInfo.getV2();
                var returnType = (ClassNode) argument.getNodeMetaData(ASTNodeMarker.INFERRED_RETURN_TYPE);
                extractGenericsConnections(connections, returnType, samReturnType);
            }
            else {
                extractGenericsConnections(connections, getType(argument), paramType);
            }

            // apply connections to placeholders mapping
            connections.forEach((gtn, gt) -> {
                // TODO: report error if connection already exists with different type
                // if( result.containsKey(gtn) && result.get(gtn) != gt )
                //     throw new Exception("...");
                
                result.putIfAbsent(gtn, gt);
            });
        }

        return result;
    }

    /**
     * Infer generic type mapping from a source and target type.
     *
     * - When the target is a placeholder, it is simply assigned to
     *   the source type in the mapping. For example, Path and T
     *   yield T -> Path.
     *
     * - When the source type is a placeholder... ?
     *
     * - When the target type is assignable from the source type, their
     *   type arguments are compared for possible connections. For example,
     *   List<Path> and Iterable<R> yield R -> Path.
     *
     * @param connections
     * @param type
     * @param target
     */
    private static void extractGenericsConnections(Map<GenericsTypeName, GenericsType> connections, ClassNode type, ClassNode target) {
        if( type == null || target == null || target == type )
            return;
        if( !target.isGenericsPlaceHolder() && !target.isUsingGenerics() )
            return;

        if( target.isGenericsPlaceHolder() ) {
            // given S and T, store T -> S
            storeGenericsConnection(connections, target.getUnresolvedName(), new GenericsType(type));
        }
        else if( type.isGenericsPlaceHolder() ) {
            // given T -> List<S> and List<E>, store E -> S
            extractGenericsConnections(connections, extractType(new GenericsType(type)), target);
        }
        else if( TypesEx.isAssignableFrom(target, type, false) ) {
            // given List<S> and Iterable<R>, store R -> S
            extractGenericsConnections(connections, type.getGenericsTypes(), target.getGenericsTypes());
        }
    }

    private static void extractGenericsConnections(Map<GenericsTypeName, GenericsType> connections, GenericsType[] usage, GenericsType[] declaration) {
        if( usage == null || declaration == null || declaration.length == 0 ) return;
        if( usage.length != declaration.length ) return;

        for( int i = 0; i < usage.length; i++ ) {
            var ui = usage[i];
            var di = declaration[i];
            if( di.isPlaceholder() ) {
                // di like "T"
                storeGenericsConnection(connections, di.getName(), ui);
            }
            else if( di.isWildcard() ) {
                // di like "?"
            }
            else {
                // di like "List<T>", "List<String>", ...
                extractGenericsConnections(connections, ui.getType(), di.getType());
            }
        }
    }

    private static void storeGenericsConnection(Map<GenericsTypeName, GenericsType> connections, String placeholderName, GenericsType gt) {
        connections.put(new GenericsTypeName(placeholderName), gt);
    }

    private static ClassNode extractType(GenericsType gt) {
        // discard the placeholder if present
        return gt.isPlaceholder()
            ? gt.getType().redirect()
            : gt.getType();
    }

    /**
     * Apply generic type connections inferred from call arguments
     * to the given placeholders mapping.
     *
     * @param connections
     * @param resolvedPlaceholders
     */
    private static void applyGenericsConnections(Map<GenericsTypeName, GenericsType> connections, Map<GenericsTypeName, GenericsType> resolvedPlaceholders) {
        if( connections == null || connections.isEmpty() )
            return;

        for( var entry : resolvedPlaceholders.entrySet() ) {
            // entry could be T=T, T=V, T=String, etc.
            var oldValue = entry.getValue();
            if( !oldValue.isPlaceholder() )
                continue;
            // T=T or T=V, not T=String or T=? ...
            var name = new GenericsTypeName(oldValue.getName());
            var newValue = connections.get(name); // find "V" in T=V
            if( newValue == oldValue )
                continue;
            if( newValue == null ) {
                newValue = connections.get(entry.getKey());

                // TODO: is this needed?
                // if( newValue != null )
                //     newValue = getCombinedGenericsType(oldValue, newValue);
            }
            if( newValue == null ) {
                newValue = applyGenericsContext(connections, oldValue);
                entry.setValue(newValue);
            }
            else if( !newValue.isPlaceholder() || newValue != resolvedPlaceholders.get(name) ) {
                entry.setValue(newValue);

                // TOOD: is this needed?
                // // GROOVY-6787: Don't override the original if the replacement doesn't respect the bounds otherwise
                // // the original bounds are lost, which can result in accepting an incompatible type as an argument!
                // var replacementType = extractType(newValue);
                // var suitabilityType = !replacementType.isGenericsPlaceHolder()
                //     ? replacementType
                //     : Optional.ofNullable(replacementType.getGenericsTypes()).map(gts -> extractType(gts[0])).orElse(replacementType.redirect());

                // if( oldValue.isCompatibleWith(suitabilityType) ) {
                //     if( newValue.isWildcard() ) {
                //         // GROOVY-9998: apply upper/lower bound for unknown
                //         entry.setValue(replacementType.asGenericsType());
                //     }
                //     else {
                //         entry.setValue(newValue);
                //     }
                // }
            }
        }
    }

    static GenericsType getCombinedGenericsType(GenericsType gt1, GenericsType gt2) {
        if( gt1.isWildcard() != gt2.isWildcard() ) return gt2.isWildcard() ? gt1 : gt2;
        // if( gt2.isPlaceholder() && gt2.getName().startsWith("#") ) return gt1;
        // if( gt1.isPlaceholder() && gt1.getName().startsWith("#") ) return gt2;
        // TODO: assert gt1 == gt2 ?
        return gt1;
    }

    /**
     * Resolve a parameterized type against the declaring type with
     * resolved type parameters.
     *
     * @param type
     * @param declaringType
     */
    public static ClassNode resolveGenericType(ClassNode type, ClassNode declaringType) {
        var spec = GenericsUtils.extractPlaceholders(declaringType);
        return applyGenericsContext(spec, type);
    }

    private static ClassNode applyGenericsContext(Map<GenericsTypeName, GenericsType> spec, ClassNode type) {
        if( type == null )
            return type;
        if( type.isArray() )
            return applyGenericsContext(spec, type.getComponentType()).makeArray();
        if( type.getGenericsTypes() == null )
            return type;

        var gts = type.getGenericsTypes();
        if( spec != null && !spec.isEmpty() )
            gts = applyGenericsContext(spec, gts);

        if( !type.isGenericsPlaceHolder() ) {
            // convert Type<T> to Type<...>
            var cn = type.getPlainNodeReference();
            cn.setGenericsTypes(gts);
            return cn;
        }

        if( !gts[0].isPlaceholder() ) {
            // convert T -> Type to Type
            return gts[0].getType();
        }

        if( type.getGenericsTypes()[0] != gts[0] ) {
            // convert T to X
            var cn = ClassHelper.make(gts[0].getName());
            var erasure = gts[0].getType().redirect();
            cn.setGenericsPlaceHolder(true);
            cn.setGenericsTypes(gts);
            cn.setRedirect(erasure);
            return cn;
        }

        return type;
    }

    private static GenericsType[] applyGenericsContext(Map<GenericsTypeName, GenericsType> spec, GenericsType[] gts) {
        if( gts == null || gts.length == 0 || spec == null || spec.isEmpty() )
            return gts;

        return Arrays.stream(gts)
            .map(gt -> applyGenericsContext(spec, gt))
            .toArray(GenericsType[]::new);
    }

    private static GenericsType applyGenericsContext(Map<GenericsTypeName, GenericsType> spec, GenericsType gt) {
        var type = gt.getType();

        if( gt.isPlaceholder() ) {
            var name = new GenericsTypeName(gt.getName());
            var specType = spec.get(name);
            if( specType != null )
                return specType;
            return gt;
        }

        if( gt.isWildcard() ) {
            var newGT = new GenericsType(type);
            newGT.setWildcard(true);
            return newGT;
        }

        if( type.isArray() ) {
            var newType = applyGenericsContext(spec, type.getComponentType()).makeArray();
            return new GenericsType(newType);
        }

        if( type.getGenericsTypes() == null )
            return gt;

        var newType = type.getPlainNodeReference();
        newType.setGenericsPlaceHolder(type.isGenericsPlaceHolder());
        newType.setGenericsTypes(applyGenericsContext(spec, type.getGenericsTypes()));
        return new GenericsType(newType);
    }

    /**
     * Get the Ops class for a given type.
     *
     * @param cn
     */
    public static ClassNode resolveOpsType(ClassNode cn) {
        if( cn == null || !cn.isResolved() )
            return null;
        var type = TypesEx.normalize(cn.getTypeClass());
        var annot = (Ops) type.getAnnotation(Ops.class);
        if( annot == null )
            return null;
        var opsType = ClassHelper.makeCached(annot.value());
        return resolveGenericType(opsType, cn);
    }

    /**
     * Resolve the result type of a binary operation.
     *
     * For example, the `+` operator for type Integer is defined as:
     *
     *   Integer plus(Integer a, Integer b);
     *
     * The ops types for each operand can be specified separately, in order
     * to support operators with operands of different types. For example, the
     * `in` operator for type List is defined as:
     *
     *   Boolean isCase(E a, List<E> b);
     *
     * @param lhsType
     * @param rhsType
     * @param lhsOps
     * @param rhsOps
     * @param name
     */
    public static ClassNode resolveOpResultType(ClassNode lhsType, ClassNode rhsType, ClassNode lhsOps, ClassNode rhsOps, String name) {
        var result = resolveOpResultType(lhsType, rhsType, lhsOps, name);
        if( result == null )
            result = resolveOpResultType(lhsType, rhsType, rhsOps, name);
        return result;
    }

    public static ClassNode resolveOpResultType(ClassNode lhsType, ClassNode rhsType, ClassNode opsType, String name) {
        if( opsType == null )
            return null;
        var resolvedPlaceholders = GenericsUtils.extractPlaceholders(opsType);
        return opsType.getDeclaredMethods(name).stream()
            .filter((mn) -> {
                var parameters = mn.getParameters();
                return parameters.length == 2
                    && isAssignableFrom(parameters[0], lhsType, resolvedPlaceholders)
                    && isAssignableFrom(parameters[1], rhsType, resolvedPlaceholders);
            })
            .map(mn -> applyGenericsContext(resolvedPlaceholders, mn.getReturnType()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Resolve the result type of a unary operation with different source/target types.
     *
     * For example, the type cast from String to Duration is defined as:
     *
     *   Duration ofType(String s);
     *
     * @param sourceType
     * @param opsType
     * @param name
     */
    public static ClassNode resolveOpResultType(ClassNode sourceType, ClassNode opsType, String name) {
        if( opsType == null )
            return null;
        var resolvedPlaceholders = GenericsUtils.extractPlaceholders(opsType);
        return opsType.getDeclaredMethods(name).stream()
            .filter((mn) -> {
                var parameters = mn.getParameters();
                return parameters.length == 1
                    && isAssignableFrom(parameters[0], sourceType, resolvedPlaceholders);
            })
            .map(mn -> applyGenericsContext(resolvedPlaceholders, mn.getReturnType()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Resolve the result type of a unary operation.
     *
     * For example, the `-` operator for type Integer is defined as:
     *
     *   Integer negative();
     *
     * @param opsType
     * @param name
     */
    public static ClassNode resolveOpResultType(ClassNode opsType, String name) {
        if( opsType == null )
            return null;
        return opsType.getDeclaredMethods(name).stream()
            .filter(mn -> mn.getParameters().length == 0)
            .map(mn -> resolveGenericType(mn.getReturnType(), opsType))
            .findFirst()
            .orElse(null);
    }

    private static boolean isAssignableFrom(Parameter parameter, ClassNode sourceType, Map<GenericsTypeName, GenericsType> resolvedPlaceholders) {
        var paramType = applyGenericsContext(resolvedPlaceholders, parameter.getType());
        return TypesEx.isAssignableFrom(paramType, sourceType);
    }

    /**
     * Make a parameterized type with the given type arguments.
     *
     * @param type
     * @param typeArguments
     */
    public static ClassNode makeType(ClassNode type, ClassNode... typeArguments) {
        var cn = type.getPlainNodeReference();
        var gts = Arrays.stream(typeArguments)
            .map(t -> new GenericsType(t))
            .toArray(GenericsType[]::new);
        cn.setGenericsTypes(gts);
        return cn;
    }

    /**
     * Return the element type of a type with one type parameter.
     *
     * @param type
     */
    public static ClassNode elementType(ClassNode type) {
        var gts = type.getGenericsTypes();
        if( gts == null || gts.length != 1 )
            return ClassHelper.dynamicType();
        return gts[0].getType();
    }
}

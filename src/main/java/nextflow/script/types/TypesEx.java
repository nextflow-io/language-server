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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import groovy.lang.GString;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.dsl.Namespace;
import nextflow.script.types.Record;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.tools.GenericsUtils;

/**
 * Utility constants and functions for working with Nextflow types.
 *
 * ClassNodes should be "normalized" into one of the standard Nextflow
 * types (see STANDARD_TYPES) before resolving fields and methods. They
 * must be normalized in a particular way in order to preserve generics
 * types and node metadata (e.g. ASTNodeMarker.NULLABLE).
 *
 * The functions in this class (isAssignableFrom, isEqual, getName)
 * normalize types automatically.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class TypesEx {

    /**
     * Determine whether a method has a non-void return type.
     *
     * @param node
     */
    public static boolean hasReturnType(MethodNode node) {
        var returnType = node.getReturnType();
        if( returnType.isGenericsPlaceHolder() )
            return true;
        return !ClassHelper.isObjectType(returnType) && !ClassHelper.isPrimitiveVoid(returnType);
    }

    /**
     * Determine whether a source type can be assigned to a target type.
     *
     * @see StaticTypeCheckingSupport.checkCompatibleAssignmentTypes()
     *
     * @param target
     * @param source
     * @param checkGenerics
     */
    public static boolean isAssignableFrom(ClassNode target, ClassNode source, boolean checkGenerics) {
        if( ClassHelper.isObjectType(target) || ClassHelper.isDynamicTyped(source) )
            return true;
        if( target.equals(source) )
            return true;
        return target.isResolved() && source.isResolved()
            && isAssignableFrom(target.getTypeClass(), source.getTypeClass())
            && (!checkGenerics || isAssignableFrom(target.getGenericsTypes(), source.getGenericsTypes()));
    }

    public static boolean isAssignableFrom(ClassNode target, ClassNode source) {
        return isAssignableFrom(target, source, false);
    }

    public static boolean isAssignableFrom(Class target, Class source) {
        target = normalize(target);
        source = normalize(source);
        if( target == Float_TYPE && source == Integer_TYPE )
            return true;
        return target.isAssignableFrom(source);
    }

    private static boolean isAssignableFrom(GenericsType[] a, GenericsType[] b) {
        if( a == null || b == null )
            return true;
        if( a.length != b.length )
            return false;
        for( int i = 0; i < a.length; i++ ) {
            if( a[i].isWildcard() )
                continue;
            if( !isEqual(a[i].getType(), b[i].getType()) )
                return false;
        }
        return true;
    }

    /**
     * Determine whether two types are equal.
     *
     * @param a
     * @param b
     */
    public static boolean isEqual(ClassNode a, ClassNode b) {
        if( a == null || b == null )
            return false;
        if( a.equals(b) )
            return true;
        return a.isResolved() && b.isResolved()
            && isEqual(a.getTypeClass(), b.getTypeClass())
            && isEqual(a.getGenericsTypes(), b.getGenericsTypes());
    }

    private static boolean isEqual(Class a, Class b) {
        a = normalize(a);
        b = normalize(b);
        return a.equals(b);
    }

    private static boolean isEqual(GenericsType[] a, GenericsType[] b) {
        if( a == null && b == null )
            return true;
        if( a == null || b == null || a.length != b.length )
            return false;
        for( int i = 0; i < a.length; i++ ) {
            if( !isEqual(a[i].getType(), b[i].getType()) )
                return false;
        }
        return true;
    }

    /**
     * Determine whether a type is a functional interface.
     *
     * @param type
     */
    public static boolean isFunctionalInterface(ClassNode type) {
        return type.getAnnotations().stream()
            .filter(an -> an.getClassNode().getName().equals(FunctionalInterface.class.getName()))
            .findFirst()
            .isPresent();
    }

    /**
     * Given a method node corresponding to a built-in constant, determine
     * whether the constant is a namespace.
     *
     * @param mn
     */
    public static boolean isNamespace(MethodNode mn) {
        var cn = mn.getReturnType();
        return cn.isResolved() && Namespace.class.isAssignableFrom(cn.getTypeClass());
    }

    /**
     * Determine whether a class is a namespace.
     *
     * @param cn
     */
    public static boolean isNamespace(ClassNode cn) {
        return cn.implementsInterface(ClassHelper.makeCached(Namespace.class));
    }

    /**
     * Get the display name of a type.
     *
     * @param type
     */
    public static String getName(ClassNode type) {
        if( type == null )
            return "?";

        if( type.isArray() )
            return getName(type.getComponentType());

        if( isFunctionalInterface(type) ) {
            var samTypeInfo = GenericsUtils.parameterizeSAM(type);
            return getName(samTypeInfo.getV1(), samTypeInfo.getV2());
        }

        if( type.isResolved() && type.getTypeClass() == Record.class ) {
            return recordName(type);
        }

        return typeName(type);
    }

    /**
     * Get the display name of a functional type.
     *
     * @param parameterTypes
     * @param returnType
     */
    public static String getName(ClassNode[] parameterTypes, ClassNode returnType) {
        var builder = new StringBuilder();

        builder.append('(');
        for( int i = 0; i < parameterTypes.length; i++ ) {
            if( i > 0 )
                builder.append(", ");
            builder.append(getName(parameterTypes[i]));
        }
        builder.append(')');
        builder.append(" -> ");
        builder.append(ClassHelper.VOID_TYPE.equals(returnType) ? "()" : getName(returnType));

        return builder.toString();
    }

    private static String recordName(ClassNode type) {
        if( type.getFields().isEmpty() )
            return "Record";

        var builder = new StringBuilder();

        builder.append("Record(\n");
        for( var fn : type.getFields() ) {
            builder.append("    ");
            builder.append(fn.getName());
            builder.append(": ");
            builder.append(typeName(fn.getType()));
            builder.append('\n');
        }
        builder.append(")");

        return builder.toString();
    }

    private static String typeName(ClassNode type) {
        var builder = new StringBuilder();

        var placeholder = type.isGenericsPlaceHolder();
        if( placeholder && type.getGenericsTypes() != null ) {
            builder.append(typeName(type.getGenericsTypes()[0].getType()));
        }
        else if( placeholder ) {
            builder.append(type.getUnresolvedName());
        }
        else if( type.getNodeMetaData(ASTNodeMarker.FULLY_QUALIFIED) != null ) {
            builder.append(type.getName());
        }
        else if( type.isResolved() ) {
            builder.append(getName(type.getTypeClass()));
        }
        else {
            builder.append(getName(type.getNameWithoutPackage()));
        }

        if( !placeholder && type.getGenericsTypes() != null ) {
            builder.append('<');
            genericsTypeNames(type.getGenericsTypes(), builder);
            builder.append('>');
        }

        if( type.getNodeMetaData(ASTNodeMarker.NULLABLE) != null )
            builder.append('?');

        return builder.toString();
    }

    private static void genericsTypeNames(GenericsType[] genericsTypes, StringBuilder builder) {
        for( int i = 0; i < genericsTypes.length; i++ ) {
            if( i > 0 )
                builder.append(", ");
            builder.append(getName(genericsTypes[i].getType()));
        }
    }

    public static String getName(Class type) {
        return getName(normalize(type).getSimpleName());
    }

    public static String getName(String name) {
        if( "Object".equals(name) )
            return "?";
        return name;
    }

    public static final Class Bag_TYPE          = nextflow.script.types.shim.Bag.class;
    public static final Class Boolean_TYPE      = Boolean.class;
    public static final Class Duration_TYPE     = nextflow.script.types.shim.Duration.class;
    public static final Class Float_TYPE        = nextflow.script.types.shim.Float.class;
    public static final Class Integer_TYPE      = nextflow.script.types.shim.Integer.class;
    public static final Class Iterable_TYPE     = nextflow.script.types.shim.Iterable.class;
    public static final Class List_TYPE         = nextflow.script.types.shim.List.class;
    public static final Class Map_TYPE          = nextflow.script.types.shim.Map.class;
    public static final Class MapEntry_TYPE     = nextflow.script.types.shim.Map.Entry.class;
    public static final Class MemoryUnit_TYPE   = nextflow.script.types.shim.MemoryUnit.class;
    public static final Class Path_TYPE         = nextflow.script.types.shim.Path.class;
    public static final Class Set_TYPE          = nextflow.script.types.shim.Set.class;
    public static final Class Record_TYPE       = Record.class;
    public static final Class String_TYPE       = nextflow.script.types.shim.String.class;

    private static final List<Class> STANDARD_TYPES = List.of(
        Bag_TYPE,
        Boolean_TYPE,
        Duration_TYPE,
        Float_TYPE,
        Integer_TYPE,
        Iterable_TYPE,
        List_TYPE,
        Map_TYPE,
        MapEntry_TYPE,
        MemoryUnit_TYPE,
        Path_TYPE,
        Record_TYPE,
        Set_TYPE,
        String_TYPE
    );

    /**
     * Mapping of Java types to Nextflow types, which
     * provide method signatures and documentation.
     */
    private static final Map<Class,Class> TYPE_ALIASES = Map.ofEntries(
        Map.entry(Bag.class,        Bag_TYPE),

        Map.entry(boolean.class,    Boolean_TYPE),

        Map.entry(Duration.class,   Duration_TYPE),

        Map.entry(BigDecimal.class, Float_TYPE),
        Map.entry(Double.class,     Float_TYPE),
        Map.entry(Float.class,      Float_TYPE),
        Map.entry(double.class,     Float_TYPE),
        Map.entry(float.class,      Float_TYPE),

        Map.entry(BigInteger.class, Integer_TYPE),
        Map.entry(Integer.class,    Integer_TYPE),
        Map.entry(Long.class,       Integer_TYPE),
        Map.entry(int.class,        Integer_TYPE),
        Map.entry(long.class,       Integer_TYPE),

        Map.entry(Collection.class, Iterable_TYPE),
        Map.entry(Iterable.class,   Iterable_TYPE),

        Map.entry(List.class,       List_TYPE),

        Map.entry(Map.class,        Map_TYPE),
        Map.entry(Map.Entry.class,  MapEntry_TYPE),

        Map.entry(MemoryUnit.class, MemoryUnit_TYPE),

        Map.entry(Path.class,       Path_TYPE),

        Map.entry(Set.class,        Set_TYPE),

        Map.entry(String.class,     String_TYPE),
        Map.entry(GString.class,    String_TYPE)
    );

    /**
     * Determine the canonical Nextflow type for a given class.
     *
     * @param type
     */
    public static Class normalize(Class type) {
        if( TYPE_ALIASES.containsKey(type) )
            return TYPE_ALIASES.get(type);
        var queue = new LinkedList<Class>();
        queue.add(type);
        while( !queue.isEmpty() ) {
            var c = queue.remove();
            if( c == null )
                continue;
            if( STANDARD_TYPES.contains(c) )
                return c;
            queue.add(c.getSuperclass());
            for( var ic : c.getInterfaces() )
                queue.add(ic);
        }
        return type;
    }

    public static ClassNode normalize(ClassNode cn) {
        if( cn == null || !cn.isResolved() )
            return cn;
        if( cn.getTypeClass() == ParamsMap.class || cn.getTypeClass() == Record.class )
            return cn;
        var result = ClassHelper.makeCached(normalize(cn.getTypeClass())).getPlainNodeReference();
        if( cn.getGenericsTypes() != null )
            result.setGenericsTypes(cn.getGenericsTypes());
        return result;
    }

}

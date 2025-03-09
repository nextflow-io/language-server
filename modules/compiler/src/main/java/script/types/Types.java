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
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.ClassNode;

public class Types {

    public static final List<ClassNode> DEFAULT_IMPORTS = List.of(
        new ClassNode(Channel.class),
        new ClassNode(Duration.class),
        new ClassNode(MemoryUnit.class),
        new ClassNode(Path.class)
    );

    /**
     * Determine whether a source type can be assigned to a target type.
     *
     * @see StaticTypeCheckingSupport.checkCompatibleAssignmentTypes()
     *
     * @param target
     * @param source
     */
    public static boolean isAssignableFrom(ClassNode target, ClassNode source) {
        if( target.equals(source) )
            return true;
        return isAssignableFrom(target.getTypeClass(), source.getTypeClass());
    }

    public static boolean isAssignableFrom(Class target, Class source) {
        target = Types.normalize(target);
        source = Types.normalize(source);
        if( target == Integer.class && source == Number.class )
            return false;
        if( target == Number.class && source == Integer.class )
            return true;
        return target.equals(source);
    }

    /**
     * Get the display name of a type.
     *
     * @param type
     */
    public static String getName(ClassNode type) {
        if( type.isArray() )
            return getName(type.getComponentType());

        var builder = new StringBuilder();

        var placeholder = type.isGenericsPlaceHolder();
        if( placeholder ) {
            builder.append(type.getUnresolvedName());
        }
        else if( !type.isPrimaryClassNode() ) {
            builder.append(getName(type.getTypeClass()));
        }
        else {
            var fullyQualified = type.getNodeMetaData("_FULLY_QUALIFIED") != null;
            var name = fullyQualified
                ? type.getName()
                : type.getNameWithoutPackage();
            builder.append(getName(name));
        }

        var genericsTypes = type.getGenericsTypes();
        if( !placeholder && genericsTypes != null ) {
            builder.append('<');
            for( int i = 0; i < genericsTypes.length; i++ ) {
                if( i > 0 )
                    builder.append(", ");
                builder.append(getName(genericsTypes[i].getType()));
            }
            builder.append('>');
        }
        return builder.toString();
    }

    public static String getName(Class type) {
        return getName(Types.normalize(type).getSimpleName());
    }

    public static String getName(String name) {
        if( "Object".equals(name) )
            return "?";
        return name;
    }

    private static final Map<Class,Class> NORMALIZED_TYPES = Map.ofEntries(
        Map.entry(boolean.class, Boolean.class),
        Map.entry(double.class,  Number.class),
        Map.entry(float.class,   Number.class),
        Map.entry(int.class,     Integer.class),
        Map.entry(long.class,    Integer.class),
        Map.entry(BigDecimal.class, Number.class),
        Map.entry(BigInteger.class, Number.class)
    );

    private static Class normalize(Class type) {
        return NORMALIZED_TYPES.getOrDefault(type, type);
    }

}

/*
 * Copyright 2013-2024, Seqera Labs
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

import java.lang.String;
import java.nio.file.Path;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;

public class Types {

    public static final List<ClassNode> TYPES = List.of(
        new ClassNode(Channel.class),
        new ClassNode(Duration.class),
        new ClassNode(MemoryUnit.class),
        new ClassNode(Path.class)
    );

    // TODO: normalize ClassNode -> String, rename shim types
    public static String normalize(String name) {
        return switch (name) {
            case "Object" -> "?";
            default -> name;
        };
        // if( "BiConsumer<K, V>".equals(name) )
        //     return "Closure<(K, V)>";
        // if( "BiFunction<R, E, R>".equals(name) )
        //     return "Closure<(R, E) -> R>";
        // if( "Consumer<E>".equals(name) )
        //     return "Closure<(E)>";
        // if( "Function<E, R>".equals(name) )
        //     return "Closure<(E) -> R>";
        // if( "Predicate<E>".equals(name) )
        //     return "Closure<(E) -> boolean>";
    }

}

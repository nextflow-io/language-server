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
package nextflow.script.ast;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;

/**
 * A type reference.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class TypeNode extends ASTNode {
    // reference to a standard type
    static class StandardType extends TypeNode {
        // TODO: class
    }

    // generic placeholder type
    static class GenericsType extends TypeNode {
        // TODO: generic name, resolved class
        // TODO: see Java8::configureTypeParameters()
    }

    // reference to a user-defined type (i.e. record type)
    static class CustomType extends TypeNode {
        // TODO: RecordNode?
    }

    TypeNode of(ClassNode cn) {
        // TODO: require type class
        // TODO: resolve generics types
        return null;
    }
}


// class TypeNode {
//     final Class type;
//     final List<Class> genericTypes;

//     public TypeNode(ClassNode cn) {
//         this.type = cn.getTypeClass();
//         if( cn.isUsingGenerics() ) {
//             this.genericTypes = Arrays.stream(cn.getGenericsTypes())
//                 .map(el -> el.getType().getTypeClass())
//                 .toList();
//         }
//         else {
//             this.genericTypes = null;
//         }
//     }
// }

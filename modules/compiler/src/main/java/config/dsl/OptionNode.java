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
package nextflow.config.dsl;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import nextflow.script.dsl.Description;

public final class OptionNode implements SchemaNode {
    private AnnotatedElement element;

    public OptionNode(AnnotatedElement element) {
        this.element = element;
    }

    @Override
    public String description() {
        var annot = element.getAnnotation(Description.class);
        return annot != null ? annot.value() : "";
    }

    public Class type() {
        if( element instanceof Field field ) {
            return field.getType();
        }
        if( element instanceof Method method ) {
            // use the return type if config option is not a directive
            var returnType = method.getReturnType();
            if( returnType != void.class )
                return returnType;
            // other use the type of the last parameter
            var paramTypes = method.getParameterTypes();
            if( paramTypes.length > 0 )
                return paramTypes[paramTypes.length - 1];
        }
        return null;
    }
}

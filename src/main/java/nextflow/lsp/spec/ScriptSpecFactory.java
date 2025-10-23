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
package nextflow.lsp.spec;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import nextflow.script.dsl.Description;
import nextflow.script.types.Duration;
import nextflow.script.types.MemoryUnit;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.stmt.EmptyStatement;

/**
 * Load script definitions from plugin specs.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptSpecFactory {
    
    public static List<MethodNode> fromDefinitions(List<Map> definitions, String type) {
        return definitions.stream()
            .filter(node -> type.equals(node.get("type")))
            .map((node) -> {
                var spec = (Map) node.get("spec");
                var name = (String) spec.get("name");
                return fromMethod(spec);
            })
            .toList();
    }

    private static MethodNode fromMethod(Map spec) {
        var name = (String) spec.get("name");
        var description = (String) spec.get("description");
        var returnType = fromType(spec.get("returnType"));
        var parameters = fromParameters((List<Map>) spec.get("parameters"));
        var method = new MethodNode(name, Modifier.PUBLIC, returnType, parameters, ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE);
        method.setHasNoRealSourcePosition(true);
        method.setDeclaringClass(ClassHelper.dynamicType());
        method.setSynthetic(true);
        if( description != null ) {
            var an = new AnnotationNode(ClassHelper.makeCached(Description.class));
            an.addMember("value", new ConstantExpression(description));
            method.addAnnotation(an);
        }
        return method;
    }

    private static Parameter[] fromParameters(List<Map> parameters) {
        return parameters.stream()
            .map((param) -> {
                var name = (String) param.get("name");
                var type = fromType(param.get("type"));
                return new Parameter(type, name);
            })
            .toArray(Parameter[]::new);
    }

    private static final Map<String,ClassNode> STANDARD_TYPES = Map.ofEntries(
        Map.entry("Boolean", ClassHelper.Boolean_TYPE),
        Map.entry("boolean", ClassHelper.Boolean_TYPE),
        Map.entry("Closure", ClassHelper.CLOSURE_TYPE),
        Map.entry("Duration", ClassHelper.makeCached(Duration.class)),
        Map.entry("Float", ClassHelper.Float_TYPE),
        Map.entry("float", ClassHelper.Float_TYPE),
        Map.entry("Integer", ClassHelper.Integer_TYPE),
        Map.entry("int", ClassHelper.Integer_TYPE),
        Map.entry("List", ClassHelper.LIST_TYPE),
        Map.entry("MemoryUnit", ClassHelper.makeCached(MemoryUnit.class)),
        Map.entry("Set", ClassHelper.SET_TYPE),
        Map.entry("String", ClassHelper.STRING_TYPE)
    );

    private static ClassNode fromType(Object type) {
        if( type instanceof String s ) {
            return STANDARD_TYPES.getOrDefault(s, ClassHelper.dynamicType());
        }
        if( type instanceof Map m ) {
            var name = (String) m.get("name");
            // TODO: type arguments
            return STANDARD_TYPES.getOrDefault(name, ClassHelper.dynamicType());
        }
        throw new IllegalStateException();
    }

}

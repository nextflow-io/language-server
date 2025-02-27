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
package nextflow.config.dsl;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.Map;

import nextflow.script.dsl.Description;

public record ScopeNode(
    String description,
    Map<String, String> options,
    Map<String, SchemaNode> scopes
) implements SchemaNode {

    /**
     * Create a scope node from a ConfigScope class.
     *
     * @param scope
     * @param description
     */
    public static ScopeNode of(Class<? extends ConfigScope> scope, String description) {
        var options = new HashMap<String, String>();
        var scopes = new HashMap<String, SchemaNode>();
        for( var field : scope.getDeclaredFields() ) {
            var name = field.getName();
            var type = field.getType();
            var placeholderName = field.getAnnotation(PlaceholderName.class);
            // fields annotated with @ConfigOption are config options
            if( field.getAnnotation(ConfigOption.class) != null ) {
                var desc = annotatedDescription(field, "");
                options.put(name, desc);
            }
            // fields of type ConfigScope are nested config scopes
            else if( ConfigScope.class.isAssignableFrom(type) ) {
                var desc = annotatedDescription(field, description);
                scopes.put(name, ScopeNode.of((Class<? extends ConfigScope>) type, desc));
            }
            // fields of type Map<String, ConfigScope> are placeholder scopes
            // (e.g. `azure.batch.pools.<name>`)
            else if( Map.class.isAssignableFrom(type) && placeholderName != null ) {
                var desc = annotatedDescription(field, description);
                var pt = (ParameterizedType)field.getGenericType();
                var valueType = (Class<? extends ConfigScope>)pt.getActualTypeArguments()[1];
                scopes.put(name, placeholderNode(desc, placeholderName.value(), valueType));
            }
        }
        for( var method : scope.getDeclaredMethods() ) {
            if( method.getAnnotation(ConfigOption.class) != null ) {
                var desc = annotatedDescription(method, "");
                options.put(method.getName(), desc);
            }
        }
        return new ScopeNode(description, options, scopes);
    }

    private static String annotatedDescription(AnnotatedElement el, String defaultValue) {
        var annot = el.getAnnotation(Description.class);
        return annot != null ? annot.value() : defaultValue;
    }

    private static PlaceholderNode placeholderNode(String description, String placeholderName, Class<? extends ConfigScope> valueType) {
        return new PlaceholderNode(description, placeholderName, ScopeNode.of(valueType, description));
    }

}

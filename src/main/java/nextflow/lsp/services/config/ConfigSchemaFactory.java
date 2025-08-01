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
package nextflow.lsp.services.config;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import groovy.json.JsonSlurper;
import groovy.lang.Closure;
import nextflow.config.schema.SchemaNode;
import nextflow.script.types.Duration;
import nextflow.script.types.MemoryUnit;
import org.codehaus.groovy.runtime.IOGroovyMethods;

/**
 * Load the config schema from the compiler as well as
 * the index file.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigSchemaFactory {

    public static SchemaNode.Scope load() {
        var scope = SchemaNode.ROOT;
        scope.children().putAll(fromIndex());
        return scope;
    }

    private static Map<String,SchemaNode> fromIndex() {
        try {
            var classLoader = ConfigSchemaFactory.class.getClassLoader();
            var resource = classLoader.getResourceAsStream("schema/index.json");
            var text = IOGroovyMethods.getText(resource);
            var json = new JsonSlurper().parseText(text);
            return fromChildren((Map<String,?>) json);
        }
        catch( IOException e ) {
            System.err.println("Failed to read index file: " + e.toString());
            return Collections.emptyMap();
        }
    }

    private static Map<String,SchemaNode> fromChildren(Map<String,?> children) {
        var entries = children.entrySet().stream()
            .map((entry) -> {
                var name = entry.getKey();
                var node = (Map<String,?>) entry.getValue();
                return Map.entry(name, fromNode(node));
            })
            .toArray(Map.Entry[]::new);
        return Map.ofEntries(entries);
    }

    private static SchemaNode fromNode(Map<String,?> node) {
        var type = (String) node.get("type");
        var spec = (Map<String,?>) node.get("spec");

        if( "Option".equals(type) )
            return fromOption(spec);

        if( "Placeholder".equals(type) )
            return fromPlaceholder(spec);

        if( "Scope".equals(type) )
            return fromScope(spec);

        throw new IllegalStateException();
    }

    private static SchemaNode.Option fromOption(Map<String,?> node) {
        var description = (String) node.get("description");
        var type = fromType(node.get("type"));
        return new SchemaNode.Option(description, type);
    }

    private static SchemaNode.Placeholder fromPlaceholder(Map<String,?> node) {
        var description = (String) node.get("description");
        var placeholderName = (String) node.get("placeholderName");
        var scope = fromScope((Map<String,?>) node.get("scope"));
        return new SchemaNode.Placeholder(description, placeholderName, scope);
    }

    private static SchemaNode.Scope fromScope(Map<String,?> node) {
        var description = (String) node.get("description");
        var children = fromChildren((Map<String,?>) node.get("children"));
        return new SchemaNode.Scope(description, children);
    }

    private static final Map<String,Class> STANDARD_TYPES = Map.ofEntries(
        Map.entry("Boolean", Boolean.class),
        Map.entry("Closure", Closure.class),
        Map.entry("Duration", Duration.class),
        Map.entry("Float", Float.class),
        Map.entry("Integer", Integer.class),
        Map.entry("List", List.class),
        Map.entry("MemoryUnit", MemoryUnit.class),
        Map.entry("String", String.class)
    );

    private static Class fromType(Object type) {
        if( type instanceof String s ) {
            return STANDARD_TYPES.getOrDefault(s, Object.class);
        }
        if( type instanceof Map m ) {
            var name = (String) m.get("name");
            // TODO: type arguments
            return STANDARD_TYPES.getOrDefault(name, Object.class);
        }
        throw new IllegalStateException();
    }

}

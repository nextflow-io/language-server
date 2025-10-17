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
import java.util.Set;

import groovy.json.JsonSlurper;
import groovy.lang.Closure;
import nextflow.config.spec.SpecNode;
import nextflow.script.types.Duration;
import nextflow.script.types.MemoryUnit;
import org.codehaus.groovy.runtime.IOGroovyMethods;

/**
 * Load config scopes from core definitions.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigSpecFactory {

    private static Map<String,SpecNode> defaultScopes = null;

    /**
     * Load config scopes from core definitions.
     */
    public static Map<String,SpecNode> defaultScopes() {
        if( defaultScopes == null ) {
            var scope = SpecNode.ROOT;
            scope.children().putAll(fromCoreDefinitions());
            defaultScopes = scope.children();
        }
        return defaultScopes;
    }

    private static Map<String,SpecNode> fromCoreDefinitions() {
        try {
            var classLoader = ConfigSpecFactory.class.getClassLoader();
            var resource = classLoader.getResourceAsStream("spec/definitions.json");
            var text = IOGroovyMethods.getText(resource);
            var definitions = (List<Map>) new JsonSlurper().parseText(text);
            return fromChildren(definitions);
        }
        catch( IOException e ) {
            System.err.println("Failed to read core definitions: " + e.toString());
            return Collections.emptyMap();
        }
    }

    private static Map<String,SpecNode> fromChildren(List<Map> children) {
        var entries = children.stream()
            .map((node) -> {
                var spec = (Map) node.get("spec");
                var name = (String) spec.get("name");
                return Map.entry(name, fromNode(node));
            })
            .toArray(Map.Entry[]::new);
        return Map.ofEntries(entries);
    }

    private static SpecNode fromNode(Map<String,?> node) {
        var type = (String) node.get("type");
        var spec = (Map) node.get("spec");

        if( "ConfigOption".equals(type) )
            return fromOption(spec);

        if( "ConfigPlaceholderScope".equals(type) )
            return fromPlaceholder(spec);

        if( "ConfigScope".equals(type) )
            return fromScope(spec);

        throw new IllegalStateException();
    }

    private static SpecNode.Option fromOption(Map<String,?> spec) {
        var description = (String) spec.get("description");
        var type = fromType(spec.get("type"));
        return new SpecNode.Option(description, type);
    }

    private static SpecNode.Placeholder fromPlaceholder(Map<String,?> spec) {
        var description = (String) spec.get("description");
        var placeholderName = (String) spec.get("placeholderName");
        var scope = fromScope((Map) spec.get("scope"));
        return new SpecNode.Placeholder(description, placeholderName, scope);
    }

    private static SpecNode.Scope fromScope(Map<String,?> spec) {
        var description = (String) spec.get("description");
        var children = fromChildren((List<Map>) spec.get("children"));
        return new SpecNode.Scope(description, children);
    }

    private static final Map<String,Class> STANDARD_TYPES = Map.ofEntries(
        Map.entry("Boolean", Boolean.class),
        Map.entry("boolean", Boolean.class),
        Map.entry("Closure", Closure.class),
        Map.entry("Duration", Duration.class),
        Map.entry("Float", Float.class),
        Map.entry("float", Float.class),
        Map.entry("Integer", Integer.class),
        Map.entry("int", Integer.class),
        Map.entry("List", List.class),
        Map.entry("MemoryUnit", MemoryUnit.class),
        Map.entry("Set", Set.class),
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

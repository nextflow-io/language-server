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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import nextflow.config.scopes.NextflowConfig;
import nextflow.config.scopes.ProcessConfig;
import nextflow.config.scopes.RootConfig;
import nextflow.script.dsl.Description;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.FeatureFlagDsl;
import nextflow.script.dsl.ProcessDsl;

public class ConfigSchema {

    public static final ScopeNode ROOT = scopeNode(RootConfig.class, "");

    /**
     * Get the schema node for a given config scope.
     *
     * @param names
     */
    public static SchemaNode getScope(List<String> names) {
        SchemaNode node = ROOT;
        for( var name : names ) {
            if( node instanceof ScopeNode sn )
                node = sn.scopes().get(name);
            else if( node instanceof PlaceholderNode pn )
                node = pn.scope();
            else
                return null;
        }
        return node;
    }

    /**
     * Get the description for a given config option.
     *
     * @param names
     */
    public static String getOption(List<String> names) {
        SchemaNode node = ROOT;
        for( int i = 0; i < names.size() - 1; i++ ) {
            var name = names.get(i);
            if( node instanceof ScopeNode sn )
                node = sn.scopes().get(name);
            else if( node instanceof PlaceholderNode pn )
                node = pn.scope();
            else
                return null;
        }
        var optionName = names.get(names.size() - 1);
        return node instanceof ScopeNode sn
            ? sn.options().get(optionName)
            : null;
    }

    private static ScopeNode scopeNode(Class<? extends ConfigScope> scope, String description) {
        if( scope.equals(NextflowConfig.class) )
            return nextflowScope(description);
        if( scope.equals(ProcessConfig.class) )
            return processScope(description);
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
                scopes.put(name, scopeNode((Class<? extends ConfigScope>) type, desc));
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
        return new PlaceholderNode(description, placeholderName, scopeNode(valueType, description));
    }

    /**
     * Derive `nextflow` config options from feature flags.
     *
     * @param description
     */
    private static ScopeNode nextflowScope(String description) {
        var enableOpts = new HashMap<String, String>();
        var previewOpts = new HashMap<String, String>();
        for( var field : FeatureFlagDsl.class.getDeclaredFields() ) {
            var fqName = field.getAnnotation(FeatureFlag.class).value();
            var names = fqName.split("\\.");
            var simpleName = names[names.length - 1];
            var desc = field.getAnnotation(Description.class).value();
            if( fqName.startsWith("nextflow.enable.") )
                enableOpts.put(simpleName, desc);
            else if( fqName.startsWith("nextflow.preview.") )
                previewOpts.put(simpleName, desc);
            else
                throw new IllegalArgumentException();
        }
        var scopes = Map.ofEntries(
            Map.entry("enable", new ScopeNode(description, enableOpts, Collections.emptyMap())),
            Map.entry("preview", new ScopeNode(description, previewOpts, Collections.emptyMap()))
        );
        return new ScopeNode(description, Collections.emptyMap(), scopes);
    }

    /**
     * Derive `process` config options from process directives.
     *
     * @param description
     */
    private static ScopeNode processScope(String description) {
        var options = new HashMap<String, String>();
        for( var method : ProcessDsl.DirectiveDsl.class.getDeclaredMethods() ) {
            var desc = method.getAnnotation(Description.class);
            if( desc != null ) {
                options.put(method.getName(), desc.value());
            }
        }
        return new ScopeNode(description, options, Collections.emptyMap());
    }

}

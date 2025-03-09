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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import nextflow.config.scopes.NextflowConfig;
import nextflow.config.scopes.ProcessConfig;
import nextflow.config.scopes.RootConfig;
import nextflow.script.dsl.Description;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.FeatureFlagDsl;
import nextflow.script.dsl.ProcessDsl;

public class ConfigSchema {

    public static final ScopeNode ROOT = rootScope();

    private static ScopeNode rootScope() {
        var result = ScopeNode.of(RootConfig.class, "");
        result.scopes().put("nextflow", nextflowScope(""));
        result.scopes().put("process", processScope(""));
        return result;
    }

    /**
     * Derive `nextflow` config options from feature flags.
     *
     * @param description
     */
    private static SchemaNode nextflowScope(String description) {
        var enableOpts = new HashMap<String, OptionNode>();
        var previewOpts = new HashMap<String, OptionNode>();
        for( var field : FeatureFlagDsl.class.getDeclaredFields() ) {
            var fqName = field.getAnnotation(FeatureFlag.class).value();
            var names = fqName.split("\\.");
            var simpleName = names[names.length - 1];
            if( fqName.startsWith("nextflow.enable.") )
                enableOpts.put(simpleName, new OptionNode(field));
            else if( fqName.startsWith("nextflow.preview.") )
                previewOpts.put(simpleName, new OptionNode(field));
            else
                throw new IllegalArgumentException();
        }
        var scopes = Map.ofEntries(
            Map.entry("enable", (SchemaNode) new ScopeNode(description, enableOpts, Collections.emptyMap())),
            Map.entry("preview", (SchemaNode) new ScopeNode(description, previewOpts, Collections.emptyMap()))
        );
        return new ScopeNode(description, Collections.emptyMap(), scopes);
    }

    /**
     * Derive `process` config options from process directives.
     *
     * @param description
     */
    private static SchemaNode processScope(String description) {
        var options = new HashMap<String, OptionNode>();
        for( var method : ProcessDsl.DirectiveDsl.class.getDeclaredMethods() )
            options.put(method.getName(), new OptionNode(method));
        return new ScopeNode(description, options, Collections.emptyMap());
    }

}

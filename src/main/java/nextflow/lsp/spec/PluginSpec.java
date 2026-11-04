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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import groovy.json.JsonSlurper;
import nextflow.config.spec.SpecNode;
import org.codehaus.groovy.ast.MethodNode;

public record PluginSpec(
    Map<String,SpecNode> configScopes,
    List<MethodNode> factories,
    List<MethodNode> functions,
    List<MethodNode> operators
) {

    /**
     * Create a plugin spec from the contents of a plugin's
     * `spec.json`, or an empty spec if it is null or blank.
     *
     * @param specJson
     */
    public static PluginSpec fromJson(String specJson) {
        var definitions = definitions(specJson);
        return new PluginSpec(
            ConfigSpecFactory.fromDefinitions(definitions),
            ScriptSpecFactory.fromDefinitions(definitions, "Factory"),
            ScriptSpecFactory.fromDefinitions(definitions, "Function"),
            ScriptSpecFactory.fromDefinitions(definitions, "Operator")
        );
    }

    private static List<Map> definitions(String specJson) {
        if( specJson == null || specJson.isBlank() )
            return Collections.emptyList();
        var spec = (Map) new JsonSlurper().parseText(specJson);
        return (List<Map>) spec.get("definitions");
    }

}

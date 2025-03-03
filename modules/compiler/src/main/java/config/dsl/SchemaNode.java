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

import java.util.List;

public sealed interface SchemaNode permits ScopeNode, PlaceholderNode {
    String description();

    /**
     * Get the schema node for a given config scope.
     *
     * @param names
     */
    default SchemaNode getScope(List<String> names) {
        SchemaNode node = this;
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
    default String getOption(List<String> names) {
        SchemaNode node = this;
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

}

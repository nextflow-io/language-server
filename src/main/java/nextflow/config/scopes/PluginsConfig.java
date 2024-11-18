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
package nextflow.config.scopes;

import nextflow.config.dsl.ConfigOption;
import nextflow.config.dsl.ConfigScope;

public class PluginsConfig implements ConfigScope {

    public PluginsConfig() {}

    @Override
    public String name() {
        return "plugins";
    }

    @Override
    public String description() {
        return """
            The `plugins` scope allows you to include plugins at runtime.

            [Read more](https://nextflow.io/docs/latest/plugins.html)
            """;
    }

    @ConfigOption("""
        The plugin id, can be a name (e.g. `nf-hello`) or a name with a version (e.g. `nf-hello@0.5.0`).
    """)
    public void id(String value) {
    }

}

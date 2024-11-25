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

public class DagConfig implements ConfigScope {

    public DagConfig() {}

    @Override
    public String name() {
        return "dag";
    }

    @Override
    public String description() {
        return """
            The `dag` scope controls the workflow diagram generated by Nextflow.

            [Read more](https://nextflow.io/docs/latest/reference/config.html#dag)
            """;
    }

    @ConfigOption("""
        When `true` enables the generation of the DAG file (default: `false`).
    """)
    public boolean enabled;

    @ConfigOption("""
        *Only supported by the HTML and Mermaid renderers.*

        Controls the maximum depth at which to render sub-workflows (default: no limit).
    """)
    public int depth;

    @ConfigOption("""
        *Only supported by the HTML and Mermaid renderers.*

        Controls the direction of the DAG, can be `'LR'` (left-to-right) or `'TB'` (top-to-bottom) (default: `'TB'`).
    """)
    public String direction;

    @ConfigOption("""
        Graph file name (default: `'dag-<timestamp>.html'`).
    """)
    public String file;

    @ConfigOption("""
        When `true` overwrites any existing DAG file with the same name (default: `false`).
    """)
    public boolean overwrite;

    @ConfigOption("""
        *Only supported by the HTML and Mermaid renderers.*

        When `false`, channel names are omitted, operators are collapsed, and empty workflow inputs are removed (default: `false`).
    """)
    public boolean verbose;

}
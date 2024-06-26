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
package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope

@CompileStatic
class DagConfig implements ConfigScope {

    DagConfig() {}

    @Override
    String name() {
        'dag'
    }

    @Override
    String description() {
        '''
        The `dag` scope controls the workflow diagram generated by Nextflow.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-dag)
        '''
    }

    @ConfigOption('''
        When `true` enables the generation of the DAG file (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        *Only supported by the HTML and Mermaid renderers.*

        Controls the maximum depth at which to render sub-workflows (default: no limit).
    ''')
    int depth

    @ConfigOption('''
        *Only supported by the HTML and Mermaid renderers.*

        Controls the direction of the DAG, can be `'LR'` (left-to-right) or `'TB'` (top-to-bottom) (default: `'TB'`).
    ''')
    String direction

    @ConfigOption('''
        Graph file name (default: `'dag-<timestamp>.html'`).
    ''')
    String file

    @ConfigOption('''
        When `true` overwrites any existing DAG file with the same name (default: `false`).
    ''')
    boolean overwrite

    @ConfigOption('''
        *Only supported by the HTML and Mermaid renderers.*

        When `false`, channel names are omitted, operators are collapsed, and empty workflow inputs are removed (default: `false`).
    ''')
    boolean verbose

}

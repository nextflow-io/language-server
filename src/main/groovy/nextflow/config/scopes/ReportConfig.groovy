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
class ReportConfig implements ConfigScope {

    ReportConfig() {}

    @Override
    String name() {
        'report'
    }

    @Override
    String description() {
        '''
        The `report` scope allows you to configure the workflow [execution report](https://nextflow.io/docs/latest/tracing.html#execution-report).

        [Read more](https://nextflow.io/docs/latest/config.html#scope-report)
        '''
    }

    @ConfigOption('''
        Enable the creation of the workflow execution report.
    ''')
    boolean enabled

    @ConfigOption('''
        The path of the created execution report file (default: `'report-<timestamp>.html'`).
    ''')
    String file

    @ConfigOption('''
        When `true` overwrites any existing report file with the same name.
    ''')
    boolean overwrite

}

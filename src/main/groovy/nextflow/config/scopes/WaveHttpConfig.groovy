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
import nextflow.util.Duration

@CompileStatic
class WaveHttpConfig implements ConfigScope {

    WaveHttpConfig() {}

    @Override
    String name() {
        'wave.httpClient'
    }

    @Override
    String description() {
        '''
        The `wave` scope provides advanced configuration for the use of [Wave containers](https://docs.seqera.io/wave).

        [Read more](https://nextflow.io/docs/latest/config.html#scope-wave)
        '''
    }

    @ConfigOption('''
        The connection timeout for the Wave HTTP client (default: `30s`).
    ''')
    Duration connectTime

}
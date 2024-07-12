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
class WaveRetryConfig implements ConfigScope {

    WaveRetryConfig() {}

    @Override
    String name() {
        'wave.retryPolicy'
    }

    @Override
    String description() {
        '''
        The `wave` scope provides advanced configuration for the use of [Wave containers](https://docs.seqera.io/wave).

        [Read more](https://nextflow.io/docs/latest/config.html#scope-wave)
        '''
    }

    @ConfigOption('''
        The initial delay when a failing HTTP request is retried (default: `150ms`).
    ''')
    Duration delay

    @ConfigOption('''
        The jitter factor used to randomly vary retry delays (default: `0.25`).
    ''')
    double jitter

    @ConfigOption('''
        The max number of attempts a failing HTTP request is retried (default: `5`).
    ''')
    int maxAttempts

    @ConfigOption('''
        The max delay when a failing HTTP request is retried (default: `90 seconds`).
    ''')
    Duration maxDelay

}

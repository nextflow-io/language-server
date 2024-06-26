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
class CondaConfig implements ConfigScope {

    CondaConfig() {}

    @Override
    String name() {
        'conda'
    }

    @Override
    String description() {
        '''
        The `conda` scope controls the creation of Conda environments by the Conda package manager.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-conda)
        '''
    }

    @ConfigOption('''
        Enable Conda execution (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        Defines the path where Conda environments are stored.
    ''')
    String cacheDir

    @ConfigOption('''
        Defines any extra command line options supported by the `conda create` command.
    ''')
    String createOptions

    @ConfigOption('''
        Defines the amount of time the Conda environment creation can last. The creation process is terminated when the timeout is exceeded (default: `20 min`).
    ''')
    Duration createTimeout

    @ConfigOption('''
        Uses the `mamba` binary instead of `conda` to create the Conda environments. For details see the [Mamba documentation](https://github.com/mamba-org/mamba).
    ''')
    boolean useMamba

    @ConfigOption('''
        Uses the `micromamba` binary instead of `conda` to create the Conda environments. For details see the [Micromamba documentation](https://mamba.readthedocs.io/en/latest/user_guide/micromamba.html).
    ''')
    boolean useMicromamba

}

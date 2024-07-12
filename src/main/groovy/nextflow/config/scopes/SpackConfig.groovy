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
class SpackConfig implements ConfigScope {

    SpackConfig() {}

    @Override
    String name() {
        'spack'
    }

    @Override
    String description() {
        '''
        The `spack` scope controls the creation of a Spack environment by the Spack package manager.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-spack)
        '''
    }

    @ConfigOption('''
        The path where Spack environments are stored.
    ''')
    String cacheDir

    @ConfigOption('''
        Enables checksum verification for source tarballs (default: `true`).
    ''')
    boolean checksum

    @ConfigOption('''
        The amount of time to wait for the Spack environment to be created before failing (default: `60 min`).
    ''')
    Duration createTimeout

    @ConfigOption('''
        The maximum number of parallel package builds (default: the number of available CPUs).
    ''')
    int parallelBuilds

}

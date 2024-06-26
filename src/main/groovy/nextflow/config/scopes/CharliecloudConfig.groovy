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
class CharliecloudConfig implements ConfigScope {

    CharliecloudConfig() {}

    @Override
    String name() {
        'charliecloud'
    }

    @Override
    String description() {
        '''
        The `charliecloud` scope controls how [Charliecloud](https://hpc.github.io/charliecloud/) containers are executed by Nextflow.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-charliecloud)
        '''
    }

    @ConfigOption('''
        The directory where remote Charliecloud images are stored. When using a computing cluster it must be a shared folder accessible to all compute nodes.
    ''')
    String cacheDir

    @ConfigOption('''
        Enable Charliecloud execution (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        Comma separated list of environment variable names to be included in the container environment.
    ''')
    String envWhitelist

    @ConfigOption('''
        The amount of time the Charliecloud pull can last, exceeding which the process is terminated (default: `20 min`).
    ''')
    Duration pullTimeout

    @ConfigOption('''
        The registry from where images are pulled. It should be only used to specify a private registry server. It should NOT include the protocol prefix i.e. `http://`.
    ''')
    String registry

    @ConfigOption('''
        This attribute can be used to provide any extra command line options supported by the `ch-run` command.
    ''')
    String runOptions

    @ConfigOption('''
        Mounts a path of your choice as the `/tmp` directory in the container. Use the special value `'auto'` to create a temporary directory each time a container is created.
    ''')
    String temp

    @ConfigOption('''
        Create a temporary squashFS container image in the process work directory instead of a folder.
    ''')
    boolean useSquash

    @ConfigOption('''
        Enable `writeFake` with charliecloud. This allows to run containers from storage in writeable mode using overlayfs.
    ''')
    boolean writeFake

}

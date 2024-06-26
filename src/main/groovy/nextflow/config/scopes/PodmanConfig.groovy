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
class PodmanConfig implements ConfigScope {

    PodmanConfig() {}

    @Override
    String name() {
        'podman'
    }

    @Override
    String description() {
        '''
        The `podman` scope controls how [Podman](https://podman.io/) containers are executed by Nextflow.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-podman)
        '''
    }

    @ConfigOption('''
        Enable Podman execution (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        This attribute can be used to provide any option supported by the Podman engine i.e. `podman [OPTIONS]`.
    ''')
    String engineOptions

    @ConfigOption('''
        Comma separated list of environment variable names to be included in the container environment.
    ''')
    String envWhitelist

    @ConfigOption('''
        Add the specified flags to the volume mounts e.g. `'ro,Z'`.
    ''')
    String mountFlags

    @ConfigOption('''
        The registry from where container images are pulled. It should be only used to specify a private registry server. It should NOT include the protocol prefix i.e. `http://`.
    ''')
    String registry

    @ConfigOption('''
        Clean-up the container after the execution (default: `true`).
    ''')
    boolean remove

    @ConfigOption('''
        This attribute can be used to provide any extra command line options supported by the `podman run` command.
    ''')
    String runOptions

    @ConfigOption('''
        Mounts a path of your choice as the `/tmp` directory in the container. Use the special value `'auto'` to create a temporary directory each time a container is created.
    ''')
    String temp

}

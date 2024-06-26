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
class DockerConfig implements ConfigScope {

    DockerConfig() {}

    @Override
    String name() {
        'docker'
    }

    @Override
    String description() {
        '''
        The `docker` scope controls how [Docker](https://www.docker.com) containers are executed by Nextflow.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-docker)
        '''
    }

    @ConfigOption('''
        Enable Docker execution (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        This attribute can be used to provide any option supported by the Docker engine i.e. `docker [OPTIONS]`.
    ''')
    String engineOptions

    @ConfigOption('''
        Comma separated list of environment variable names to be included in the container environment.
    ''')
    String envWhitelist

    @ConfigOption('''
        Fix ownership of files created by the Docker container.
    ''')
    boolean fixOwnership

    @ConfigOption('''
        Use command line options removed since Docker 1.10.0 (default: `false`).
    ''')
    boolean legacy

    @ConfigOption('''
        Add the specified flags to the volume mounts e.g. `'ro,Z'`.
    ''')
    String mountFlags

    @ConfigOption('''
        The registry from where Docker images are pulled. It should be only used to specify a private registry server. It should NOT include the protocol prefix i.e. `http://`.
    ''')
    String registry

    @ConfigOption('''
        Clean up the container after the execution (default: `true`). See the [Docker documentation](https://docs.docker.com/engine/reference/run/#clean-up---rm) for details.
    ''')
    boolean remove

    @ConfigOption('''
        This attribute can be used to provide any extra command line options supported by the `docker run` command. See the [Docker documentation](https://docs.docker.com/engine/reference/run/) for details.
    ''')
    String runOptions

    @ConfigOption('''
        Executes Docker run command as `sudo` (default: `false`).
    ''')
    boolean sudo

    @ConfigOption('''
        Mounts a path of your choice as the `/tmp` directory in the container. Use the special value `'auto'` to create a temporary directory each time a container is created.
    ''')
    String temp

    @ConfigOption('''
        Allocates a pseudo-tty (default: `false`).
    ''')
    boolean tty

}

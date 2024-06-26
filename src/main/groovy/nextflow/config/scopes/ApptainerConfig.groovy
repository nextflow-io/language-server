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
class ApptainerConfig implements ConfigScope {

    ApptainerConfig() {}

    @Override
    String name() {
        'apptainer'
    }

    @Override
    String description() {
        '''
        The `apptainer` scope controls how [Apptainer](https://apptainer.org) containers are executed by Nextflow.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-apptainer)
        '''
    }

    @ConfigOption('''
        When `true` Nextflow automatically mounts host paths in the executed container. It requires the `user bind control` feature to be enabled in your Apptainer installation (default: `true`).
    ''')
    boolean autoMounts

    @ConfigOption('''
        The directory where remote Apptainer images are stored. When using a computing cluster it must be a shared folder accessible to all compute nodes.
    ''')
    String cacheDir

    @ConfigOption('''
        Enable Apptainer execution (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        This attribute can be used to provide any option supported by the Apptainer engine i.e. `apptainer [OPTIONS]`.
    ''')
    String engineOptions

    @ConfigOption('''
        Comma separated list of environment variable names to be included in the container environment.
    ''')
    String envWhitelist

    @ConfigOption('''
        Pull the Apptainer image with http protocol (default: `false`).
    ''')
    boolean noHttps

    @ConfigOption('''
        When enabled, OCI (and Docker) container images are pulled and converted to the SIF format by the Apptainer run command, instead of Nextflow (default: `false`).
    ''')
    boolean ociAutoPull

    @ConfigOption('''
        The amount of time the Apptainer pull can last, exceeding which the process is terminated (default: `20 min`).
    ''')
    Duration pullTimeout

    @ConfigOption('''
        The registry from where Docker images are pulled. It should be only used to specify a private registry server. It should NOT include the protocol prefix i.e. `http://`.
    ''')
    String registry

    @ConfigOption('''
        This attribute can be used to provide any extra command line options supported by `apptainer exec`.
    ''')
    String runOptions

}

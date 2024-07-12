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
import nextflow.util.MemoryUnit

@CompileStatic
class GoogleBatchConfig implements ConfigScope {

    GoogleBatchConfig() {}

    @Override
    String name() {
        'google.batch'
    }

    @Override
    String description() {
        '''
        The `google` scope allows you to configure the interactions with Google Cloud, including Google Cloud Batch and Google Cloud Storage.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-google)
        '''
    }

    @ConfigOption('''
        The set of allowed locations for VMs to be provisioned (default: no restriction).

        [Read more](https://cloud.google.com/batch/docs/reference/rest/v1/projects.locations.jobs#locationpolicy)
    ''')
    List<String> allowedLocations

    @ConfigOption('''
        The size of the virtual machine boot disk, e.g `50.GB` (default: none).
    ''')
    MemoryUnit bootDiskSize

    @ConfigOption('''
        The minimum CPU Platform, e.g. `'Intel Skylake'` (default: none).

        [Read more](https://cloud.google.com/compute/docs/instances/specify-min-cpu-platform#specifications)
    ''')
    String cpuPlatform

    @ConfigOption('''
        Max number of execution attempts of a job interrupted by a Compute Engine spot reclaim event (default: `5`).
    ''')
    int maxSpotAttempts

    @ConfigOption('''
        The URL of an existing network resource to which the VM will be attached.
    ''')
    String network

    @ConfigOption('''
        The Google service account email to use for the pipeline execution. If not specified, the default Compute Engine service account for the project will be used.

        [Read more](https://www.nextflow.io/docs/latest/google.html#credentials)
    ''')
    String serviceAccountEmail

    @ConfigOption('''
        When `true`, enables the usage of *spot* virtual machines (default: `false`).
    ''')
    boolean spot

    @ConfigOption('''
        The URL of an existing subnetwork resource in the network to which the VM will be attached.
    ''')
    String subnetwork

    @ConfigOption('''
        When `true`, the VM will *not* be provided with a public IP address, and only contain an internal IP.
    ''')
    boolean usePrivateAddress

}
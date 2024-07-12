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
class FusionConfig implements ConfigScope {

    FusionConfig() {}

    @Override
    String name() {
        'fusion'
    }

    @Override
    String description() {
        '''
        The `fusion` scope provides advanced configuration for the use of the [Fusion file system](https://docs.seqera.io/fusion).

        [Read more](https://nextflow.io/docs/latest/config.html#scope-fusion)
        '''
    }

    @ConfigOption('''
        Enable/disable the use of Fusion file system.
    ''')
    boolean enabled

    @ConfigOption('''
        The maximum size of the local cache used by the Fusion client.
    ''')
    MemoryUnit cacheSize

    @ConfigOption('''
        The URL from where the container layer provisioning the Fusion client is downloaded.
    ''')
    String containerConfigUrl

    @ConfigOption('''
        When `true` the access credentials required by the underlying object storage are exported to the task execution environment.
    ''')
    boolean exportStorageCredentials

    @ConfigOption('''
        The level of logging emitted by the Fusion client.
    ''')
    String logLevel

    @ConfigOption('''
        Where the logging output is written. 
    ''')
    String logOutput

    @ConfigOption('''
        Enables the use of privileged containers when using Fusion (default: `true`).
    ''')
    boolean privileged

    @ConfigOption('''
        The pattern that determines how tags are applied to files created via the Fusion client (default: `[.command.*|.exitcode|.fusion.*](nextflow.io/metadata=true),[*](nextflow.io/temporary=true)`). Set to `false` to disable tags.
    ''')
    String tags

}

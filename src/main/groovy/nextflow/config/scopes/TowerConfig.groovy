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
class TowerConfig implements ConfigScope {

    TowerConfig() {}

    @Override
    String name() {
        'tower'
    }

    @Override
    String description() {
        '''
        The `tower` scope controls the settings for the [Seqera Platform](https://seqera.io) (formerly Tower Cloud).

        [Read more](https://nextflow.io/docs/latest/config.html#scope-tower)
        '''
    }

    @ConfigOption('''
        The unique access token for your Seqera Platform account.
    ''')
    String accessToken

    @ConfigOption('''
        Enable workflow monitoring with Seqera Platform (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        The endpoint of your Seqera Platform instance (default: `https://api.cloud.seqera.io`).
    ''')
    String endpoint

    @ConfigOption('''
        The workspace ID in Seqera Platform in which to save the run (default: the launching user's personal workspace).
    ''')
    String workspaceId

}

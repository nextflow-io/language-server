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
class UnscopedConfig implements ConfigScope {

    UnscopedConfig() {}

    @Override
    String name() {
        ''
    }

    @Override
    String description() {
        '''
        Miscellaneous settings that do not have a dedicated scope.

        [Read more](https://nextflow.io/docs/latest/config.html#miscellaneous)
        '''
    }

    @ConfigOption('''
        If `true`, on a successful completion of a run all files in *work* directory are automatically deleted.
    ''')
    boolean cleanup

    @ConfigOption('''
        If `true`, dump task hash keys in the log file, for debugging purposes. Equivalent to the `-dump-hashes` option of the `run` command.
    ''')
    boolean dumpHashes

    @ConfigOption('''
        If `true`, enable the use of previously cached task executions. Equivalent to the `-resume` option of the `run` command.
    ''')
    boolean resume

    @ConfigOption('''
        Defines the pipeline work directory. Equivalent to the `-work-dir` option of the `run` command.
    ''')
    String workDir

}

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
class Manifest implements ConfigScope {

    Manifest() {}

    @Override
    String name() {
        'manifest'
    }

    @Override
    String description() {
        '''
        The `manifest` scope allows you to define some metadata that is useful when publishing or running your pipeline.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-manifest)
        '''
    }

    @ConfigOption('''
        Project author name (use a comma to separate multiple names).
    ''')
    String author

    @ConfigOption('''
        Git repository default branch (default: `master`).
    ''')
    String defaultBranch

    @ConfigOption('''
        Free text describing the workflow project.
    ''')
    String description

    @ConfigOption('''
        Project related publication DOI identifier.
    ''')
    String doi

    @ConfigOption('''
        Project home page URL.
    ''')
    String homePage

    @ConfigOption('''
        Project main script (default: `main.nf`).
    ''')
    String mainScript

    @ConfigOption('''
        Project short name.
    ''')
    String name

    @ConfigOption('''
        Minimum required Nextflow version.
    ''')
    String nextflowVersion

    @ConfigOption('''
        Pull submodules recursively from the Git repository.
    ''')
    boolean recurseSubmodules

    @ConfigOption('''
        Project version number.
    ''')
    String version

}

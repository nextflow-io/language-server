package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope

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

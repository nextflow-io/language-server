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

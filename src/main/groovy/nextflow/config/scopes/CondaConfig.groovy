package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope
import nextflow.util.Duration

@CompileStatic
class CondaConfig implements ConfigScope {

    CondaConfig() {}

    @Override
    String name() {
        'conda'
    }

    @Override
    String description() {
        '''
        The `conda` scope controls the creation of Conda environments by the Conda package manager.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-conda)
        '''
    }

    @ConfigOption('''
        Enable Conda execution (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        Defines the path where Conda environments are stored.
    ''')
    String cacheDir

    @ConfigOption('''
        Defines any extra command line options supported by the `conda create` command.
    ''')
    String createOptions

    @ConfigOption('''
        Defines the amount of time the Conda environment creation can last. The creation process is terminated when the timeout is exceeded (default: `20 min`).
    ''')
    Duration createTimeout

    @ConfigOption('''
        Uses the `mamba` binary instead of `conda` to create the Conda environments. For details see the [Mamba documentation](https://github.com/mamba-org/mamba).
    ''')
    boolean useMamba

    @ConfigOption('''
        Uses the `micromamba` binary instead of `conda` to create the Conda environments. For details see the [Micromamba documentation](https://mamba.readthedocs.io/en/latest/user_guide/micromamba.html).
    ''')
    boolean useMicromamba

}

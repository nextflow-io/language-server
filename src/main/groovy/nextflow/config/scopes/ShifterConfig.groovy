package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope

@CompileStatic
class ShifterConfig implements ConfigScope {

    ShifterConfig() {}

    @Override
    String name() {
        'shifter'
    }

    @Override
    String description() {
        '''
        The `shifter` scope controls how [Shifter](https://docs.nersc.gov/programming/shifter/overview/) containers are executed by Nextflow.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-shifter)
        '''
    }

    @ConfigOption('''
        Enable Shifter execution (default: `false`).
    ''')
    boolean enabled

}

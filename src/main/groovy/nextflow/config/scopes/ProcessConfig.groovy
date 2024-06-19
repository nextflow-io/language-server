package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigScope

@CompileStatic
class ProcessConfig implements ConfigScope {

    ProcessConfig() {}

    @Override
    String name() {
        'process'
    }

    @Override
    String description() {
        '''
        The `process` scope allows you to specify default directives for processes in your pipeline.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-process)
        '''
    }

}

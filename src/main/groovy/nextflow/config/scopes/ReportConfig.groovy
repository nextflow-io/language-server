package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope

@CompileStatic
class ReportConfig implements ConfigScope {

    ReportConfig() {}

    @Override
    String name() {
        'report'
    }

    @Override
    String description() {
        '''
        The `report` scope allows you to configure the workflow [execution report](https://nextflow.io/docs/latest/tracing.html#execution-report).

        [Read more](https://nextflow.io/docs/latest/config.html#scope-report)
        '''
    }

    @ConfigOption('''
        Enable the creation of the workflow execution report.
    ''')
    boolean enabled

    @ConfigOption('''
        The path of the created execution report file (default: `'report-<timestamp>.html'`).
    ''')
    String file

    @ConfigOption('''
        When `true` overwrites any existing report file with the same name.
    ''')
    boolean overwrite

}

package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope
import nextflow.util.MemoryUnit

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

    @ConfigOption('''
        The `clusterOptions` directive allows the usage of any native configuration option accepted by your cluster submit command. You can use it to request non-standard resources or use settings that are specific to your cluster and not supported out of the box by Nextflow.

        [Read more](https://nextflow.io/docs/latest/process.html#clusteroptions)
    ''')
    String clusterOptions

    @ConfigOption('''
        The `conda` directive allows for the definition of the process dependencies using the [Conda](https://conda.io) package manager.

        [Read more](https://nextflow.io/docs/latest/process.html#conda)
    ''')
    String conda

    @ConfigOption('''
        The `container` directive allows you to execute the process script in a container.

        [Read more](https://nextflow.io/docs/latest/process.html#container)
    ''')
    String container

    @ConfigOption('''
        The `containerOptions` directive allows you to specify any container execution option supported by the underlying container engine (ie. Docker, Singularity, etc). This can be useful to provide container settings only for a specific process.

        [Read more](https://nextflow.io/docs/latest/process.html#containeroptions)
    ''')
    String containerOptions

    @ConfigOption('''
        The `cpus` directive allows you to define the number of (logical) CPUs required by each task.

        [Read more](https://nextflow.io/docs/latest/process.html#cpus)
    ''')
    Integer cpus

    @ConfigOption('''
        The `debug` directive allows you to print the process standard output to Nextflow\'s standard output, i.e. the console. By default this directive is disabled.

        [Read more](https://nextflow.io/docs/latest/process.html#debug)
    ''')
    boolean debug

    @ConfigOption('''
        The `errorStrategy` directive allows you to define how an error condition is managed by the process. By default when an error status is returned by the executed script, the process stops immediately. This in turn forces the entire pipeline to terminate.

        [Read more](https://nextflow.io/docs/latest/process.html#errorstrategy)
    ''')
    String errorStrategy

    @ConfigOption('''
        The `executor` defines the underlying system where tasks are executed.

        [Read more](https://nextflow.io/docs/latest/process.html#executor)
    ''')
    String executor

    @ConfigOption('''
        The `maxErrors` directive allows you to specify the maximum number of times a process can fail when using the `retry` or `ignore` error strategy. By default this directive is disabled.

        [Read more](https://nextflow.io/docs/latest/process.html#maxerrors)
    ''')
    int maxErrors = -1

    @ConfigOption('''
        The `maxRetries` directive allows you to define the maximum number of times a task can be retried when using the `retry` error strategy. By default only one retry is allowed.

        [Read more](https://nextflow.io/docs/latest/process.html#maxretries)
    ''')
    int maxRetries = 1

    @ConfigOption('''
        The `memory` directive allows you to define how much memory is required by each task. Can be a string (e.g. `\'8 GB\'`) or a memory unit (e.g. `8.GB`).

        [Read more](https://nextflow.io/docs/latest/process.html#memory)
    ''')
    MemoryUnit memory

    @ConfigOption('''
        The `tag` directive allows you to associate each process execution with a custom label, so that it will be easier to identify in the log file or in a report.

        [Read more](https://nextflow.io/docs/latest/process.html#tag)
    ''')
    String tag

}

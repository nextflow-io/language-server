package nextflow.lsp.services

class ConfigDefs {

    static final List<List<String>> SCOPES = [
        [
            'executor',
            '''
            The `executor` scope controls various executor behaviors.

            [Read more](https://nextflow.io/docs/latest/config.html#scope-executor)
            '''
        ],
        [
            'process',
            '''
            The `process` scope allows you to specify default directives for processes in your pipeline.

            [Read more](https://nextflow.io/docs/latest/config.html#scope-process)
            '''
        ]
    ]

    static final List<List<String>> OPTIONS = [
        [
            'executor.dumpInterval',
            '''
            Determines how often to log the executor status (default: `5min`).
            '''
        ],
        [
            'executor.exitReadTimeout',
            '''
            Determines how long to wait before returning an error status when a process is terminated but the `.exitcode` file does not exist or is empty (default: `270 sec`). Used only by grid executors.
            '''
        ],
        [
            'executor.jobName',
            '''
            Determines the name of jobs submitted to the underlying cluster executor e.g. `executor.jobName = { "$task.name - $task.hash" }`. Make sure the resulting job name matches the validation constraints of the underlying batch scheduler. This setting is only support by the following executors: Bridge, Condor, Flux, HyperQueue, Lsf, Moab, Nqsii, Oar, Pbs, PbsPro, Sge, Slurm and Google Batch.
            '''
        ],
        [
            'executor.killBatchSize',
            '''
            Determines the number of jobs that can be killed in a single command execution (default: `100`).
            '''
        ],
        [
            'executor.pollInterval',
            '''
            Determines how often to check for process termination. Default varies for each executor (see below).
            '''
        ],
        [
            'executor.queueGlobalStatus',
            '''
            Determines how job status is retrieved. When `false` only the queue associated with the job execution is queried. When `true` the job status is queried globally i.e. irrespective of the submission queue (default: `false`).
            '''
        ],
        [
            'executor.queueSize',
            '''
            The number of tasks the executor will handle in a parallel manner. A queue size of zero corresponds to no limit. Default varies for each executor (see below).
            '''
        ],
        [
            'executor.queueStatInterval',
            '''
            Determines how often to fetch the queue status from the scheduler (default: `1min`). Used only by grid executors.
            '''
        ],
        [
            'executor.submitRateLimit',
            '''
            Determines the max rate of job submission per time unit, for example `'10sec'` (10 jobs per second) or `'50/2min'` (50 jobs every 2 minutes) (default: unlimited).
            '''
        ],
        [
            'process.clusterOptions',
            '''
            The `clusterOptions` directive allows the usage of any native configuration option accepted by your cluster submit command. You can use it to request non-standard resources or use settings that are specific to your cluster and not supported out of the box by Nextflow.

            [Read more](https://nextflow.io/docs/latest/process.html#clusteroptions)
            '''
        ],
        [
            'process.container',
            '''
            The `container` directive allows you to execute the process script in a container.

            [Read more](https://nextflow.io/docs/latest/process.html#container)
            '''
        ],
        [
            'process.containerOptions',
            '''
            The `containerOptions` directive allows you to specify any container execution option supported by the underlying container engine (ie. Docker, Singularity, etc). This can be useful to provide container settings only for a specific process.

            [Read more](https://nextflow.io/docs/latest/process.html#containeroptions)
            '''
        ],
        [
            'process.cpus',
            '''
            The `cpus` directive allows you to define the number of (logical) CPUs required by each task.

            [Read more](https://nextflow.io/docs/latest/process.html#cpus)
            '''
        ],
        [
            'process.debug',
            '''
            The `debug` directive allows you to print the process standard output to Nextflow\'s standard output, i.e. the console. By default this directive is disabled.

            [Read more](https://nextflow.io/docs/latest/process.html#debug)
            '''
        ],
        [
            'process.errorStrategy',
            '''
            The `errorStrategy` directive allows you to define how an error condition is managed by the process. By default when an error status is returned by the executed script, the process stops immediately. This in turn forces the entire pipeline to terminate.

            [Read more](https://nextflow.io/docs/latest/process.html#errorstrategy)
            '''
        ],
        [
            'process.maxErrors',
            '''
            The `maxErrors` directive allows you to specify the maximum number of times a process can fail when using the `retry` or `ignore` error strategy. By default this directive is disabled.

            [Read more](https://nextflow.io/docs/latest/process.html#maxerrors)
            '''
        ],
        [
            'process.maxRetries',
            '''
            The `maxRetries` directive allows you to define the maximum number of times a task can be retried when using the `retry` error strategy. By default only one retry is allowed.

            [Read more](https://nextflow.io/docs/latest/process.html#maxretries)
            '''
        ],
        [
            'process.memory',
            '''
            The `memory` directive allows you to define how much memory is required by each task. Can be a string (e.g. `\'8 GB\'`) or a memory unit (e.g. `8.GB`).

            [Read more](https://nextflow.io/docs/latest/process.html#memory)
            '''
        ],
        [
            'process.tag',
            '''
            The `tag` directive allows you to associate each process execution with a custom label, so that it will be easier to identify in the log file or in a report.

            [Read more](https://nextflow.io/docs/latest/process.html#tag)
            '''
        ]
    ]

}

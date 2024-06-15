package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope
import nextflow.util.Duration

class ExecutorConfig implements ConfigScope {

    ExecutorConfig() {}

    @Override
    String name() {
        'executor'
    }

    @Override
    String description() {
        '''
        The `executor` scope controls various executor behaviors.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-executor)
        '''
    }

    @ConfigOption('''
        Determines how often to log the executor status (default: `5 min`).
    ''')
    Duration dumpInterval = Duration.of('5 min')

    @ConfigOption('''
        Determines how long to wait before returning an error status when a process is terminated but the `.exitcode` file does not exist or is empty (default: `270 sec`). Used only by grid executors.
    ''')
    Duration exitReadTimeout = Duration.of('270 sec')

    @ConfigOption('''
        Determines the name of jobs submitted to the underlying cluster executor e.g. `executor.jobName = { "$task.name - $task.hash" }`. Make sure the resulting job name matches the validation constraints of the underlying batch scheduler. This setting is only support by the following executors: Bridge, Condor, Flux, HyperQueue, Lsf, Moab, Nqsii, Oar, Pbs, PbsPro, Sge, Slurm and Google Batch.
    ''')
    String jobName

    @ConfigOption('''
        Determines the number of jobs that can be killed in a single command execution (default: `100`).
    ''')
    int killBatchSize = 100

    @ConfigOption('''
        Determines how often to check for process termination. Default varies for each executor.
    ''')
    Duration pollInterval

    @ConfigOption('''
        Determines how job status is retrieved. When `false` only the queue associated with the job execution is queried. When `true` the job status is queried globally i.e. irrespective of the submission queue (default: `false`).
    ''')
    boolean queueGlobalStatus

    @ConfigOption('''
        The number of tasks the executor will handle in a parallel manner. A queue size of zero corresponds to no limit. Default varies for each executor.
    ''')
    Integer queueSize

    @ConfigOption('''
        Determines how often to fetch the queue status from the scheduler (default: `1 min`). Used only by grid executors.
    ''')
    Duration queueStatInterval = Duration.of('1 min')

    @ConfigOption('''
        Determines the max rate of job submission per time unit, for example `'10sec'` (10 jobs per second) or `'50/2min'` (50 jobs every 2 minutes) (default: unlimited).
    ''')
    String submitRateLimit

}

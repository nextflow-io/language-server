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
import nextflow.util.Duration
import nextflow.util.MemoryUnit

@CompileStatic
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
        *Used only by the SLURM, LSF, PBS and PBS Pro executors.*

        Specify the project or organisation account that should be charged for running the pipeline jobs.
    ''')
    String account

    @ConfigOption('''
        *Used only by the local executor.*

        The maximum number of CPUs made available by the underlying system.
    ''')
    int cpus

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
        *Used only by the local executor.*

        The maximum amount of memory made available by the underlying system.
    ''')
    MemoryUnit memory

    @ConfigOption('''
        The name of the executor to be used (default: `local`).
    ''')
    String name

    @ConfigOption('''
        *Used only by the {ref}`slurm-executor` executor.*

        When `true`, specifies memory allocations for SLURM jobs as `--mem-per-cpu <task.memory / task.cpus>` instead of `--mem <task.memory>`.
    ''')
    boolean perCpuMemAllocation

    @ConfigOption('''
        Specifies Platform LSF *per-job* memory limit mode.
    ''')
    boolean perJobMemLimit

    @ConfigOption('''
        Specifies Platform LSF *per-task* memory reserve mode.
    ''')
    boolean perTaskReserve

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

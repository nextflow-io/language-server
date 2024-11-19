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
package nextflow.config.scopes;

import nextflow.config.dsl.ConfigOption;
import nextflow.config.dsl.ConfigScope;
import nextflow.script.types.Duration;
import nextflow.script.types.MemoryUnit;

public class ExecutorConfig implements ConfigScope {

    public ExecutorConfig() {}

    @Override
    public String name() {
        return "executor";
    }

    @Override
    public String description() {
        return """
            The `executor` scope controls various executor behaviors.

            [Read more](https://nextflow.io/docs/latest/reference/config.html#executor)
            """;
    }

    @ConfigOption("""
        *Used only by the SLURM, LSF, PBS and PBS Pro executors.*

        Specify the project or organisation account that should be charged for running the pipeline jobs.
    """)
    public String account;

    @ConfigOption("""
        *Used only by the local executor.*

        The maximum number of CPUs made available by the underlying system.
    """)
    public int cpus;

    @ConfigOption("""
        Determines how often to log the executor status (default: `5 min`).
    """)
    public Duration dumpInterval;

    @ConfigOption("""
        *Used only by grid executors.*

        Determines how long to wait for the `.exitcode` file to be created after the task has completed, before returning an error status (default: `270 sec`).
    """)
    public Duration exitReadTimeout;

    @ConfigOption("""
        *Used only by grid executors and Google Batch.*

        Determines the name of jobs submitted to the underlying cluster executor:
        ```nextflow
        executor.jobName = { "$task.name - $task.hash" }
        ```
    """)
    public String jobName;

    @ConfigOption("""
        Determines the number of jobs that can be killed in a single command execution (default: `100`).
    """)
    public int killBatchSize = 100;

    @ConfigOption("""
        *Used only by the local executor.*

        The maximum amount of memory made available by the underlying system.
    """)
    public MemoryUnit memory;

    @ConfigOption("""
        The name of the executor to be used (default: `local`).
    """)
    public String name;

    @ConfigOption("""
        *Used only by the [SLURM](https://nextflow.io/docs/latest/executor.html#slurm) executor.*

        When `true`, memory allocations for SLURM jobs are specified as `--mem-per-cpu <task.memory / task.cpus>` instead of `--mem <task.memory>`.
    """)
    public boolean perCpuMemAllocation;

    @ConfigOption("""
        *Used only by the [LSF](https://nextflow.io/docs/latest/executor.html#lsf) executor.*

        Enables the *per-job* memory limit mode for LSF jobs.
    """)
    public boolean perJobMemLimit;

    @ConfigOption("""
        *Used only by the [LSF](https://nextflow.io/docs/latest/executor.html#lsf) executor.*

        Enables the *per-task* memory reserve mode for LSF jobs.
    """)
    public boolean perTaskReserve;

    @ConfigOption("""
        Determines how often to check for process termination. Default varies for each executor.
    """)
    public Duration pollInterval;

    @ConfigOption("""
        Determines how job status is retrieved. When `false` only the queue associated with the job execution is queried. When `true` the job status is queried globally i.e. irrespective of the submission queue (default: `false`).
    """)
    public boolean queueGlobalStatus;

    @ConfigOption("""
        The number of tasks the executor will handle in a parallel manner. A queue size of zero corresponds to no limit. Default varies for each executor.
    """)
    public Integer queueSize;

    @ConfigOption("""
        *Used only by grid executors.*

        Determines how often to fetch the queue status from the scheduler (default: `1 min`).
    """)
    public Duration queueStatInterval;

    @ConfigOption("""
        Determines the max rate of job submission per time unit, for example `'10sec'` (10 jobs per second) or `'50/2min'` (50 jobs every 2 minutes) (default: unlimited).
    """)
    public String submitRateLimit;

}

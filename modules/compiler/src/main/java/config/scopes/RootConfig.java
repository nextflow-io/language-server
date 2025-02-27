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

import java.nio.file.Path;

import nextflow.config.dsl.ConfigOption;
import nextflow.config.dsl.ConfigScope;
import nextflow.script.dsl.Description;

public class RootConfig implements ConfigScope {

    // OPTIONS

    @ConfigOption
    @Description("""
        If `true`, on a successful completion of a run all files in *work* directory are automatically deleted.
    """)
    public boolean cleanup;

    @ConfigOption
    @Description("""
        If `true`, dump task hash keys in the log file, for debugging purposes. Equivalent to the `-dump-hashes` option of the `run` command.
    """)
    public boolean dumpHashes;

    @ConfigOption
    @Description("""
        Defines the pipeline output directory. Equivalent to the `-output-dir` option of the `run` command.
    """)
    public Path outputDir;

    @ConfigOption
    @Description("""
        If `true`, enable the use of previously cached task executions. Equivalent to the `-resume` option of the `run` command.
    """)
    public boolean resume;

    @ConfigOption
    @Description("""
        The pipeline work directory. Equivalent to the `-work-dir` option of the `run` command.
    """)
    public Path workDir;

    // SCOPES

    @Description("""
        The `apptainer` scope controls how [Apptainer](https://apptainer.org) containers are executed by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#apptainer)
    """)
    public ApptainerConfig apptainer;

    @Description("""
        The `aws` scope controls the interactions with AWS, including AWS Batch and S3.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#aws)
    """)
    public AwsConfig aws;

    @Description("""
        The `azure` scope allows you to configure the interactions with Azure, including Azure Batch and Azure Blob Storage.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#azure)
    """)
    public AzureConfig azure;

    @Description("""
        The `charliecloud` scope controls how [Charliecloud](https://hpc.github.io/charliecloud/) containers are executed by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#charliecloud)
    """)
    public CharliecloudConfig charliecloud;

    @Description("""
        The `conda` scope controls the creation of Conda environments by the Conda package manager.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#conda)
    """)
    public CondaConfig conda;

    @Description("""
        The `dag` scope controls the workflow diagram generated by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#dag)
    """)
    public DagConfig dag;

    @Description("""
        The `docker` scope controls how [Docker](https://www.docker.com) containers are executed by Nextflow.
    
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#docker)
    """)
    public DockerConfig docker;

    @Description("""
        The `env` scope allows you to define environment variables that will be exported into the environment where workflow tasks are executed.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#env)
    """)
    public EnvConfig env;

    @Description("""
        The `executor` scope controls various executor behaviors.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#executor)
    """)
    public ExecutorConfig executor;

    @Description("""
        The `fusion` scope provides advanced configuration for the use of the [Fusion file system](https://docs.seqera.io/fusion).
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#fusion)
    """)
    public FusionConfig fusion;

    @Description("""
        The `google` scope allows you to configure the interactions with Google Cloud, including Google Cloud Batch and Google Cloud Storage.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#google)
    """)
    public GoogleConfig google;

    @Description("""
        The `k8s` scope controls the deployment and execution of workflow applications in a Kubernetes cluster.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#k8s)
    """)
    public K8sConfig k8s;

    @Description("""
        The `mail` scope controls the mail server used to send email notifications.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#mail)
    """)
    public MailConfig mail;

    @Description("""
        The `manifest` scope allows you to define some metadata that is useful when publishing or running your pipeline.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#manifest)
    """)
    public Manifest manifest;

    public NextflowConfig nextflow;

    @Description("""
        The `params` scope allows you to define parameters that will be accessible in the pipeline script.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#params)
    """)
    public ParamsConfig params;

    @Description("""
        The `plugins` scope allows you to include plugins at runtime.
    
        [Read more](https://nextflow.io/docs/latest/plugins.html)
    """)
    public PluginsConfig plugins;

    @Description("""
        The `podman` scope controls how [Podman](https://podman.io/) containers are executed by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#podman)
    """)
    public PodmanConfig podman;

    @Description("""
        The `process` scope allows you to specify default directives for processes in your pipeline.
    
        [Read more](https://nextflow.io/docs/latest/config.html#process-configuration)
    """)
    public ProcessConfig process;

    @Description("""
        The `profiles` block allows you to define configuration profiles. A profile is a set of configuration settings that can be applied at runtime with the `-profile` command line option.
    
        [Read more](https://nextflow.io/docs/latest/config.html#config-profiles)
    """)
    public ProfilesConfig profiles;

    @Description("""
        The `report` scope allows you to configure the workflow [execution report](https://nextflow.io/docs/latest/tracing.html#execution-report).
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#report)
    """)
    public ReportConfig report;

    @Description("""
        The `shifter` scope controls how [Shifter](https://docs.nersc.gov/programming/shifter/overview/) containers are executed by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#shifter)
    """)
    public ShifterConfig shifter;

    @Description("""
        The `singularity` scope controls how [Singularity](https://sylabs.io/singularity/) containers are executed by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#singularity)
    """)
    public SingularityConfig singularity;

    @Description("""
        The `spack` scope controls the creation of a Spack environment by the Spack package manager.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#spack)
    """)
    public SpackConfig spack;

    @Description("""
        The `timeline` scope controls the execution timeline report generated by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#timeline)
    """)
    public TimelineConfig timeline;

    @Description("""
        The `tower` scope controls the settings for the [Seqera Platform](https://seqera.io) (formerly Tower Cloud).
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#tower)
    """)
    public TowerConfig tower;

    @Description("""
        The `trace` scope controls the layout of the execution trace file generated by Nextflow.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#trace)
    """)
    public TraceConfig trace;

    @Description("""
        The `wave` scope provides advanced configuration for the use of [Wave containers](https://docs.seqera.io/wave).
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#wave)
    """)
    public WaveConfig wave;

    @Description("""
        The `workflow` scope provides workflow execution options.
    
        [Read more](https://nextflow.io/docs/latest/reference/config.html#workflow)
    """)
    public WorkflowConfig workflow;

}

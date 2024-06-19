package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope
import nextflow.util.Duration

@CompileStatic
class SingularityConfig implements ConfigScope {

    SingularityConfig() {}

    @Override
    String name() {
        'singularity'
    }

    @Override
    String description() {
        '''
        The `singularity` scope controls how [Singularity](https://sylabs.io/singularity/) containers are executed by Nextflow.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-singularity)
        '''
    }

    @ConfigOption('''
        When `true` Nextflow automatically mounts host paths in the executed container. It requires the `user bind control` feature to be enabled in your Singularity installation (default: `true`).
    ''')
    boolean autoMounts

    @ConfigOption('''
        The directory where remote Singularity images are stored. When using a compute cluster, it must be a shared folder accessible to all compute nodes.
    ''')
    String cacheDir

    @ConfigOption('''
        Enable Singularity execution (default: `false`).
    ''')
    boolean enabled

    @ConfigOption('''
        This attribute can be used to provide any option supported by the Singularity engine i.e. `singularity [OPTIONS]`.
    ''')
    String engineOptions

    @ConfigOption('''
        Comma separated list of environment variable names to be included in the container environment.
    ''')
    String envWhitelist

    @ConfigOption('''
        Pull the Singularity image with http protocol (default: `false`).
    ''')
    boolean noHttps

    @ConfigOption('''
        When enabled, OCI (and Docker) container images are pull and converted to a SIF image file format implicitly by the Singularity run command, instead of Nextflow (default: `false`).
    ''')
    boolean ociAutoPull

    @ConfigOption('''
        Enable OCI-mode, that allows running native OCI compliant container image with Singularity using `crun` or `runc` as low-level runtime (default: `false`).
    ''')
    boolean ociMode

    @ConfigOption('''
        The amount of time the Singularity pull can last, after which the process is terminated (default: `20 min`).
    ''')
    Duration pullTimeout

    @ConfigOption('''
        The registry from where Docker images are pulled. It should be only used to specify a private registry server. It should NOT include the protocol prefix i.e. `http://`.
    ''')
    String registry

    @ConfigOption('''
        This attribute can be used to provide any extra command line options supported by `singularity exec`.
    ''')
    String runOptions

}

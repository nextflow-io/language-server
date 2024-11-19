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

public class SingularityConfig implements ConfigScope {

    public SingularityConfig() {}

    @Override
    public String name() {
        return "singularity";
    }

    @Override
    public String description() {
        return """
            The `singularity` scope controls how [Singularity](https://sylabs.io/singularity/) containers are executed by Nextflow.

            [Read more](https://nextflow.io/docs/latest/reference/config.html#singularity)
            """;
    }

    @ConfigOption("""
        When `true` Nextflow automatically mounts host paths in the executed container. It requires the `user bind control` feature to be enabled in your Singularity installation (default: `true`).
    """)
    public boolean autoMounts;

    @ConfigOption("""
        The directory where remote Singularity images are stored. When using a compute cluster, it must be a shared folder accessible to all compute nodes.
    """)
    public String cacheDir;

    @ConfigOption("""
        Enable Singularity execution (default: `false`).
    """)
    public boolean enabled;

    @ConfigOption("""
        This attribute can be used to provide any option supported by the Singularity engine i.e. `singularity [OPTIONS]`.
    """)
    public String engineOptions;

    @ConfigOption("""
        Comma separated list of environment variable names to be included in the container environment.
    """)
    public String envWhitelist;

    @ConfigOption("""
        Pull the Singularity image with http protocol (default: `false`).
    """)
    public boolean noHttps;

    @ConfigOption("""
        When enabled, OCI (and Docker) container images are pull and converted to a SIF image file format implicitly by the Singularity run command, instead of Nextflow (default: `false`).
    """)
    public boolean ociAutoPull;

    @ConfigOption("""
        Enable OCI-mode, that allows running native OCI compliant container image with Singularity using `crun` or `runc` as low-level runtime (default: `false`).
    """)
    public boolean ociMode;

    @ConfigOption("""
        The amount of time the Singularity pull can last, after which the process is terminated (default: `20 min`).
    """)
    public Duration pullTimeout;

    @ConfigOption("""
        The registry from where Docker images are pulled. It should be only used to specify a private registry server. It should NOT include the protocol prefix i.e. `http://`.
    """)
    public String registry;

    @ConfigOption("""
        This attribute can be used to provide any extra command line options supported by `singularity exec`.
    """)
    public String runOptions;

}

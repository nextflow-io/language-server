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
import nextflow.util.Duration;

public class CondaConfig implements ConfigScope {

    public CondaConfig() {}

    @Override
    public String name() {
        return "conda";
    }

    @Override
    public String description() {
        return """
            The `conda` scope controls the creation of Conda environments by the Conda package manager.

            [Read more](https://nextflow.io/docs/latest/config.html#scope-conda)
            """;
    }

    @ConfigOption("""
        Enable Conda execution (default: `false`).
    """)
    public boolean enabled;

    @ConfigOption("""
        The path where Conda environments are stored.
    """)
    public String cacheDir;

    @ConfigOption("""
        Extra command line options to append to the `conda create` command.
    """)
    public String createOptions;

    @ConfigOption("""
        The amount of time to wait for the Conda environment to be created before failing (default: `20 min`).
    """)
    public Duration createTimeout;

    @ConfigOption("""
        When `true`, use `mamba` instead of `conda` to create the Conda environments.

        [Read more](https://github.com/mamba-org/mamba)
    """)
    public boolean useMamba;

    @ConfigOption("""
        When `true`, use `micromamba` instead of `conda` to create the Conda environments.

        [Read more](https://mamba.readthedocs.io/en/latest/user_guide/micromamba.html)
    """)
    public boolean useMicromamba;

}

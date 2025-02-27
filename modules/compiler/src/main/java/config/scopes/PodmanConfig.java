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
import nextflow.script.dsl.Description;

public class PodmanConfig implements ConfigScope {

    @ConfigOption
    @Description("""
        Enable Podman execution (default: `false`).
    """)
    public boolean enabled;

    @ConfigOption
    @Description("""
        This attribute can be used to provide any option supported by the Podman engine i.e. `podman [OPTIONS]`.
    """)
    public String engineOptions;

    @ConfigOption
    @Description("""
        Comma separated list of environment variable names to be included in the container environment.
    """)
    public String envWhitelist;

    @ConfigOption
    @Description("""
        Add the specified flags to the volume mounts e.g. `'ro,Z'`.
    """)
    public String mountFlags;

    @ConfigOption
    @Description("""
        The registry from where container images are pulled. It should be only used to specify a private registry server. It should NOT include the protocol prefix i.e. `http://`.
    """)
    public String registry;

    @ConfigOption
    @Description("""
        Clean-up the container after the execution (default: `true`).
    """)
    public boolean remove;

    @ConfigOption
    @Description("""
        This attribute can be used to provide any extra command line options supported by the `podman run` command.
    """)
    public String runOptions;

    @ConfigOption
    @Description("""
        Mounts a path of your choice as the `/tmp` directory in the container. Use the special value `'auto'` to create a temporary directory each time a container is created.
    """)
    public String temp;

}

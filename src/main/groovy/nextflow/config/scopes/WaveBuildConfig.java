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

public class WaveBuildConfig implements ConfigScope {

    public WaveBuildConfig() {}

    @Override
    public String name() {
        return "wave.build";
    }

    @Override
    public String description() {
        return """
            The `wave` scope provides advanced configuration for the use of [Wave containers](https://docs.seqera.io/wave).

            [Read more](https://nextflow.io/docs/latest/config.html#scope-wave)
            """;
    }

    @ConfigOption("""
        The container repository where images built by Wave are uploaded.
    """)
    public String repository;

    @ConfigOption("""
        The container repository used to cache image layers built by the Wave service.
    """)
    public String cacheRepository;

}

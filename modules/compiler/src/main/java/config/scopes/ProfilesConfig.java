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

import nextflow.config.dsl.ConfigScope;

public class ProfilesConfig implements ConfigScope {

    public ProfilesConfig() {}

    @Override
    public String name() {
        return "profiles";
    }

    @Override
    public String description() {
        return """
            The `profiles` block allows you to define configuration profiles. A profile is a set of configuration settings that can be applied at runtime with the `-profile` command line option.

            [Read more](https://nextflow.io/docs/latest/config.html#config-profiles)
            """;
    }

    // NOTE: only used to provide completions, hover hints

}

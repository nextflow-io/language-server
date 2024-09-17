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

public class ShifterConfig implements ConfigScope {

    public ShifterConfig() {}

    @Override
    public String name() {
        return "shifter";
    }

    @Override
    public String description() {
        return """
            The `shifter` scope controls how [Shifter](https://docs.nersc.gov/programming/shifter/overview/) containers are executed by Nextflow.

            [Read more](https://nextflow.io/docs/latest/config.html#scope-shifter)
            """;
    }

    @ConfigOption("""
        Enable Shifter execution (default: `false`).
    """)
    public boolean enabled;

}

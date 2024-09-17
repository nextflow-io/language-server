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

public class ProcessConfig implements ConfigScope {

    public ProcessConfig() {}

    @Override
    public String name() {
        return "process";
    }

    @Override
    public String description() {
        return """
            The `process` scope allows you to specify default directives for processes in your pipeline.

            [Read more](https://nextflow.io/docs/latest/config.html#scope-process)
            """;
    }

    // NOTE: process config options are inferred from ProcessDsl

}
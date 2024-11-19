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

import groovy.lang.Closure;
import nextflow.config.dsl.ConfigOption;
import nextflow.config.dsl.ConfigScope;

public class WorkflowConfig implements ConfigScope {

    public WorkflowConfig() {}

    @Override
    public String name() {
        return "workflow";
    }

    @Override
    public String description() {
        return """
            The `workflow` scope provides workflow execution options.

            [Read more](https://nextflow.io/docs/latest/reference/config.html#workflow)
            """;
    }

    @ConfigOption("""
        When `true`, the pipeline will exit with a non-zero exit code if any failed tasks are ignored using the `ignore` error strategy.
    """)
    public boolean failOnIgnore;

    @ConfigOption("""
        Specify a closure that will be invoked at the end of a workflow run (including failed runs).
    """)
    public Closure onComplete;

    @ConfigOption("""
        Specify a closure that will be invoked if a workflow run is terminated.
    """)
    public Closure onError;

}

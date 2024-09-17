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

public class AzureRetryConfig implements ConfigScope {

    public AzureRetryConfig() {}

    @Override
    public String name() {
        return "azure.retryPolicy";
    }

    @Override
    public String description() {
        return """
            The `azure` scope allows you to configure the interactions with Azure, including Azure Batch and Azure Blob Storage.

            [Read more](https://nextflow.io/docs/latest/config.html#scope-azure)
            """;
    }

    @ConfigOption("""
        Delay when retrying failed API requests (default: `500ms`).
    """)
    public Duration delay;

    @ConfigOption("""
        Jitter value when retrying failed API requests (default: `0.25`).
    """)
    public double jitter;

    @ConfigOption("""
        Max attempts when retrying failed API requests (default: `10`).
    """)
    public int maxAttempts;

    @ConfigOption("""
        Max delay when retrying failed API requests (default: `90s`).
    """)
    public Duration maxDelay;

}
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

public class GoogleConfig implements ConfigScope {

    public GoogleConfig() {}

    @Override
    public String name() {
        return "google";
    }

    @Override
    public String description() {
        return """
            The `google` scope allows you to configure the interactions with Google Cloud, including Google Cloud Batch and Google Cloud Storage.

            [Read more](https://nextflow.io/docs/latest/reference/config.html#google)
            """;
    }

    @ConfigOption("""
        When `true`, the given Google Cloud project ID is used as the billing project for storage access (default: `false`). Required when accessing data from *requester pays enabled* buckets.

        [Read more](https://cloud.google.com/storage/docs/requester-pays)
    """)
    public boolean enableRequesterPaysBuckets;

    @ConfigOption("""
        The HTTP connection timeout for Cloud Storage API requests (default: `'60s'`).
    """)
    public Duration httpConnectTimeout;

    @ConfigOption("""
        The HTTP read timeout for Cloud Storage API requests (default: `'60s'`).
    """)
    public Duration httpReadTimeout;

    @ConfigOption("""
        The Google Cloud location where jobs are executed (default: `us-central1`).
    """)
    public String location;

    @ConfigOption("""
        The Google Cloud project ID to use for pipeline execution.
    """)
    public String project;

}

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

public class AwsConfig implements ConfigScope {

    public AwsConfig() {}

    @Override
    public String name() {
        return "aws";
    }

    @Override
    public String description() {
        return """
            The `aws` scope controls the interactions with AWS, including AWS Batch and S3.

            [Read more](https://nextflow.io/docs/latest/reference/config.html#aws)
            """;
    }

    @ConfigOption("""
        AWS account access key.
    """)
    public String accessKey;

    @ConfigOption("""
        AWS profile from `~/.aws/credentials`.
    """)
    public String profile;

    @ConfigOption("""
        AWS region (e.g. `us-east-1`).
    """)
    public String region;

    @ConfigOption("""
        AWS account secret key.
    """)
    public String secretKey;

}

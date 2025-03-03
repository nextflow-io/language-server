/*
 * Copyright 2024-2025, Seqera Labs
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
import nextflow.script.types.Duration;

public class AzureStorageConfig implements ConfigScope {

    @ConfigOption
    @Description("""
        The blob storage account name.
    """)
    public String accountName;

    @ConfigOption
    @Description("""
        The blob storage account key.
    """)
    public String accountKey;

    @ConfigOption
    @Description("""
        The blob storage shared access signature (SAS) token, which can be provided instead of an account key.
    """)
    public String sasToken;

    @ConfigOption
    @Description("""
        The duration of the SAS token generated by Nextflow when the `sasToken` option is *not* specified (default: `48h`).
    """)
    public Duration tokenDuration;

}

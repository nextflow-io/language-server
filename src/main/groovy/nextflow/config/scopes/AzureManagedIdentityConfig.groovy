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
package nextflow.config.scopes

import groovy.transform.CompileStatic
import nextflow.config.dsl.ConfigOption
import nextflow.config.dsl.ConfigScope

@CompileStatic
class AzureManagedIdentityConfig implements ConfigScope {

    AzureManagedIdentityConfig() {}

    @Override
    String name() {
        'azure.managedIdentity'
    }

    @Override
    String description() {
        '''
        The `azure` scope allows you to configure the interactions with Azure, including Azure Batch and Azure Blob Storage.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-azure)
        '''
    }

    @ConfigOption('''
        The client ID for an Azure managed identity.

        [Read more](https://nextflow.io/docs/latest/azure.html#managed-identities)
    ''')
    String clientId

    @ConfigOption('''
        When `true`, use the system-assigned managed identity to authenticate Azure resources.

        [Read more](https://nextflow.io/docs/latest/azure.html#managed-identities)
    ''')
    boolean system

    @ConfigOption('''
        The Azure tenant ID.
    ''')
    String tenantId

}
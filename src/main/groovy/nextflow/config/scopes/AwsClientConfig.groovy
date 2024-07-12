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
import nextflow.util.Duration
import nextflow.util.MemoryUnit

@CompileStatic
class AwsClientConfig implements ConfigScope {

    AwsClientConfig() {}

    @Override
    String name() {
        'aws.client'
    }

    @Override
    String description() {
        '''
        The `aws` scope controls the interactions with AWS, including AWS Batch and S3.

        [Read more](https://nextflow.io/docs/latest/config.html#scope-aws)
        '''
    }

    @ConfigOption('''
        Allow the access of public S3 buckets without providing AWS credentials. Any service that does not accept unsigned requests will return a service access error.
    ''')
    boolean anonymous

    @ConfigOption('''
        Specify predefined bucket permissions, also known as *canned ACL*. Can be one of `Private`, `PublicRead`, `PublicReadWrite`, `AuthenticatedRead`, `LogDeliveryWrite`, `BucketOwnerRead`, `BucketOwnerFullControl`, or `AwsExecRead`.
        
        [Read more](https://docs.aws.amazon.com/AmazonS3/latest/userguide/acl-overview.html#canned-acl)
    ''')
    String s3Acl

    @ConfigOption('''
        The amount of time to wait (in milliseconds) when initially establishing a connection before timing out.
    ''')
    int connectionTimeout

    @ConfigOption('''
        The AWS S3 API entry point e.g. `https://s3-us-west-1.amazonaws.com`. The endpoint must include the protocol prefix e.g. `https://`.
    ''')
    String endpoint

    @ConfigOption('''
        The maximum number of allowed open HTTP connections.
    ''')
    int maxConnections

    @ConfigOption('''
        The maximum number of retry attempts for failed retryable requests.
    ''')
    int maxErrorRetry

    @ConfigOption('''
        The protocol (i.e. HTTP or HTTPS) to use when connecting to AWS.
    ''')
    String protocol

    @ConfigOption('''
        The proxy host to connect through.
    ''')
    String proxyHost

    @ConfigOption('''
        The port on the proxy host to connect through.
    ''')
    int proxyPort

    @ConfigOption('''
        The user name to use when connecting through a proxy.
    ''')
    String proxyUsername

    @ConfigOption('''
        The password to use when connecting through a proxy.
    ''')
    String proxyPassword

    @ConfigOption('''
        Enable the requester pays feature for S3 buckets.
    ''')
    boolean requesterPays

    @ConfigOption('''
        Enable the use of path-based access model that is used to specify the address of an object in S3-compatible storage systems.
    ''')
    boolean s3PathStyleAccess

    @ConfigOption('''
        The name of the signature algorithm to use for signing requests made by the client.
    ''')
    String signerOverride

    @ConfigOption('''
        The size hint (in bytes) for the low level TCP send buffer.
    ''')
    int socketSendBufferSizeHint

    @ConfigOption('''
        The size hint (in bytes) for the low level TCP receive buffer.
    ''')
    int socketRecvBufferSizeHint

    @ConfigOption('''
        The amount of time to wait (in milliseconds) for data to be transferred over an established, open connection before the connection is timed out.
    ''')
    int socketTimeout

    @ConfigOption('''
        The S3 server side encryption to be used when saving objects on S3, either `AES256` or `aws:kms` values are allowed.
    ''')
    String storageEncryption

    @ConfigOption('''
        The AWS KMS key Id to be used to encrypt files stored in the target S3 bucket.
    ''')
    String storageKmsKeyId

    @ConfigOption('''
        The HTTP user agent header passed with all HTTP requests.
    ''')
    String userAgent

    @ConfigOption('''
        The size of a single part in a multipart upload (default: `100 MB`).
    ''')
    MemoryUnit uploadChunkSize

    @ConfigOption('''
        The maximum number of upload attempts after which a multipart upload returns an error (default: `5`).
    ''')
    int uploadMaxAttempts

    @ConfigOption('''
        The maximum number of threads used for multipart upload.
    ''')
    int uploadMaxThreads

    @ConfigOption('''
        The time to wait after a failed upload attempt to retry the part upload (default: `500ms`).
    ''')
    Duration uploadRetrySleep

    @ConfigOption('''
        The S3 storage class applied to stored objects, one of \\[`STANDARD`, `STANDARD_IA`, `ONEZONE_IA`, `INTELLIGENT_TIERING`\\] (default: `STANDARD`).
    ''')
    String uploadStorageClass

}

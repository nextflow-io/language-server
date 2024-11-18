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

import java.util.List;
import java.util.Map;

import nextflow.config.dsl.ConfigOption;
import nextflow.config.dsl.ConfigScope;
import nextflow.util.Duration;

public class K8sConfig implements ConfigScope {

    public K8sConfig() {}

    @Override
    public String name() {
        return "k8s";
    }

    @Override
    public String description() {
        return """
            The `k8s` scope controls the deployment and execution of workflow applications in a Kubernetes cluster.

            [Read more](https://nextflow.io/docs/latest/reference/config.html#k8s)
            """;
    }

    @ConfigOption("""
        When `true`, host paths are automatically mounted into the task pods (default: `false`). Only intended for development purposes when using a single node.
    """)
    public boolean autoMountHostPaths;

    @ConfigOption("""
        Whether to use Kubernetes `Pod` or `Job` resource type to carry out Nextflow tasks (default: `Pod`).
    """)
    public String computeResourceType;

    @ConfigOption("""
        The Kubernetes [configuration context](https://kubernetes.io/docs/tasks/access-application-cluster/configure-access-multiple-clusters/) to use.
    """)
    public String context;

    @ConfigOption("""
        When `true`, both the pod CPU `request` and `limit` are set to the `cpus` directive, otherwise only the `request` is set (default: `false`).
    """)
    public boolean cpuLimits;

    // @ConfigOption("""
    //     When `true`, the pod spec for each task is saved to `.command.yaml` in the task directory (default: `false`).
    // """)
    // public boolean debugYaml;

    @ConfigOption("""
        When `true`, includes the hostname of each task in the execution trace (default: `false`).
    """)
    public boolean fetchNodeName;

    @ConfigOption("""
        The FUSE device plugin to be used when enabling Fusion in unprivileged mode (default: `['nextflow.io/fuse': 1]`).
    """)
    public Map fuseDevicePlugin;

    @ConfigOption("""
        The Kubernetes HTTP client request connection timeout e.g. `'60s'`.
    """)
    public Duration httpConnectTimeout;

    @ConfigOption("""
        The Kubernetes HTTP client request connection read timeout e.g. `'60s'`.
    """)
    public Duration httpReadTimeout;

    @ConfigOption("""
        The path where the workflow is launched and the user data is stored (default: `<volume-claim-mount-path>/<user-name>`). Must be a path in a shared K8s persistent volume.
    """)
    public String launchDir;

    @ConfigOption("""
        The maximum number of retries for failed requests by the Kubernetes HTTP client (default: 4).
    """)
    public int maxErrorRetry;

    @ConfigOption("""
        The Kubernetes namespace to use (default: `default`).
    """)
    public String namespace;

    @ConfigOption("""
        Allows the definition of one or more pod configuration options such as environment variables, config maps, secrets, etc. Allows the same settings as the [pod](https://nextflow.io/docs/latest/process.html#pod) process directive.
    """)
    public List pod;

    @ConfigOption("""
        The path where Nextflow projects are downloaded (default: `<volume-claim-mount-path>/projects`). Must be a path in a shared K8s persistent volume.
    """)
    public String projectDir;

    @ConfigOption("""
        The strategy for pulling container images. Can be `IfNotPresent`, `Always`, `Never`.

        [Read more](https://kubernetes.io/docs/concepts/containers/images/#image-pull-policy)
    """)
    public String pullPolicy;

    @ConfigOption("""
        The user ID to be used to run the containers. Shortcut for the `securityContext` option.
    """)
    public String runAsUser;

    @ConfigOption("""
        The [security context](https://kubernetes.io/docs/tasks/configure-pod-container/security-context/) to use for all pods.
    """)
    public Map securityContext;

    @ConfigOption("""
        The Kubernetes [service account name](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/) to use.
    """)
    public String serviceAccount;

    @ConfigOption("""
        The name of the persistent volume claim where the shared work directory is stored.
    """)
    public String storageClaimName;

    @ConfigOption("""
        The mount path for the persistent volume claim (default: `/workspace`).
    """)
    public String storageMountPath;

    @ConfigOption("""
        The path in the persistent volume to be mounted (default: `/`).
    """)
    public String storageSubPath;

    @ConfigOption("""
        The path of the shared work directory (default: `<user-dir>/work`). Must be a path in a shared K8s persistent volume.
    """)
    public String workDir;

}

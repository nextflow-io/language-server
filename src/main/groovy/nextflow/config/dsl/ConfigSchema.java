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
package nextflow.config.dsl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nextflow.config.scopes.*;
import nextflow.script.dsl.Function;
import nextflow.script.dsl.ProcessDirectiveDsl;

public class ConfigSchema {

    private static final List<ConfigScope> CLASSES = List.of(
        new ApptainerConfig(),
        new AwsConfig(),
        new AwsBatchConfig(),
        new AwsClientConfig(),
        new AzureConfig(),
        new AzureActiveDirectoryConfig(),
        new AzureBatchConfig(),
        new AzureManagedIdentityConfig(),
        new AzureRegistryConfig(),
        new AzureRetryConfig(),
        new AzureStorageConfig(),
        new CharliecloudConfig(),
        new CondaConfig(),
        new DagConfig(),
        new DockerConfig(),
        new EnvConfig(),
        new ExecutorConfig(),
        new FusionConfig(),
        new GoogleConfig(),
        new GoogleBatchConfig(),
        new K8sConfig(),
        new Manifest(),
        new ParamsConfig(),
        new PluginsConfig(),
        new PodmanConfig(),
        new ProcessConfig(),
        new ProfilesConfig(),
        new ReportConfig(),
        new ShifterConfig(),
        new SingularityConfig(),
        new SpackConfig(),
        new TimelineConfig(),
        new TowerConfig(),
        new TraceConfig(),
        new UnscopedConfig(),
        new WaveConfig(),
        new WaveBuildConfig(),
        new WaveCondaConfig(),
        new WaveHttpConfig(),
        new WaveRetryConfig(),
        new WaveSpackConfig(),
        new WorkflowConfig(),
        new WorkflowOutputConfig()
    );

    public static final Map<String, ConfigScope> SCOPES = getConfigScopes();

    public static final Map<String, String> OPTIONS = getConfigOptions();

    private static Map<String, ConfigScope> getConfigScopes() {
        var result = new HashMap<String, ConfigScope>();
        for( var scope : CLASSES )
            result.put(scope.name(), scope);
        return result;
    }

    private static Map<String, String> getConfigOptions() {
        var result = new HashMap<String, String>();
        for( var scope : CLASSES ) {
            for( var field : scope.getClass().getDeclaredFields() ) {
                var annot = field.getAnnotation(ConfigOption.class);
                if( annot == null )
                    continue;
                var name = scope.name().isEmpty()
                    ? field.getName()
                    : scope.name() + "." + field.getName();
                result.put(name, annot.value());
            }
            for( var method : scope.getClass().getDeclaredMethods() ) {
                var annot = method.getAnnotation(ConfigOption.class);
                if( annot == null )
                    continue;
                var name = scope.name().isEmpty()
                    ? method.getName()
                    : scope.name() + "." + method.getName();
                result.put(name, annot.value());
            }
        }
        // derive process config from process directives
        for( var method : ProcessDirectiveDsl.class.getDeclaredMethods() ) {
            var annot = method.getAnnotation(Function.class);
            if( annot == null )
                continue;
            var name = "process." + method.getName();
            result.put(name, annot.value());
        }
        return result;
    }
}

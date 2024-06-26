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
package nextflow.config.dsl

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

import groovy.transform.CompileStatic
import nextflow.script.dsl.Function
import nextflow.script.dsl.ProcessDirectiveDsl

interface ConfigScope {
    String name()
    String description()
}

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.FIELD, ElementType.METHOD])
@interface ConfigOption {
    String value()
}

@CompileStatic
class ConfigSchema {

    private static final List<ConfigScope> CLASSES = [
        new nextflow.config.scopes.ApptainerConfig(),
        new nextflow.config.scopes.CharliecloudConfig(),
        new nextflow.config.scopes.CondaConfig(),
        new nextflow.config.scopes.DagConfig(),
        new nextflow.config.scopes.DockerConfig(),
        new nextflow.config.scopes.ExecutorConfig(),
        new nextflow.config.scopes.Manifest(),
        new nextflow.config.scopes.PodmanConfig(),
        new nextflow.config.scopes.ProcessConfig(),
        new nextflow.config.scopes.ReportConfig(),
        new nextflow.config.scopes.ShifterConfig(),
        new nextflow.config.scopes.SingularityConfig(),
        new nextflow.config.scopes.TimelineConfig(),
        new nextflow.config.scopes.TraceConfig(),
        new nextflow.config.scopes.UnscopedConfig()
    ]

    static final Map<String, ConfigScope> SCOPES = getConfigScopes()

    static final Map<String, String> OPTIONS = getConfigOptions()

    private static Map<String, ConfigScope> getConfigScopes() {
        final Map<String, ConfigScope> result = [:]
        for( final scope : CLASSES )
            result.put(scope.name(), scope)
        return result
    }

    private static Map<String, String> getConfigOptions() {
        final Map<String, String> result = [:]
        for( final scope : CLASSES ) {
            for( def field : scope.getClass().getDeclaredFields() ) {
                final annot = field.getAnnotation(ConfigOption)
                if( !annot )
                    continue
                final name = scope.name()
                    ? scope.name() + '.' + field.getName()
                    : field.getName()
                result.put(name, annot.value())
            }
            for( def method : scope.getClass().getDeclaredMethods() ) {
                final annot = method.getAnnotation(ConfigOption)
                if( !annot )
                    continue
                final name = scope.name()
                    ? scope.name() + '.' + method.getName()
                    : method.getName()
                result.put(name, annot.value())
            }
        }
        // derive process config from process directives
        for( final method : ProcessDirectiveDsl.class.getDeclaredMethods() ) {
            final annot = method.getAnnotation(Function)
            if( !annot )
                continue
            final name = 'process.' + method.getName()
            result.put(name, annot.value())
        }
        return result
    }
}

package nextflow.config.dsl

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

import groovy.transform.CompileStatic

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
        new nextflow.config.scopes.ExecutorConfig(),
        new nextflow.config.scopes.Manifest(),
        new nextflow.config.scopes.ProcessConfig()
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
                result.put(scope.name() + '.' + field.getName(), annot.value())
            }
            for( def method : scope.getClass().getDeclaredMethods() ) {
                final annot = method.getAnnotation(ConfigOption)
                if( !annot )
                    continue
                result.put(scope.name() + '.' + method.getName(), annot.value())
            }
        }
        return result
    }
}

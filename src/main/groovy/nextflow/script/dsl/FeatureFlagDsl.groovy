package nextflow.script.dsl

import groovy.transform.CompileStatic

@CompileStatic
class FeatureFlagDsl {

    @FeatureFlag(
        name='nextflow.enable.dsl',
        description='''
            Defines the DSL version (`1` or `2`).
        ''')
    float dsl

    @FeatureFlag(
        name='nextflow.enable.moduleBinaries',
        description='''
            When `true`, enables the use of modules with executable scripts i.e. [module binaries](https://nextflow.io/docs/latest/module.html#module-binaries).
        ''')
    boolean moduleBinaries

    @FeatureFlag(
        name='nextflow.enable.strict',
        description='''
            When `true`, the pipeline is executed in [strict mode](https://nextflow.io/docs/latest/config.html#feature-flags).
        ''')
    boolean strict

    @FeatureFlag(
        name='nextflow.preview.output',
        description='''
            When `true`, enables the use of the [workflow output definition](https://nextflow.io/docs/latest/workflow.html#workflow-output-def).
        ''')
    boolean previewOutput

    @FeatureFlag(
        name='nextflow.preview.recursion',
        description='''
            When `true`, enables the use of [process and workflow recursion](https://github.com/nextflow-io/nextflow/discussions/2521).
        ''')
    boolean previewRecursion

    @FeatureFlag(
        name='nextflow.preview.topic',
        description='''
            When `true`, enables the use of [topic channels](https://nextflow.io/docs/latest/channel.html#channel-topic).
        ''')
    boolean previewTopic

}

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface FeatureFlag {
    String name()
    String description()
}

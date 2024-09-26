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
package nextflow.script.dsl

import groovy.transform.CompileStatic

@CompileStatic
class FeatureFlagDsl {

    @Deprecated
    @FeatureFlag(
        name='nextflow.enable.configProcessNamesValidation',
        description='''
            When `true`, prints a warning for every `withName:` process selector that doesn't match a process in the pipeline (default: `true`).
        ''')
    boolean configProcessNamesValidation

    @Deprecated
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
            When `true`, the pipeline is executed in [strict mode](https://nextflow.io/docs/latest/reference/feature-flags.html).
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
            When `true`, enables the use of [topic channels](https://nextflow.io/docs/latest/reference/channel.html#topic).
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

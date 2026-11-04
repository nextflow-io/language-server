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
package nextflow

import groovy.json.JsonOutput
import groovy.transform.TypeChecked
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.MarkdownRenderer
import nextflow.config.spec.ScopeName
import nextflow.config.spec.SpecNode
import nextflow.plugin.Plugins
import nextflow.plugin.spec.ConfigSpec
import nextflow.script.dsl.Description

@TypeChecked
class SpecWriter {

    static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Missing output path")
            System.exit(1)
        }

        final outputPath = args[0]
        final file = new File(outputPath)
        file.parentFile.mkdirs()
        file.text = JsonOutput.toJson(getDefinitions())

        println "Rendered core definitions to $file"

        final docsFile = new File("${file.parent}/config.md")
        docsFile.text = new MarkdownRenderer().render()

        println "Rendered Markdown docs to $docsFile"
    }

    private static List<Map> getDefinitions() {
        final result = new ArrayList<Map>()
        for( final scope : Plugins.getExtensions(ConfigScope) ) {
            final clazz = scope.getClass()
            final scopeName = clazz.getAnnotation(ScopeName)?.value()
            final description = clazz.getAnnotation(Description)?.value()
            if( scopeName == '' ) {
                SpecNode.Scope.of(clazz, '').children().each { name, node ->
                    result.add(ConfigSpec.of(node, name))
                }
                continue
            }
            if( !scopeName )
                continue
            final node = SpecNode.Scope.of(clazz, description)
            result.add(ConfigSpec.of(node, scopeName))
        }
        return result
    }
}

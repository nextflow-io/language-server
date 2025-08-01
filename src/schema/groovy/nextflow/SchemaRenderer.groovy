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

import groovy.transform.TypeChecked
import nextflow.config.schema.JsonRenderer
import nextflow.config.schema.MarkdownRenderer

@TypeChecked
class SchemaRenderer {

    static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Missing output path")
            System.exit(1)
        }

        final outputPath = args[0]
        final file = new File(outputPath)
        file.parentFile.mkdirs()
        file.text = new JsonRenderer().render()

        println "Rendered JSON schema to $file"

        final docsFile = new File("${file.parent}/config.md")
        docsFile.text = new MarkdownRenderer().render()

        println "Rendered Markdown docs to $docsFile"
    }
}

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

package nextflow.lsp.services.script

import java.nio.file.Files
import java.nio.file.Path

import nextflow.lsp.services.util.FormattingOptions
import nextflow.lsp.TestLanguageClient
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentItem
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptFormattingTest extends Specification {

    String openAndFormat(ScriptService service, Path filePath, String contents) {
        def uri = filePath.toUri()
        def textDocumentItem = new TextDocumentItem(uri.toString(), 'nextflow', 1, contents)
        service.didOpen(new DidOpenTextDocumentParams(textDocumentItem))
        def textEdits = service.formatting(uri, new FormattingOptions(4, true, false))
        return textEdits.first().getNewText()
    }

    def 'should format a script' () {
        given:
        def workspaceRoot = Path.of(System.getProperty('user.dir')).resolve('build/test_workspace/')
        if( !Files.exists(workspaceRoot) )
            workspaceRoot.toFile().mkdirs()

        def service = new ScriptService()
        service.connect(new TestLanguageClient())
        service.initialize(workspaceRoot.toUri().toString(), Collections.emptyList(), false)

        when:
        def filePath = workspaceRoot.resolve('main.nf')
        def contents = '''\
            workflow { println 'Hello!' }
            '''.stripIndent()
        then:
        openAndFormat(service, filePath, contents) == '''\
            workflow {
                println('Hello!')
            }
            '''.stripIndent()

        when:
        contents = '''\
            workflow {
                println('Hello!')
            }
            '''.stripIndent()
        then:
        openAndFormat(service, filePath, contents) == '''\
            workflow {
                println('Hello!')
            }
            '''.stripIndent()
    }

}

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

package nextflow.lsp.services.script

import nextflow.lsp.util.Positions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptRenameTest extends Specification {

    Map<String,List<TextEdit>> rename(ScriptService service, String uri, Position position, String newName) {
        def workspaceEdit = service.rename(new RenameParams(new TextDocumentIdentifier(uri), position, newName))
        def result = workspaceEdit.getChanges()
        for( def key : result.keySet() ) {
            result[key].sort((a, b) -> (
                Positions.COMPARATOR.compare(a.getRange().getStart(), b.getRange().getStart())
            ))
        }
        return result
    }

    def 'should rename a workflow' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        def contents = '''\
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            '''
        open(service, uri, contents)
        service.updateNow()
        def changes = rename(service, uri, new Position(0, 9), "hi")
        then:
        changes[uri].size() == 2
        changes[uri][0].getRange().getStart() == new Position(0, 9)
        changes[uri][0].getRange().getEnd() == new Position(0, 14)
        changes[uri][0].getNewText() == "hi"
        changes[uri][1].getRange().getStart() == new Position(4, 4)
        changes[uri][1].getRange().getEnd() == new Position(4, 9)
        changes[uri][1].getNewText() == "hi"
    }

    def 'should rename a workflow input' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        def contents = '''\
            workflow HELLO {
                take:
                greeting

                main:
                greeting.view()
            }
            '''
        open(service, uri, contents)
        service.updateNow()
        def changes = rename(service, uri, new Position(2, 4), "message")
        then:
        changes[uri].size() == 2
        changes[uri][0].getRange().getStart() == new Position(2, 4)
        changes[uri][0].getRange().getEnd() == new Position(2, 12)
        changes[uri][0].getNewText() == "message"
        changes[uri][1].getRange().getStart() == new Position(5, 4)
        changes[uri][1].getRange().getEnd() == new Position(5, 12)
        changes[uri][1].getNewText() == "message"
    }

    def 'should rename a process' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        def contents = '''\
            process HELLO {
                script:
                'true'
            }

            workflow {
                HELLO()
            }
            '''
        open(service, uri, contents)
        service.updateNow()
        def changes = rename(service, uri, new Position(0, 8), "hi")
        then:
        changes[uri].size() == 2
        changes[uri][0].getRange().getStart() == new Position(0, 8)
        changes[uri][0].getRange().getEnd() == new Position(0, 13)
        changes[uri][0].getNewText() == "hi"
        changes[uri][1].getRange().getStart() == new Position(6, 4)
        changes[uri][1].getRange().getEnd() == new Position(6, 9)
        changes[uri][1].getNewText() == "hi"
    }

    def 'should rename a process input' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        def contents = '''\
            process HELLO {
                input:
                val greeting

                script:
                "echo $greeting"
            }
            '''
        open(service, uri, contents)
        service.updateNow()
        def changes = rename(service, uri, new Position(2, 8), "message")
        then:
        changes[uri].size() == 2
        changes[uri][0].getRange().getStart() == new Position(2, 8)
        changes[uri][0].getRange().getEnd() == new Position(2, 16)
        changes[uri][0].getNewText() == "message"
        changes[uri][1].getRange().getStart() == new Position(5, 10)
        changes[uri][1].getRange().getEnd() == new Position(5, 18)
        changes[uri][1].getNewText() == "message"
    }

    def 'should rename a local variable' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        def contents = '''\
            def msg = 'hello'
            println msg
            '''
        open(service, uri, contents)
        service.updateNow()
        def changes = rename(service, uri, new Position(0, 4), "message")
        then:
        changes[uri].size() == 2
        changes[uri][0].getRange().getStart() == new Position(0, 4)
        changes[uri][0].getRange().getEnd() == new Position(0, 7)
        changes[uri][0].getNewText() == "message"
        changes[uri][1].getRange().getStart() == new Position(1, 8)
        changes[uri][1].getRange().getEnd() == new Position(1, 11)
        changes[uri][1].getNewText() == "message"
    }

    def 'should rename a function' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        def contents = '''\
            def hello() {
            }

            workflow {
                hello()
            }
            '''
        open(service, uri, contents)
        service.updateNow()
        def changes = rename(service, uri, new Position(0, 4), "hi")
        then:
        changes[uri].size() == 2
        changes[uri][0].getRange().getStart() == new Position(0, 4)
        changes[uri][0].getRange().getEnd() == new Position(0, 9)
        changes[uri][0].getNewText() == "hi"
        changes[uri][1].getRange().getStart() == new Position(4, 4)
        changes[uri][1].getRange().getEnd() == new Position(4, 9)
        changes[uri][1].getNewText() == "hi"
    }

    def 'should rename a function parameter' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        def contents = '''\
            def hello(greeting: String) {
                println greeting
            }
            '''
        open(service, uri, contents)
        service.updateNow()
        def changes = rename(service, uri, new Position(0, 10), "message")
        then:
        changes[uri].size() == 2
        changes[uri][0].getRange().getStart() == new Position(0, 10)
        changes[uri][0].getRange().getEnd() == new Position(0, 18)
        changes[uri][0].getNewText() == "message"
        changes[uri][1].getRange().getStart() == new Position(1, 12)
        changes[uri][1].getRange().getEnd() == new Position(1, 20)
        changes[uri][1].getNewText() == "message"
    }

}

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
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptReferencesTest extends Specification {

    Map<String,Range> getReferences(ScriptService service, String uri, Position position) {
        def locations = service.references(new ReferenceParams(new TextDocumentIdentifier(uri), position, new ReferenceContext(true)))
        def result = [:]
        for( def location : locations ) {
            def key = location.getUri()
            def ranges = result.computeIfAbsent(key, (k) -> [])
            ranges.add(location.getRange())
        }
        for( def key : result.keySet() )
            result[key].sort((a, b) -> Positions.COMPARATOR.compare(a.getStart(), b.getStart()))
        return result
    }

    def 'should get the references of a workflow definition' () {
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
        awaitUpdate()
        def references = getReferences(service, uri, new Position(0, 9))
        then:
        references[uri].size() == 2
        references[uri][0].getStart() == new Position(0, 0)
        references[uri][0].getEnd() == new Position(1, 1)
        references[uri][1].getStart() == new Position(4, 4)
        references[uri][1].getEnd() == new Position(4, 11)
    }

    def 'should get the references of a workflow definition in a different file' () {
        given:
        def service = getScriptService()
        def mainUri = getUri('main.nf')
        def moduleUri = getUri('module.nf')

        when:
        open(service, moduleUri, '''\
            workflow HELLO {
            }
            ''')
        open(service, mainUri, '''\
            include { HELLO } from './module.nf'

            workflow {
                HELLO()
            }
            ''')
        awaitUpdate()
        def references = getReferences(service, moduleUri, new Position(0, 9))
        then:
        references[moduleUri].size() == 1
        references[moduleUri][0].getStart() == new Position(0, 0)
        references[moduleUri][0].getEnd() == new Position(1, 1)
        references[mainUri].size() == 2
        references[mainUri][0].getStart() == new Position(0, 10)
        references[mainUri][0].getEnd() == new Position(0, 15)
        references[mainUri][1].getStart() == new Position(3, 4)
        references[mainUri][1].getEnd() == new Position(3, 11)
    }

}

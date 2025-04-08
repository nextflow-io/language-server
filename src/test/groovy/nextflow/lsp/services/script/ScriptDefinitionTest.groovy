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

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptDefinitionTest extends Specification {

    Location getDefinition(ScriptService service, String uri, Position position) {
        def locations = service
            .definition(new DefinitionParams(new TextDocumentIdentifier(uri), position))
            .getLeft()
        return locations.size() > 0 ? locations.first() : null
    }

    def 'should get the definition of a workflow in the same file' () {
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
        def location = getDefinition(service, uri, new Position(4, 4))
        then:
        location != null
        location.getUri() == uri
        location.getRange().getStart() == new Position(0, 0)
        location.getRange().getEnd() == new Position(1, 1)
    }

    def 'should get the definition of a workflow in a different file' () {
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
        def location = getDefinition(service, mainUri, new Position(3, 4))
        then:
        location != null
        location.getUri() == moduleUri
        location.getRange().getStart() == new Position(0, 0)
        location.getRange().getEnd() == new Position(1, 1)
    }

}

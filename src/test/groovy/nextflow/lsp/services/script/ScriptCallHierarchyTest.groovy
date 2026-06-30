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

import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptCallHierarchyTest extends Specification {

    private static final String CONTENTS = '''\
        workflow HELLO {
        }

        workflow {
            HELLO()
        }
        '''

    def prepare(ScriptService service, String uri, Position position) {
        return service.prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), position))
    }

    def 'should prepare a call hierarchy item for a definition and a call' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, CONTENTS)
        service.updateNow()
        def atDef = prepare(service, uri, new Position(0, 9))
        def atCall = prepare(service, uri, new Position(4, 4))
        then:
        atDef.size() == 1
        atDef[0].getName() == 'HELLO'
        atDef[0].getRange().getStart() == new Position(0, 0)

        atCall.size() == 1
        atCall[0].getName() == 'HELLO'
        atCall[0].getRange().getStart() == new Position(4, 4)
    }

    def 'should resolve incoming calls to a workflow' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, CONTENTS)
        service.updateNow()
        def item = prepare(service, uri, new Position(0, 9))[0]
        def incoming = service.callHierarchyIncomingCalls(item)
        then: 'the entry workflow calls HELLO at line 4'
        incoming.size() == 1
        incoming[0].getFrom().getName() == '<entry>'
        incoming[0].getFromRanges().any { it.getStart() == new Position(4, 4) }
    }

    def 'should resolve outgoing calls from the entry workflow' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, CONTENTS)
        service.updateNow()
        def item = prepare(service, uri, new Position(3, 0))[0]
        def outgoing = service.callHierarchyOutgoingCalls(item)
        then: 'the entry workflow calls HELLO'
        outgoing.size() == 1
        outgoing[0].getTo().getName() == 'HELLO'
        outgoing[0].getFromRanges().any { it.getStart() == new Position(4, 4) }
    }

    def 'should return no call hierarchy item for a non-callable position' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, CONTENTS)
        service.updateNow()
        then: 'the blank line between the two workflows is not a method or call'
        prepare(service, uri, new Position(2, 0)).isEmpty()
    }

}

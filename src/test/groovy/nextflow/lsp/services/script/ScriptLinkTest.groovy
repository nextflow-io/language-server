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

import org.eclipse.lsp4j.DocumentLinkParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptLinkTest extends Specification {

    List getLinks(ScriptService service, String uri) {
        return service.documentLink(new DocumentLinkParams(new TextDocumentIdentifier(uri)))
    }

    def 'should resolve an include of a .nf module' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            include { HELLO } from './module.nf'

            workflow {
                HELLO()
            }
            ''')
        service.updateNow()
        def links = getLinks(service, uri)
        then:
        links.size() == 1
        links[0].getTarget() == getUri('module.nf')
    }

    def 'should append the .nf extension when the include omits it' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            include { HELLO } from './module'

            workflow {
                HELLO()
            }
            ''')
        service.updateNow()
        def links = getLinks(service, uri)
        then:
        links.size() == 1
        links[0].getTarget() == getUri('module.nf')
    }

    def 'should not produce a link for a plugin include' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            include { sayHello } from 'plugin/nf-hello'

            workflow {
                sayHello()
            }
            ''')
        service.updateNow()
        then:
        getLinks(service, uri).isEmpty()
    }

}

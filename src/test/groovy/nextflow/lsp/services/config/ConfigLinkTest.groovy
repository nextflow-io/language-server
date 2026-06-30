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

package nextflow.lsp.services.config

import org.eclipse.lsp4j.DocumentLinkParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigLinkTest extends Specification {

    List getLinks(ConfigService service, String uri) {
        return service.documentLink(new DocumentLinkParams(new TextDocumentIdentifier(uri)))
    }

    def 'should resolve a relative config include against the parent directory' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, '''\
            includeConfig 'conf/base.config'
            ''')
        service.updateNow()
        def links = getLinks(service, uri)
        then:
        links.size() == 1
        links[0].getTarget() == getUri('conf/base.config')
    }

    def 'should pass through an absolute include URI unchanged' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, '''\
            includeConfig 'https://example.com/shared.config'
            ''')
        service.updateNow()
        def links = getLinks(service, uri)
        then:
        links.size() == 1
        links[0].getTarget() == 'https://example.com/shared.config'
    }

    def 'should produce no links for a config without includes' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, '''\
            process.cpus = 2
            ''')
        service.updateNow()
        then:
        getLinks(service, uri).isEmpty()
    }

}

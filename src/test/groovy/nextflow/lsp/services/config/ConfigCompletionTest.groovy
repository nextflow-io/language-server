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

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigCompletionTest extends Specification {

    // NOTE: completion() runs its own update internally, so (unlike hover or
    // definition) the file must NOT be flushed with updateNow() beforehand.

    def either(ConfigService service, String uri, Position position, int maxItems = 100) {
        return service.completion(new CompletionParams(new TextDocumentIdentifier(uri), position), maxItems, false)
    }

    List<CompletionItem> getCompletions(ConfigService service, String uri, Position position, int maxItems = 100) {
        def result = either(service, uri, position, maxItems)
        return result.isLeft() ? result.getLeft() : result.getRight().getItems()
    }

    def 'should provide top-level completions for a partial scope name' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, 'wor\n')
        def completions = getCompletions(service, uri, new Position(0, 3))
        def byLabel = completions.collectEntries { [(it.getLabel()): it] }
        then: 'a scope is offered both as a bare name and as a block snippet'
        byLabel['process']?.getKind() == CompletionItemKind.Property
        byLabel['process {']?.getKind() == CompletionItemKind.Property
        and: 'a top-level option carries its type in the detail'
        byLabel['workDir']?.getDetail()?.startsWith('workDir:')
    }

    def 'should provide option completions for a config scope' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, 'process.\n')
        def completions = getCompletions(service, uri, new Position(0, 8))
        def cpus = completions.find { it.getLabel() == 'cpus' }
        then:
        cpus != null
        cpus.getKind() == CompletionItemKind.Property
        cpus.getDetail() == 'cpus: Integer'
    }

    def 'should provide option completions inside a profile, ignoring the profile scope' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, '''\
            profiles {
                standard {
                    process.
                }
            }
            ''')
        def completions = getCompletions(service, uri, new Position(2, 16))
        then: 'the "profiles.<name>" prefix is stripped, so process options are offered'
        completions.find { it.getLabel() == 'cpus' }?.getDetail() == 'cpus: Integer'
    }

    def 'should mark the completion list incomplete when items exceed maxItems' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, 'process.\n')
        def result = either(service, uri, new Position(0, 8), 1)
        then:
        result.isRight()
        result.getRight().isIncomplete()
        result.getRight().getItems().size() == 1
    }

    def 'should return no completions for a file that is not open' () {
        given:
        def service = getConfigService()

        expect:
        getCompletions(service, getUri('other.config'), new Position(0, 0)).isEmpty()
    }

}

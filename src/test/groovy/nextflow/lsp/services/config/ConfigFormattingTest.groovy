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

import nextflow.script.formatter.FormattingOptions
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigFormattingTest extends Specification {

    String openAndFormat(ConfigService service, String uri, String contents) {
        open(service, uri, contents)
        def textEdits = service.formatting(URI.create(uri), new FormattingOptions(4, true))
        // no edits means the document was already formatted
        return textEdits ? textEdits.first().getNewText() : contents.stripIndent()
    }

    boolean checkFormat(ConfigService service, String uri, String before, String after) {
        assert openAndFormat(service, uri, before) == after.stripIndent()
        return true
    }

    boolean checkRoundTrip(ConfigService service, String uri, String source) {
        return checkFormat(service, uri, source, source)
    }

    def 'should format a config file' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        expect:
        checkFormat(service, uri,
            '''\
            process.cpus = 2 ; process.memory = 8.GB
            ''',
            '''\
            process.cpus = 2
            process.memory = 8.GB
            '''
        )
        checkRoundTrip(service, uri,
            '''\
            process.cpus = 2
            process.memory = 8.GB
            '''
        )
    }

    def 'should preserve all comments when formatting a config file' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        expect:
        // trailing comments, dangling comments at the end of a block and at
        // the end of the file are all preserved
        checkRoundTrip(service, uri,
            '''\
            process {
                cpus = 2 // trailing comment
                // comment after the last option
            }

            // comment at the end of the file
            '''
        )
    }

    def 'should not format config regions excluded with fmt directives' () {
        given:
        def service = getConfigService()
        def uri = getUri('nextflow.config')

        expect:
        checkRoundTrip(service, uri,
            '''\
            process.cpus = 2

            // fmt: off
            env.FOO    = 'one'
            env.BARBAZ = 'two'
            // fmt: on
            '''
        )
    }

    def 'should produce identical output when formatting a cached config AST twice' () {
        given:
        // formatting the same document repeatedly without a document change
        // re-derives the comment metadata on the same cached AST -- the
        // output must not change; the input is deliberately non-canonical
        // so that every request returns an edit
        def service = getConfigService()
        def uri = getUri('nextflow.config')
        def contents = '''\
            // top comment
            process {
                cpus=2 // trailing comment
                // dangling comment
            }
            '''.stripIndent()
        def expected = contents.replace('cpus=2', 'cpus = 2')

        when:
        openOnDisk(service, uri, contents)
        def texts = (1..3).collect {
            def edits = service.formatting(URI.create(uri), new FormattingOptions(4, true))
            edits ? edits.first().getNewText() : contents
        }

        then:
        texts == [expected] * 3

        cleanup:
        deleteOnDisk(uri)
    }

}

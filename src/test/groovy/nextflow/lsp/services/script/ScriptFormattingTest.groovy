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

import nextflow.script.formatter.FormattingOptions
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptFormattingTest extends Specification {

    boolean checkFormat(ScriptService service, String uri, FormattingOptions options, String before, String after) {
        open(service, uri, before)
        def textEdits = service.formatting(URI.create(uri), options)
        // the provider returns no edits both when the document is already
        // formatted and when it refuses to format (e.g. a comment would be
        // lost), so a document that should change must produce an edit
        if( before.stripIndent() != after.stripIndent() )
            assert textEdits
        def newText = textEdits ? textEdits.first().getNewText() : before.stripIndent()
        assert newText == after.stripIndent()
        return true
    }

    boolean checkFormat(ScriptService service, String uri, String before, String after) {
        return checkFormat(service, uri, new FormattingOptions(4, true), before, after)
    }

    boolean checkRoundTrip(ScriptService service, String uri, FormattingOptions options, String source) {
        return checkFormat(service, uri, options, source, source)
    }

    boolean checkRoundTrip(ScriptService service, String uri, String source) {
        return checkFormat(service, uri, source, source)
    }

    def 'should format a script' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            '''\
            workflow { println 'Hello!' }
            ''',
            '''\
            workflow {
                println('Hello!')
            }
            '''
        )
        checkFormat(service, uri, 
            '''\
            workflow {
                println('Hello!')
            }
            ''',
            '''\
            workflow {
                println('Hello!')
            }
            '''
        )
    }

    def 'should format an include declaration' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            '''\
            include{foo;bar}from'./foobar.nf'
            ''',
            '''\
            include { foo ; bar } from './foobar.nf'
            '''
        )
        checkFormat(service, uri,
            '''\
            include{
            foo;bar
            }from'./foobar.nf'
            ''',
            '''\
            include {
                foo ;
                bar
            } from './foobar.nf'
            '''
        )
    }

    def 'should preserve all comments when formatting' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        // trailing comments, dangling comments at the end of a block and at
        // the end of the file are all preserved; the input is deliberately
        // non-canonical so that a refusal to format cannot pass as a no-op
        checkFormat(service, uri,
            '''\
            workflow {
                x=1 // trailing comment
                // comment after the last statement
            }

            // comment at the end of the file
            ''',
            '''\
            workflow {
                x = 1 // trailing comment
                // comment after the last statement
            }

            // comment at the end of the file
            '''
        )
        // a commented-out process is preserved
        checkFormat(service, uri,
            '''\
            // process FOO {
            //     script:
            //     "true"
            // }

            process BAR {
                script:
                "true"
            }

            workflow {
                x=BAR()
            }
            ''',
            '''\
            // process FOO {
            //     script:
            //     "true"
            // }

            process BAR {
                script:
                "true"
            }

            workflow {
                x = BAR()
            }
            '''
        )
    }

    def 'should re-indent multi-line strings' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')
        def options = new FormattingOptions(2, true)

        expect:
        checkFormat(service, uri, options,
            '''\
            process foo {
                script:
                """
                echo 'hello world!'
                """
            }
            ''',
            '''\
            process foo {
              script:
              """
              echo 'hello world!'
              """
            }
            '''
        )
    }

    def 'should format leading comments in a file with CRLF line endings' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            "workflow {\r\n    // ALIGN reads to reference genome\r\n    BWA_ALIGN(sample_id, library_id)\r\n}\r\n",
            '''\
            workflow {
                // ALIGN reads to reference genome
                BWA_ALIGN(sample_id, library_id)
            }
            '''
        )
    }

    def 'should produce identical output when formatting a cached AST twice' () {
        given:
        // formatting the same document repeatedly without a document change
        // reuses the same cached AST -- the output must not change, including
        // for comments inside wrapped expressions; the input is deliberately
        // non-canonical so that every request returns an edit
        def service = getScriptService()
        def uri = getUri('main.nf')
        def contents = '''\
            workflow {
                foo(
                    // leading comment on element
                    alpha,
                    beta, // trailing comment on element
                )
                data
                    // comment on chain link
                    .map { x -> x }
                    .view()
                y=1
            }
            '''.stripIndent()
        def expected = contents.replace('y=1', 'y = 1')

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

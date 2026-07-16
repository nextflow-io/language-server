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

import java.nio.file.Files
import java.nio.file.Path

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
        assert textEdits.first().getNewText() == after.stripIndent()
        return true
    }

    boolean checkFormat(ScriptService service, String uri, String before, String after) {
        return checkFormat(service, uri, new FormattingOptions(4, true), before, after)
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

    def 'should format if-else statements in K&R style' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            '''\
            workflow {
                if( params.a ) {
                    run_a()
                }
                else if( params.b ) {
                    run_b()
                }
                else {
                    run_c()
                }
            }
            ''',
            '''\
            workflow {
                if (params.a) {
                    run_a()
                } else if (params.b) {
                    run_b()
                } else {
                    run_c()
                }
            }
            '''
        )
    }

    def 'should normalize blank lines' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            '''\
            include { A } from './a.nf'
            include { B } from './b.nf'
            params.x = 1
            workflow {
                A()
            }
            ''',
            '''\
            include { A } from './a.nf'
            include { B } from './b.nf'

            params.x = 1

            workflow {
                A()
            }
            '''
        )
        checkFormat(service, uri,
            '''\
            workflow {

                x = 1



                y = 2
            }
            ''',
            '''\
            workflow {
                x = 1

                y = 2
            }
            '''
        )
    }

    def 'should preserve all comments when formatting' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        // trailing comments, dangling comments at the end of a block and at
        // the end of the file are all preserved
        checkFormat(service, uri,
            '''\
            workflow {
                x = 1 // trailing comment
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
                BAR()
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
                BAR()
            }
            '''
        )
    }

    def 'should wrap lines that exceed the maximum line length' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')
        def options = new FormattingOptions(4, true, false, false, false, 60)

        expect:
        checkFormat(service, uri, options,
            '''\
            workflow {
                ALIGN_AND_SORT(samples_channel, reference_genome, annotation_file, params.threads)
            }
            ''',
            '''\
            workflow {
                ALIGN_AND_SORT(
                    samples_channel,
                    reference_genome,
                    annotation_file,
                    params.threads,
                )
            }
            '''
        )
    }

    def 'should not wrap lines when the maximum line length is zero' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')
        def options = new FormattingOptions(4, true, false, false, false, 0)

        expect:
        checkFormat(service, uri, options,
            '''\
            workflow {
                ALIGN_AND_SORT(samples_channel, reference_genome, annotation_file, params.threads, extra_arg_one, extra_arg_two, extra_arg_three)
            }
            ''',
            '''\
            workflow {
                ALIGN_AND_SORT(samples_channel, reference_genome, annotation_file, params.threads, extra_arg_one, extra_arg_two, extra_arg_three)
            }
            '''
        )
    }

    def 'should not format regions excluded with fmt directives' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            '''\
            workflow {
                x  =  [1,  2,   3] // fmt: skip
                y = [4,5]
            }
            ''',
            '''\
            workflow {
                x  =  [1,  2,   3] // fmt: skip
                y = [4, 5]
            }
            '''
        )
        // a fmt: off / fmt: on region round-trips unchanged
        checkFormat(service, uri,
            '''\
            workflow {
                a = 1

                // fmt: off
                matrix = [
                    [1, 0],
                    [0, 1] ]
                // fmt: on

                b = 2
            }
            ''',
            '''\
            workflow {
                a = 1

                // fmt: off
                matrix = [
                    [1, 0],
                    [0, 1] ]
                // fmt: on

                b = 2
            }
            '''
        )
    }

    def 'should produce identical output when formatting a cached AST twice' () {
        given:
        // formatting the same document repeatedly without a document change
        // re-derives the comment metadata on the same cached AST -- the
        // output must not change, including for comments inside wrapped
        // expressions
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
            }
            '''.stripIndent()
        // the file must exist on disk so that the deferred workspace scan
        // does not evict it from the AST cache between formatting calls
        Files.writeString(Path.of(URI.create(uri)), contents)

        when:
        open(service, uri, contents)
        def edits = (1..3).collect {
            service.formatting(URI.create(uri), new FormattingOptions(4, true))
        }

        then:
        edits.every { it.first().getNewText() == contents }

        cleanup:
        Files.deleteIfExists(Path.of(URI.create(uri)))
    }

}

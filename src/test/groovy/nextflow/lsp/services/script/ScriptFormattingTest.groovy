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

    boolean checkFormat(ScriptService service, String uri, String before, String after) {
        open(service, uri, before)
        def textEdits = service.formatting(URI.create(uri), new FormattingOptions(4, true))
        assert textEdits.first().getNewText() == after.stripIndent()
        return true
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

}

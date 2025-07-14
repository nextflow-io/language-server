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

import nextflow.lsp.services.LanguageServerConfiguration
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*
import static nextflow.lsp.util.JsonUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConvertScriptStaticTypesTest extends Specification {

    boolean check(ScriptService service, String before, String after) {
        def uri = getUri('main.nf')
        open(service, uri, wrapInProcess(before))
        def response = service.executeCommand('nextflow.server.convertScriptToTyped', [asJson(uri)], LanguageServerConfiguration.defaults())
        def newText = response.applyEdit.getChanges()[uri][0].getNewText()
        assert newText == wrapInProcess(after).strip()
        return true
    }

    String wrapInProcess(String section) {
        """\
        process test {
            ${section.strip()}

            script:
            true
        }
        """.stripIndent()
    }

    boolean checkInputs(ScriptService service, String beforeInput, String afterInput, String stage=null) {
        def before = """\
            input:
            ${beforeInput}
            """

        def after = stage != null
            ?
            """\
            input:
            ${afterInput}

            stage:
            ${stage}
            """
            :
            """\
            input:
            ${afterInput}
            """

        return check(service, before, after)
    }

    boolean checkOutputs(ScriptService service, String beforeOutput, String afterOutput, String topic=null) {
        def before = """\
            output:
            ${beforeOutput}
            """

        def after = afterOutput != null && topic != null
            ?
            """\
            output:
            ${afterOutput}

            topic:
            ${topic}
            """
            : afterOutput != null
            ?
            """\
            output:
            ${afterOutput}
            """
            :
            """\
            topic:
            ${topic}
            """

        return check(service, before, after)
    }

    def 'should convert val inputs' () {
        given:
        def service = getScriptService()

        expect:
        checkInputs(service, 'val id', 'id')
    }

    def 'should convert path inputs' () {
        given:
        def service = getScriptService()

        expect:
        checkInputs(service, INPUT, TYPED_INPUT, STAGE)

        where:
        INPUT                               | TYPED_INPUT           | STAGE
        'path fastq'                        | 'fastq: Path'         | null
        "path 'file.txt'"                   | '$in1: Path'          | "stageAs 'file.txt', \$in1"
        "path fastq, stageAs: 'file.txt'"   | 'fastq: Path'         | "stageAs 'file.txt', fastq"
        "path fastq, arity: '1'"            | 'fastq: Path'         | null
        "path fastq, arity: '0..*'"         | 'fastq: Set<Path>'    | null
        "path fastq, arity: '2'"            | 'fastq: Set<Path>'    | null
    }

    def 'should convert env inputs' () {
        given:
        def service = getScriptService()

        expect:
        checkInputs(service, "env 'FOO'", '$in1: String', "env 'FOO', \$in1")
    }

    def 'should convert stdin inputs' () {
        given:
        def service = getScriptService()

        expect:
        checkInputs(service, 'stdin', '$in1: String', 'stdin $in1')
    }

    def 'should convert tuple inputs' () {
        given:
        def service = getScriptService()

        expect:
        checkInputs(service, INPUT, TYPED_INPUT, STAGE)

        where:
        INPUT                               | TYPED_INPUT                 | STAGE
        'tuple val(id), path(fastq)'        | '(id, fastq: Path): Record' | null
        "tuple val(id), path('file.txt')"   | '(id, $in1: Path): Record'  | "stageAs 'file.txt', \$in1"
    }

    def 'should convert each inputs as val or path' () {
        given:
        def service = getScriptService()

        expect:
        checkInputs(service, 'each method', 'method')
        checkInputs(service, 'each path(index)', 'index: Path')
        checkInputs(service, "each path('file.txt')", '$in1: Path', "stageAs 'file.txt', \$in1")
    }

    def 'should convert val outputs' () {
        given:
        def service = getScriptService()

        expect:
        checkOutputs(service, OUTPUT, TYPED_OUTPUT)

        where:
        OUTPUT              | TYPED_OUTPUT
        "val '1'"           | "'1'"
        "val '1', emit: id" | "id = '1'"
    }

    def 'should convert path outputs' () {
        given:
        def service = getScriptService()

        expect:
        checkOutputs(service, OUTPUT, TYPED_OUTPUT, TOPIC)

        where:
        OUTPUT                              | TYPED_OUTPUT                          | TOPIC
        "path 'output.txt'"                 | "file('output.txt')"                  | null
        "path 'output.txt', emit: txt"      | "txt = file('output.txt')"            | null
        "path 'output.txt', topic: txt"     | null                                  | "file('output.txt') >> 'txt'"
        "path 'output.txt', hidden: true"   | "file('output.txt', hidden: true)"    | null
        "path 'output.txt', optional: true" | "file('output.txt', optional: true)"  | null
        "path '*.txt', arity: '1'"          | "file('*.txt')"                       | null
        "path '*.txt', arity: '1..*'"       | "files('*.txt')"                      | null
        "path '*.txt', arity: '0..*'"       | "files('*.txt')"                      | null
    }

    def 'should convert env outputs' () {
        given:
        def service = getScriptService()

        expect:
        checkOutputs(service, "env 'FOO'", "env('FOO')")
    }

    def 'should convert eval outputs' () {
        given:
        def service = getScriptService()

        expect:
        checkOutputs(service, "eval 'bash --version'", "eval('bash --version')")
    }

    def 'should convert stdout outputs' () {
        given:
        def service = getScriptService()

        expect:
        checkOutputs(service, 'stdout', 'stdout()')
    }

    def 'should convert tuple outputs' () {
        given:
        def service = getScriptService()

        expect:
        checkOutputs(service, OUTPUT, TYPED_OUTPUT)

        where:
        OUTPUT                                                      | TYPED_OUTPUT
        "tuple val('id'), path('*.fastq')"                          | "tuple('id', file('*.fastq'))"
        "tuple stdout(), val('id')"                                 | "tuple(stdout(), 'id')"
        "tuple val('x'), val('y'), emit: xy"                        | "xy = tuple('x', 'y')"
        "tuple stdout(), env('BAR'), emit: bar"                     | "bar = tuple(stdout(), env('BAR'))"
        "tuple val('id'), path('*.fastq', hidden: true), emit: bar" | "bar = tuple('id', file('*.fastq', hidden: true))"
    }

}

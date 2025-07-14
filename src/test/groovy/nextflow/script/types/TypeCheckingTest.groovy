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

package nextflow.script.types

import nextflow.script.ast.ASTNodeMarker
import nextflow.script.control.ScriptParser
import nextflow.script.control.ScriptResolveVisitor
import nextflow.script.control.TypeCheckingVisitorEx
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static nextflow.script.types.TypeCheckingUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class TypeCheckingTest extends Specification {

    @Shared
    ScriptParser scriptParser

    def setupSpec() {
        scriptParser = new ScriptParser()
    }

    SourceUnit parse(String contents) {
        def source = scriptParser.parse('main.nf', contents.stripIndent())
        new ScriptResolveVisitor(source, scriptParser.compiler().compilationUnit(), Types.DEFAULT_SCRIPT_IMPORTS, Collections.emptyList()).visit()
        new TypeCheckingVisitorEx(source, true).visit()
        return source
    }

    Statement parseStatement(String contents) {
        def source = parse(contents)
        assert !source.getErrorCollector().hasErrors()
        def entry = source.getAST().getEntry()
        assert entry.main instanceof BlockStatement
        return entry.main.statements.last()
    }

    Expression parseExpression(String contents) {
        def stmt = parseStatement(contents)
        assert stmt instanceof ExpressionStatement
        return stmt.expression
    }

    def getErrors(String contents) {
        final source = parse(contents)
        final errorCollector = source.getErrorCollector()
        if( !errorCollector.hasErrors() )
            return Collections.emptyList()
        return errorCollector.getErrors().stream()
            .filter(e -> e instanceof SyntaxErrorMessage)
            .map(e -> e.cause)
            .sorted(ERROR_COMPARATOR)
            .toList()
    }

    static final Comparator<SyntaxException> ERROR_COMPARATOR = (SyntaxException a, SyntaxException b) -> {
        return a.getStartLine() != b.getStartLine()
            ? a.getStartLine() - b.getStartLine()
            : a.getStartColumn() - b.getStartColumn()
    }

    boolean check(String contents, String message) {
        final errors = getErrors(contents)
        if( message != null ) {
            assert errors.size() == 1
            assert errors[0].getOriginalMessage() == message
        }
        else {
            assert errors.size() == 0
        }
        return true
    }

    boolean checkType(ASTNode exp, Class type) {
        assert TypesEx.isEqual(getType(exp), ClassHelper.makeCached(type))
        return true
    }

    def 'should check a parameter declaration' () {
        when:
        def errors = getErrors(
            '''\
            params {
                input: String = 3.14
            }
            '''
        )
        then:
        errors.size() == 1
        errors[0].getStartLine() == 2
        errors[0].getStartColumn() == 5
        errors[0].getOriginalMessage() == "Parameter 'input' with type String cannot be assigned to default value with type Float"

        when:
        def exp = parseExpression(
            '''\
            params {
                input: String = 'input'
            }

            workflow {
                params
            }
            '''
        )
        def cn = getType(exp)
        then:
        checkType(exp, ParamsMap)
        cn.getField('input') != null
    }

    def 'should check a workflow emit' () {
        when:
        def errors = getErrors(
            '''\
            workflow hello {
                emit:
                result: String = 42
            }
            '''
        )
        then:
        errors.size() == 1
        errors[0].getStartLine() == 3
        errors[0].getStartColumn() == 5
        errors[0].getOriginalMessage() == "Assignment target with type String cannot be assigned to value with type Integer"

        when:
        errors = getErrors(
            '''\
            workflow hello {
                emit:
                result: Integer = 42
            }
            '''
        )
        then:
        errors.size() == 0
    }

    def 'should check a return statement' () {
        when:
        def errors = getErrors(
            '''\
            def hello() -> String {
                return 42
            }
            '''
        )
        then:
        errors.size() == 1
        errors[0].getStartLine() == 2
        errors[0].getStartColumn() == 5
        errors[0].getOriginalMessage() == "Return value with type Integer does not match the declared return type (String)"

        when:
        errors = getErrors(
            '''\
            def hello() -> String {
                return 'Hello!'
            }
            '''
        )
        then:
        errors.size() == 0
    }

    def 'should check an assignment' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE                              | ERROR
        'def x: String ; x = 42'            | "Assignment target with type String cannot be assigned to value with type Integer"
        // "def x: List<String> ; x = [42]"    | "Assignment target with type List<String> cannot be assigned to value with type List<Integer>"
        "def x: List<String> ; x = []"      | null
    }

    def 'should infer the type of a variable declaration or assignment' () {
        when:
        def exp = parseExpression(
            '''
            def x = 'hello'
            '''
        )
        def target = exp.getLeftExpression()
        then:
        checkType(target, String)

        when:
        exp = parseExpression(
            '''
            def x
            x = 'hello'
            '''
        )
        target = exp.getLeftExpression()
        then:
        checkType(target, String)
    }

    @Unroll
    def 'should check a function call' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE          | ERROR
        'env()'         | "Function `env` expects 1 argument(s) but received 0"
        'env(42)'       | "Argument with type Integer is not compatible with parameter of type String"
        "env('HELLO')"  | null
    }

    @Unroll
    def 'should check a function call with overloads' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE              | ERROR
        'file()'            | "Function `file` (with multiple signatures) was called with incorrect number of arguments and/or incorrect argument types"
        'file(42)'          | "Function `file` (with multiple signatures) was called with incorrect number of arguments and/or incorrect argument types"
        "file('input.txt')" | null
        "file('input.txt', checkIfExists: true)" | null
    }

    @Unroll
    def 'should check a function call with named params' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE                                      | ERROR
        "file('input.txt', checkIfExist: true)"     | "Named param `checkIfExist` is not defined"
        "file('input.txt', checkIfExists: 'true')"  | "Named param `checkIfExists` expects a Boolean but received a String"
        "file('input.txt', checkIfExists: true)"    | null
    }

    @Unroll
    def 'should check a namespaced function call' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE                  | ERROR
        'channel.queue()'       | "Unrecognized method `queue` for namespace `channel`"
        'channel.value()'       | "Function `value` expects 1 argument(s) but received 0"
        'channel.of()'          | null
        'channel.of(1, 2, 3)'   | null
    }

    @Unroll
    def 'should check a method call' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE                                  | ERROR
        'workflow.outputDir.name()'             | "Unrecognized method `name` for type Path"
        'workflow.outputDir.resolve()'          | "Function `resolve` expects 1 argument(s) but received 0"
        "workflow.outputDir.resolve('hello')"   | null
    }

    @Unroll
    def 'should check a binary expression' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE          | ERROR
        "2 + '2'"       | "The `+` operator is not defined for operands with types Integer and String"
        "2 + 2"         | null
        "2 == '2'"      | "The `==` operator is not defined for operands with types Integer and String"
        "2 == 2"        | null
        "2 in ['2']"    | "The `in` operator is not defined for operands with types Integer and List<String>"
        "2 in [2]"      | null
        // "[2] == ['2']"  | "The `==` operator is not defined for operands with types List<Integer> and List<String>"
        "[2] == [2]"    | null
    }

    def 'should recognize duration literals' () {
        expect:
        check(
            '''\
            (1.d + 4.h + 15.m + 30.s + 500.ms).toMillis()
            ''',
            null
        )
    }

    def 'should recognize memory unit literals' () {
        expect:
        check(
            '''\
            (1.GB + 500.MB + 4.KB + 64.B).toBytes()
            ''',
            null
        )
    }

    @Unroll
    def 'should check a ternary expression' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE              | ERROR
        "true ? 42 : '42'"  | "Conditional expression has inconsistent types -- true branch has type Integer but false branch has type String"
        "true ? 42 : null"  | null
    }

    def 'should check an elvis expression' () {
        when:
        def exp = parseExpression(
            '''
            workflow {
                def meta: Map<String,String> = [:]
                meta.id ?: 'id'
            }
            '''
        )
        then:
        checkType(exp, String)
    }

    @Unroll
    def 'should check a list expression' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE          | ERROR
        "[1, 2, '3']"   | "List expression has inconsistent element types -- some elements have type Integer while others have type String"
        "[1, 2, 3]"     | null
    }

    @Unroll
    def 'should resolve generic types' () {
        given:
        def exp = parseExpression(
            """\
            workflow { ${SOURCE} }
            """
        )
        expect:
        TypesEx.getName(getType(exp)) == TYPE

        where:
        SOURCE                              | TYPE
        '[1, 2, 3]'                         | 'List<Integer>'
        '[1, 2, 3].first()'                 | 'Integer'
        'channel.value("42")'               | 'Value<String>'
        'channel.of(1, 2, 3)'               | 'Channel<Integer>'
        '[1, 2, 3].collect { v -> v * 2 }'  | 'Iterable<Integer>'
        'channel.of(1).map { v -> v * 2 }'  | 'Channel<Integer>'
        'channel.fromList(1..10).flatMap { n -> 1..n }' | 'Channel<Integer>'
    }

    @Ignore("todo")
    def 'should resolve generic functional parameter types' () {
        when:
        def exp = parseExpression(
            """\
            [1, 2, 3].collect { v -> v * 2 }
            """
        )
        def method = exp.getNodeMetaData(ASTNodeMarker.METHOD_TARGET)
        def param = method.getParameters().last()
        then:
        TypesEx.getName(param.getType()) == '(Integer) -> Integer'

        when:
        exp = parseExpression(
            """\
            channel.of(1, 2, 3).map { v -> v * 2 }
            """
        )
        method = exp.getNodeMetaData(ASTNodeMarker.METHOD_TARGET)
        param = method.getParameters().last()
        then:
        TypesEx.getName(param.getType()) == '(Integer) -> Integer'
    }

    @Unroll
    def 'should check a unary expression' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE  | ERROR
        "-'42'" | "The `-` operator is not defined for an operand with type String"
        "-42"   | null
    }

    @Unroll
    def 'should check a cast expression' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE                  | ERROR
        '42 as List'            | "Value of type Integer cannot be cast to List"
        '[] as List<Path>'      | null
        "'24 h' as Duration"    | null
        "'8 GB' as MemoryUnit"  | null
    }

    def 'should check a record cast' () {
        expect:
        check(
            '''\
            workflow {
                record(id: '1', fastq_1: file('1_1.fastq'), fastq_2: file('1_2.fastq')) as FastqPair
            }

            record FastqPair {
                id: String
                fastq_1: Path
                fastq_2: Path
            }
            ''',
            null
        )
        check(
            '''\
            workflow {
                record(id: '1', fastq_1: file('1_1.fastq')) as FastqPair
            }

            record FastqPair {
                id: String
                fastq_1: Path
                fastq_2: Path
            }
            ''',
            'Record mismatch -- source record is missing field `fastq_2` required by FastqPair'
        )
        check(
            '''\
            workflow {
                record(id: '1', fastq_1: file('1_1.fastq'), fastq_2: '1_2.fastq') as FastqPair
            }

            record FastqPair {
                id: String
                fastq_1: Path
                fastq_2: Path
            }
            ''',
            'Record mismatch -- field `fastq_2` of FastqPair expects a Path but received a String'
        )
    }

    @Unroll
    def 'should check a property expression' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE                          | ERROR
        'workflow.output_dir'           | "Unrecognized property `output_dir` for namespace `workflow`"
        'workflow.outputDir'            | null
        'workflow.outputDir.fileName'   | "Unrecognized property `fileName` for type Path"
        'workflow.outputDir.name'       | null
    }

    def 'should check a map property' () {
        when:
        def exp = parseExpression(
            '''
            workflow {
                def meta: Map = [:]
                meta.id
            }
            '''
        )
        then:
        checkType(exp, Object)
    }

    def 'should check a workflow call' () {
        expect:
        check(
            '''
            workflow hello {
                take:
                messages: Channel<String>

                main:
                messages.view()
            }

            workflow {
                hello()
            }
            ''',
            'Workflow `hello` expects 1 argument(s) but received 0'
        )
    }

    def 'should recognize workflow output type' () {
        when:
        def exp = parseExpression(
            '''\
            workflow hello {
                emit:
                result: Integer = 42
            }

            workflow {
                hello().result
            }
            '''
        )
        then:
        checkType(exp, Integer)
    }

    def 'should check a process call' () {
        expect:
        check(
            '''
            nextflow.preview.types = true

            process hello {
                input:
                message: String

                script:
                ''
            }

            workflow hello_flow {
                take:
                messages: Channel<String>

                main:
                hello( messages )
            }
            ''',
            null
        )
        and:
        check(
            '''
            nextflow.preview.types = true

            process hello {
                input:
                message: String

                script:
                ''
            }

            workflow {
                hello()
            }
            ''',
            'Process `hello` expects 1 argument(s) but received 0'
        )
        and:
        check(
            '''
            nextflow.preview.types = true

            process hello {
                input:
                message: String

                script:
                ''
            }

            workflow hello_flow {
                take:
                messages: Channel<Integer>

                main:
                hello( messages )
            }
            ''',
            'Argument with type Integer is not compatible with process input of type String'
        )
        and:
        check(
            '''
            nextflow.preview.types = true

            process hello {
                input:
                message: String
                target: String

                script:
                ''
            }

            workflow hello_flow {
                take:
                messages: Channel<String>
                targets: Channel<String>

                main:
                hello( messages, targets )
            }
            ''',
            'Process `hello` was called with multiple channel arguments which can lead to non-deterministic behavior -- make sure that at most one argument is a channel and that all other arguments are dataflow values'
        )
    }

    def 'should recognize process output type' () {
        when:
        def exp = parseExpression(
            '''\
            nextflow.preview.types = true

            process hello {
                input:
                target: String

                output:
                "Hello, $target!"

                exec:
                true
            }

            workflow {
                hello( 'World' )
            }
            '''
        )
        def type = getType(exp)
        then:
        TypesEx.getName(type) == 'Value<String>'

        when:
        exp = parseExpression(
            '''\
            nextflow.preview.types = true

            process hello {
                input:
                target: String

                output:
                message: String = "Hello, $target!"

                exec:
                true
            }

            workflow {
                hello( 'World' )
                hello.out.message
            }
            '''
        )
        type = getType(exp)
        then:
        TypesEx.getName(type) == 'Value<String>'
    }

    def 'should resolve record type' () {
        when:
        def exp = parseExpression(
            '''\
            record(id: '1', count: 42)
            '''
        )
        def type = getType(exp)
        then:
        TypesEx.getName(type) == 'Record'
        type.getField('id') != null
        type.getField('count') != null

        when:
        exp = parseExpression(
            '''\
            record(id: '1', count: 42).id
            '''
        )
        type = getType(exp)
        then:
        TypesEx.getName(type) == 'String'
    }

    def 'should resolve tuple type' () {
        when:
        def exp = parseExpression(
            '''\
            tuple('hello', 42, true)
            '''
        )
        def type = getType(exp)
        then:
        TypesEx.getName(type) == 'Tuple<String, Integer, Boolean>'

        when:
        exp = parseExpression(
            '''\
            tuple('hello', 42, true)[1]
            '''
        )
        type = getType(exp)
        then:
        TypesEx.getName(type) == 'Integer'
    }

    def 'should resolve tuple assignment' () {
        when:
        def exp = parseExpression(
            '''\
            (x, y, z) = tuple('hello', 42, true)
            tuple(x, y, z)
            '''
        )
        def type = getType(exp)
        then:
        TypesEx.getName(type) == 'Tuple<String, Integer, Boolean>'
    }

    def 'should allow tuple destructuring in closure' () {
        expect:
        check(
            '''\
            channel.value( tuple(1, 2, 3) ).map { x, y, z -> y }
            ''',
            null
        )
    }

    @Unroll
    def 'should check a spread-dot expression' () {
        expect:
        check(SOURCE, ERROR)

        where:
        SOURCE                          | ERROR
        "'*.txt'*.dirName"              | "Spread-dot is only supported for Iterable types"
        "files('*.txt')*.dirName"       | "Unrecognized property `dirName` for element type Path"
        "files('*.txt')*.name"          | null
        "'*.txt'*.getDirName()"         | "Spread-dot is only supported for Iterable types"
        "files('*.txt')*.getDirName()"  | "Unrecognized method `getDirName` for element type Path"
        "files('*.txt')*.toUriString()" | null
    }

}

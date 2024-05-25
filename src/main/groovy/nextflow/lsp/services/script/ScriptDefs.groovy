package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode

@CompileStatic
class ScriptDefs {

    static final List<List<String>> FUNCTIONS = [
        [
            'error',
            '''
            Throw a script runtime error with an optional error message.
            ''',
            'error ${1:message}'
        ],
        [
            'file',
            '''
            Get one or more files from a path or glob pattern. Returns a Path or list of Paths if there are multiple files.
            ''',
            'file( ${1:filePattern} )'
        ],
    ]

    static final List<List<String>> OPERATORS = [
        [
            'filter',
            '''
            The `filter` operator emits the values from a source channel that satisfy a condition, discarding all other values. The filter condition can be a literal value, a regular expression, a type qualifier, or a boolean predicate.

            [Read more](https://nextflow.io/docs/latest/operator.html#filter)
            ''',
            'filter { v -> $1 }'
        ],
        [
            'map',
            '''
            The `map` operator applies a mapping function to each value from a source channel.

            [Read more](https://nextflow.io/docs/latest/operator.html#map)
            ''',
            'map { v -> $1 }'
        ],
        [
            'reduce',
            '''
            The `reduce` operator applies an accumulator function sequentially to each value in a source channel, and emits the final accumulated value. The accumulator function takes two parameters -- the accumulated value and the *i*-th emitted value -- and it should return the accumulated result, which is passed to the next invocation with the *i+1*-th value. This process is repeated for each value in the source channel.

            [Read more](https://nextflow.io/docs/latest/operator.html#reduce)
            ''',
            'reduce( ${1:seed} ) { acc, v -> $2 }'
        ],
    ]

    static final List<ClassNode> TYPES = [
        ClassHelper.make( java.nio.file.Path ),
        ClassHelper.make( nextflow.Channel ),
        ClassHelper.make( nextflow.util.Duration ),
        ClassHelper.make( nextflow.util.MemoryUnit ),
    ]

}

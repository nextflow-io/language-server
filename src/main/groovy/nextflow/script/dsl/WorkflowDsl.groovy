package nextflow.script.dsl

import groovy.transform.CompileStatic
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel

@CompileStatic
class WorkflowDsl {

    @Operator
    @Function('''
        The `collect` operator collects all values from a source channel into a list and emits it as a single value.

        [Read more](https://nextflow.io/docs/latest/operator.html#collect)
    ''')
    DataflowWriteChannel collect(DataflowReadChannel source, Closure action=null) {
    }

    @Operator
    @Function('''
        The `concat` operator emits the values from two or more source channels into a single output channel. Each source channel is emitted in the order in which it was specified.

        [Read more](https://nextflow.io/docs/latest/operator.html#concat)
    ''')
    DataflowWriteChannel concat(DataflowReadChannel source, DataflowReadChannel... others) {
    }

    @Operator
    @Function('''
        The `count` operator computes the total number of values from a source channel and emits it.

        [Read more](https://nextflow.io/docs/latest/operator.html#count)
    ''')
    DataflowWriteChannel count(DataflowReadChannel source) {
    }

    @Operator
    @Function('''
        The `cross` operator emits every pairwise combination of two channels for which the pair has a matching key.

        [Read more](https://nextflow.io/docs/latest/operator.html#cross)
    ''')
    DataflowWriteChannel cross(DataflowReadChannel source, DataflowReadChannel other, Closure mapper=null) {
    }

    @Operator
    @Function('''
        The `distinct` operator forwards a source channel with consecutively repeated values removed, such that each emitted value is different from the preceding one.

        [Read more](https://nextflow.io/docs/latest/operator.html#distinct)
    ''')
    DataflowWriteChannel distinct(DataflowReadChannel source) {
    }

    @Operator
    @Function('''
        The `filter` operator emits the values from a source channel that satisfy a condition, discarding all other values. The filter condition can be a literal value, a regular expression, a type qualifier, or a boolean predicate.

        [Read more](https://nextflow.io/docs/latest/operator.html#filter)
    ''')
    DataflowWriteChannel filter(DataflowReadChannel source, Closure<Boolean> closure) {
    }

    @Operator
    @Function('''
        The `first` operator emits the first value from a source channel, or the first value that satisfies a condition. The condition can be a regular expression, a type qualifier (i.e. Java class), or a boolean predicate.

        [Read more](https://nextflow.io/docs/latest/operator.html#first)
    ''')
    DataflowWriteChannel first(DataflowReadChannel source, Object criteria=null) {
    }

    @Operator
    @Function('''
        The `flatMap` operator applies a mapping function to each value from a source channel.
        
        When the mapping function returns a list, each element in the list is emitted separately. When the mapping function returns a map, each key-value pair in the map is emitted separately.

        [Read more](https://nextflow.io/docs/latest/operator.html#flatmap)
    ''')
    DataflowWriteChannel flatMap(DataflowReadChannel source, Closure closure=null) {
    }

    @Operator
    @Function('''
        The `flatten` operator flattens each value from a source channel that is a list or other collection, such that each element in each collection is emitted separately. Deeply nested collections are also flattened.

        [Read more](https://nextflow.io/docs/latest/operator.html#flatten)
    ''')
    DataflowWriteChannel flatten(DataflowReadChannel source) {
    }

    @Operator
    @Function('''
        The `groupTuple` operator collects tuples from a source channel into groups based on a grouping key. A new tuple is emitted for each distinct key.

        [Read more](https://nextflow.io/docs/latest/operator.html#grouptuple)
    ''')
    DataflowWriteChannel groupTuple(DataflowReadChannel source, Map opts=null) {
    }

    @Operator
    @Function('''
        The `ifEmpty` operator emits a source channel, or a default value if the source channel is empty.

        [Read more](https://nextflow.io/docs/latest/operator.html#ifempty)
    ''')
    DataflowWriteChannel ifEmpty(DataflowReadChannel source, value) {
    }

    @Operator
    @Function('''
        The `join` operator emits the inner product of two source channels using a matching key.

        [Read more](https://nextflow.io/docs/latest/operator.html#join)
    ''')
    DataflowWriteChannel join(DataflowReadChannel left, right) {
    }

    @Operator
    @Function('''
        The `last` operator emits the last value from a source channel.

        [Read more](https://nextflow.io/docs/latest/operator.html#last)
    ''')
    DataflowWriteChannel last(DataflowReadChannel source) {
    }

    @Operator
    @Function('''
        The `map` operator applies a mapping function to each value from a source channel.

        [Read more](https://nextflow.io/docs/latest/operator.html#map)
    ''')
    DataflowWriteChannel map(DataflowReadChannel source, Closure closure) {
    }

    @Operator
    @Function('''
        The `mix` operator emits the values from two or more source channels into a single output channel.

        [Read more](https://nextflow.io/docs/latest/operator.html#mix)
    ''')
    DataflowWriteChannel mix(DataflowReadChannel source, DataflowReadChannel... others) {
    }

    @Operator
    @Function('''
        The `reduce` operator applies an accumulator function sequentially to each value from a source channel, and emits the accumulated value. The accumulator function takes two parameters -- the accumulated value and the *i*-th emitted value -- and it should return the accumulated result, which is passed to the next invocation with the *i+1*-th value. This process is repeated for each value in the source channel.

        [Read more](https://nextflow.io/docs/latest/operator.html#reduce)
    ''')
    DataflowWriteChannel reduce(DataflowReadChannel source, Object seed=null, Closure closure) {
    }

    @Operator
    @Function('''
        The `take` operator takes the first *N* values from a source channel.

        [Read more](https://nextflow.io/docs/latest/operator.html#take)
    ''')
    DataflowWriteChannel take(DataflowReadChannel source, int n) {
    }

    @Operator
    @Function('''
        The `transpose` operator transposes each tuple from a source channel by flattening any nested list in each tuple, emitting each nested value separately.

        [Read more](https://nextflow.io/docs/latest/operator.html#transpose)
    ''')
    DataflowWriteChannel transpose(DataflowReadChannel source, Map opts=null) {
    }

    @Operator
    @Function('''
        The `unique` operator emits the unique values from a source channel.

        [Read more](https://nextflow.io/docs/latest/operator.html#unique)
    ''')
    DataflowWriteChannel unique(DataflowReadChannel source, Closure comparator=null) {
    }

    @Operator
    @Function('''
        The `until` operator emits each value from a source channel until a stopping condition is satisfied.

        [Read more](https://nextflow.io/docs/latest/operator.html#until)
    ''')
    DataflowWriteChannel until(DataflowReadChannel source, Closure<Boolean> closure) {
    }

    @Operator
    @Function('''
        The `view` operator prints each value from a source channel to standard output.

        [Read more](https://nextflow.io/docs/latest/operator.html#view)
    ''')
    DataflowWriteChannel view(DataflowReadChannel source, Closure closure=null) {
    }

}

@CompileStatic
class EntryWorkflowDsl extends WorkflowDsl {

    @Constant('''
        List of positional arguments specified on the command line.
    ''')
    List<String> args

    @Constant('''
        Map of workflow parameters specified in the config file or as command line options.
    ''')
    Map<String,Object> params
}


import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Operator {
}

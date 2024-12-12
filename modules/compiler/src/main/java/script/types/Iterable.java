/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.script.types;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import nextflow.script.dsl.Description;

@Description("""
    An iterable is a common interface for collections that support iteration.

    [Read more](https://nextflow.io/docs/latest/reference/stdlib.html#iterable)
""")
public interface Iterable<E> {

    @Description("""
        Returns `true` if any value in the iterable satisfies the given condition.
    """)
    boolean any(Predicate<E> predicate);

    @Description("""
        Returns a new iterable with each value transformed by the given closure.
    """)
    <R> Iterable<R> collect(Function<E,R> mapper);

    @Description("""
        Invoke the given closure for each value in the iterable.
    """)
    void each(Consumer<E> action);

    @Description("""
        Returns `true` if every value in the iterable satisfies the given condition.
    """)
    boolean every(Predicate<E> predicate);

    @Description("""
        Returns the values in the iterable that satisfy the given condition.
    """)
    Iterable<E> findAll(Predicate<E> predicate);

    @Description("""
        Returns a new iterable with each value transformed by the given closure.
    """)
    <R> R inject(R initialValue, BiFunction<R,E,R> accumulator);

    @Description("""
        Returns the number of values in the iterable.
    """)
    int size();

    @Description("""
        Returns a sorted list of the iterable's values.
    """)
    List<E> sort();

    @Description("""
        Returns the sum of the values in the iterable. The values should support the `+` operator.
    """)
    E sum();

    @Description("""
        Converts the iterable to a set. Duplicate values are excluded.
    """)
    Set<E> toSet();

}

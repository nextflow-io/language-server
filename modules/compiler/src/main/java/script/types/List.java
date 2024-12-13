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

import nextflow.script.dsl.Description;

@Description("""
    A list is an unordered collection of elements.

    [Read more](https://nextflow.io/docs/latest/reference/stdlib.html#list)
""")
@ShimType(java.util.List.class)
public interface List<E> extends Iterable<E> {

    @Description("""
        Collates the list into a list of sub-lists of length `size`, stepping through the list `step` elements for each sub-list. If `keepRemainder` is `true`, any remaining elements are included as a partial sub-list, otherwise they are excluded.
    """)
    List<?> collate(int size, int step, boolean keepRemainder);

    @Description("""
        Returns the first element in the list. Raises an error if the list is empty.
    """)
    E first();

    @Description("""
        Returns the list of integers from 0 to *n - 1*, where *n* is the number of elements in the list.
    """)
    List<Integer> getIndices();

    @Description("""
        Returns a list of 2-tuples corresponding to the value and index of each element in the list.
    """)
    List withIndex();
    
}

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

import java.util.function.BiConsumer;

import nextflow.script.dsl.Description;

@Description("""
    A map "maps" keys to values. Each key can map to at most one value -- a map cannot contain duplicate keys.

    [Read more](https://nextflow.io/docs/latest/reference/stdlib.html#map)
""")
@ShimType(java.util.Map.class)
public interface Map<K,V> {

    @Description("""
        Invoke the given closure for each key-value pair in the map. The closure should accept two parameters corresponding to the key and value of an entry.
    """)
    void each(BiConsumer<K,V> action);

    @Description("""
        Returns a set of the key-value pairs in the map.
    """)
    Set<Entry<K,V>> entrySet();

    @Description("""
        A map entry is a key-value pair.
    """)
    interface Entry<K,V> {
        K getKey();
        V getValue();
    }

}

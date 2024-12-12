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
    A string is an immutable array of characters.

    [Read more](https://nextflow.io/docs/latest/reference/stdlib.html#string)
""")
@ShimType(java.lang.String.class)
public interface String {

    @Description("""
        Parses the string into an integer.
    """)
    Integer toInteger();

    @Description("""
        Splits the string into a list of substrings using the given delimiters. Each character in the delimiter string is treated as a separate delimiter.
    """)
    List<String> tokenize(String delimiters);

}

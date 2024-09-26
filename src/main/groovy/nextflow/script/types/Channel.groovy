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
package nextflow.script.types

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovyx.gpars.dataflow.DataflowVariable
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.script.dsl.DslType
import nextflow.script.dsl.Function

@CompileStatic
@DslType('''
    The `Channel` type provides the channel factory methods.

    [Read more](https://nextflow.io/docs/latest/reference/channel.html)
''')
class Channel {

    @Function('''
        Create a channel that emits nothing.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#empty)
    ''')
    static DataflowWriteChannel empty() {
    }

    @Deprecated
    @Function('''
        Create a channel that emits each argument.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#from)
    ''')
    static <T> DataflowWriteChannel<T> from(T... values) {
    }

    @Deprecated
    @Function('''
        Create a channel that emits each element in a collection.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#from)
    ''')
    static <T> DataflowWriteChannel<T> from(Collection<T> values) {
    }

    @Function('''
        Create a channel that emits all file pairs matching a glob pattern.

        An optional closure can be used to customize the grouping strategy.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#fromfilepairs)
    ''')
    static DataflowWriteChannel fromFilePairs(Map opts = null, pattern, Closure grouping = null) {
    }

    @Function('''
        Create a channel that emits each element in a collection.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#fromlist)
    ''')
    static <T> DataflowWriteChannel<T> fromList(Collection<T> values) {
    }

    @Function('''
        Create a channel that emits all paths matching a name or glob pattern.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#frompath)
    ''')
    static DataflowWriteChannel<Path> fromPath(Map opts = null, pattern) {
    }

    @Function('''
        Create a channel that queries the [NCBI SRA](https://www.ncbi.nlm.nih.gov/sra) database and emits all FASTQ files matching the given project or accession ids.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#fromsra)
    ''')
    static DataflowWriteChannel fromSRA(Map opts = null, query) {
    }

    @Function('''
        Create a channel that emits each argument.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#of)
    ''')
    static <T> DataflowWriteChannel<T> of(T... values) {
    }

    @Function('''
        Create a channel that emits all values in the given topic.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#topic)
    ''')
    static DataflowWriteChannel topic(String name) {
    }

    @Function('''
        Create a value channel.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#value)
    ''')
    static <T> DataflowVariable<T> value(T obj = null) {
    }

    @Function('''
        Create a channel that watches for filesystem events for all files matching the given pattern.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#watchpath)
    ''')
    static DataflowWriteChannel<Path> watchPath(filePattern, String events = 'create') {
    }

}

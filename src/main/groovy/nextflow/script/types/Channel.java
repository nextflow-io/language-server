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

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import groovy.lang.Closure;
import groovyx.gpars.dataflow.DataflowVariable;
import groovyx.gpars.dataflow.DataflowWriteChannel;
import nextflow.script.dsl.Description;

@Description("""
    The `Channel` type provides the channel factory methods.

    [Read more](https://nextflow.io/docs/latest/reference/channel.html)
""")
public class Channel {

    protected static Channel instance;

    @Description("""
        Create a channel that emits nothing.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#empty)
    """)
    public static DataflowWriteChannel empty() {
        return instance.empty();
    }

    @Deprecated
    @Description("""
        Create a channel that emits each argument.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#from)
    """)
    public static <T> DataflowWriteChannel<T> from(T... values) {
        return instance.from(values);
    }

    @Deprecated
    @Description("""
        Create a channel that emits each element in a collection.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#from)
    """)
    public static <T> DataflowWriteChannel<T> from(Collection<T> values) {
        return instance.from(values);
    }

    @Description("""
        Create a channel that emits all file pairs matching a glob pattern.

        An optional closure can be used to customize the grouping strategy.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#fromfilepairs)
    """)
    public static DataflowWriteChannel fromFilePairs(Map opts, String pattern, Closure grouping) {
        return instance.fromFilePairs(opts, pattern, grouping);
    }

    @Description("""
        Create a channel that emits each element in a collection.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#fromlist)
    """)
    public static <T> DataflowWriteChannel<T> fromList(Collection<T> values) {
        return instance.fromList(values);
    }

    @Description("""
        Create a channel that emits all paths matching a name or glob pattern.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#frompath)
    """)
    public static DataflowWriteChannel<Path> fromPath(Map opts, String pattern) {
        return instance.fromPath(opts, pattern);
    }

    @Description("""
        Create a channel that queries the [NCBI SRA](https://www.ncbi.nlm.nih.gov/sra) database and emits all FASTQ files matching the given project or accession ids.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#fromsra)
    """)
    public static DataflowWriteChannel fromSRA(Map opts, String query) {
        return instance.fromSRA(opts, query);
    }

    @Description("""
        Create a channel that emits each argument.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#of)
    """)
    public static <T> DataflowWriteChannel<T> of(T... values) {
        return instance.of(values);
    }

    @Description("""
        Create a channel that emits all values in the given topic.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#topic)
    """)
    public static DataflowWriteChannel topic(String name) {
        return instance.topic(name);
    }

    @Description("""
        Create a value channel.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#value)
    """)
    public static <T> DataflowVariable<T> value(T value) {
        return instance.value(value);
    }

    @Description("""
        Create a channel that watches for filesystem events for all files matching the given pattern.

        [Read more](https://nextflow.io/docs/latest/reference/channel.html#watchpath)
    """)
    public static DataflowWriteChannel<Path> watchPath(String filePattern, String events) {
        return instance.watchPath(filePattern, events);
    }

}

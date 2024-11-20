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

public interface ChannelFactory {

    DataflowWriteChannel empty();

    <T> DataflowWriteChannel<T> from(T... values);

    <T> DataflowWriteChannel<T> from(Collection<T> values);

    DataflowWriteChannel fromFilePairs(Map<String,?> opts, String pattern, Closure grouping);

    <T> DataflowWriteChannel<T> fromList(Collection<T> values);

    DataflowWriteChannel<Path> fromPath(Map<String,?> opts, String pattern);

    DataflowWriteChannel fromSRA(Map<String,?> opts, String query);

    <T> DataflowWriteChannel<T> of(T... values);

    DataflowWriteChannel topic(String name);

    <T> DataflowVariable<T> value(T value);

    DataflowWriteChannel<Path> watchPath(String filePattern, String events);

}

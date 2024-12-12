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

import nextflow.script.dsl.Constant;
import nextflow.script.dsl.Description;

@Description("""
    A Path is a handle for hierarchichal paths such as local files and directories, HTTP/FTP URLs, and object storage paths (e.g. Amazon S3).

    [Read more](https://nextflow.io/docs/latest/reference/stdlib.html#path)
""")
@ShimType(java.nio.file.Path.class)
public interface Path {

    @Constant("text")
    @Description("""
        Returns the file content as a string value.
    """)
    String getText();

    @Description("""
        Reads the file line by line and returns the content as a list of strings.
    """)
    List<String> readLines();

    @Description("""
        Splits a CSV file into a list of records.
    """)
    List<?> splitCsv();

    @Description("""
        Splits a text file into a list of lines.
    """)
    List<String> splitText();

}

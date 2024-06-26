/*
 * Copyright 2024, Seqera Labs
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
package nextflow.lsp.util

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

@CompileStatic
class Ranges {

    static boolean contains(Range range, Position position) {
        return Positions.COMPARATOR.compare(position, range.start) >= 0
            && Positions.COMPARATOR.compare(position, range.end) <= 0
    }

    static boolean intersect(Range r1, Range r2) {
        return contains(r1, r2.start) || contains(r1, r2.end)
    }

    static String getSubstring(String string, Range range, int maxLines = 0) {
        final reader = new BufferedReader(new StringReader(string))
        final builder = new StringBuilder()
        final start = range.start
        final end = range.end
        int startLine = start.getLine()
        int startChar = start.getCharacter()
        int endLine = end.getLine()
        int endChar = end.getCharacter()
        int lineCount = 1 + (endLine - startLine)

        if( maxLines > 0 && lineCount > maxLines ) {
            endLine = startLine + maxLines - 1
            endChar = 0
        }

        try {
            // skip to the start position
            for( int i = 0; i < startLine; i++ )
                reader.readLine()
            for( int i = 0; i < startChar; i++ )
                reader.read()

            // read each full line in the range
            int endCharStart = startChar
            int maxLineBreaks = endLine - startLine
            if( maxLineBreaks > 0 ) {
                endCharStart = 0
                int readLines = 0
                while( readLines < maxLineBreaks ) {
                    final character = (char) reader.read()
                    if( character == '\n' )
                        readLines++
                    builder.append(character)
                }
            }

            // the remaining characters on the final line
            for( int i = endCharStart; i < endChar; i++ )
                builder.append((char) reader.read())
        }
        catch( IOException e ) {
            return null
        }

        try {
            reader.close()
        }
        catch( IOException e ) {
        }

        return builder.toString()
    }

}

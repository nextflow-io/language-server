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
package nextflow.lsp.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import org.eclipse.lsp4j.Range;

public class Ranges {

    public static String getSubstring(String string, Range range) {
        return getSubstring(string, range, 0);
    }

    public static String getSubstring(String string, Range range, int maxLines) {
        var reader = new BufferedReader(new StringReader(string));
        var builder = new StringBuilder();
        var start = range.getStart();
        var end = range.getEnd();
        int startLine = start.getLine();
        int startChar = start.getCharacter();
        int endLine = end.getLine();
        int endChar = end.getCharacter();
        int lineCount = 1 + (endLine - startLine);

        if( maxLines > 0 && lineCount > maxLines ) {
            endLine = startLine + maxLines - 1;
            endChar = 0;
        }

        try {
            // skip to the start position
            for( int i = 0; i < startLine; i++ )
                reader.readLine();
            for( int i = 0; i < startChar; i++ )
                reader.read();

            // read each full line in the range
            int endCharStart = startChar;
            int maxLineBreaks = endLine - startLine;
            if( maxLineBreaks > 0 ) {
                endCharStart = 0;
                int readLines = 0;
                while( readLines < maxLineBreaks ) {
                    var character = (char) reader.read();
                    if( character == '\n' )
                        readLines++;
                    builder.append(character);
                }
            }

            // the remaining characters on the var line
            for( int i = endCharStart; i < endChar; i++ )
                builder.append((char) reader.read());
        }
        catch( IOException e ) {
            return null;
        }

        try {
            reader.close();
        }
        catch( IOException e ) {
        }

        return builder.toString();
    }

}

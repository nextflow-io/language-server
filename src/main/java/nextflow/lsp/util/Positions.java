/*
 * Copyright 2024-2025, Seqera Labs
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
import java.util.Comparator;

import org.eclipse.lsp4j.Position;

public class Positions {

    public static final Comparator<Position> COMPARATOR = (Position a, Position b) -> {
        return a.getLine() != b.getLine()
            ? a.getLine() - b.getLine()
            : a.getCharacter() - b.getCharacter();
    };

    /**
     * Map a two-dimensional position (line and character) to a
     * one-dimensional index in a string buffer.
     *
     * The character is counted in UTF-16 code units, and lines are
     * terminated by "\n", "\r\n" or "\r". Out-of-range positions are
     * clamped: a character past the end of its line maps to the line
     * end, and a line past the last line maps to the end of the text.
     *
     * @param string
     * @param position
     */
    public static int getOffset(String string, Position position) {
        var length = string.length();
        var line = Math.max(0, position.getLine());
        var character = Math.max(0, position.getCharacter());

        // find the start of the line
        int lineStart = 0;
        for( int i = 0; i < line; i++ ) {
            var next = nextLineStart(string, lineStart);
            if( next == -1 )
                return length;
            lineStart = next;
        }

        // clamp the character to the end of the line
        var lineEnd = lineStart;
        while( lineEnd < length && string.charAt(lineEnd) != '\n' && string.charAt(lineEnd) != '\r' )
            lineEnd++;
        return character < lineEnd - lineStart
            ? lineStart + character
            : lineEnd;
    }

    private static int nextLineStart(String string, int from) {
        var length = string.length();
        for( int i = from; i < length; i++ ) {
            var c = string.charAt(i);
            if( c == '\n' )
                return i + 1;
            if( c == '\r' )
                return i + 1 < length && string.charAt(i + 1) == '\n' ? i + 2 : i + 1;
        }
        return -1;
    }

    /**
     * Map a one-dimensional index in a string buffer to a
     * two-dimensional position (line and character).
     *
     * @param string
     * @param offset
     */
    public static Position getPosition(String string, int offset) {
        int line = 0;
        int character = 0;
        if( offset > 0 ) {
            var reader = new BufferedReader(new StringReader(string));
            try {
                while( true ) {
                    var b = reader.read();
                    if( b == -1 )
                        return new Position(-1, -1);
                    offset--;
                    character++;
                    if( (char) b == '\n' ) {
                        line++;
                        character = 0;
                    }
                    if( offset == 0 )
                        break;
                }
            }
            catch( IOException e ) {
                return new Position(-1, -1);
            }

            try {
                reader.close();
            }
            catch( IOException e ) {
            }
        }
        return new Position(line, character);
    }

}

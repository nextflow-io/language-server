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
     * @param string
     * @param position
     */
    public static int getOffset(String string, Position position) {
        int line = position.getLine();
        int character = position.getCharacter();
        int currentIndex = 0;
        if( line > 0 ) {
            var reader = new BufferedReader(new StringReader(string));
            try {
                int readLines = 0;
                while( true ) {
                    var b = reader.read();
                    if( b == -1 )
                        return -1;
                    currentIndex++;
                    if( (char) b == '\n' ) {
                        readLines++;
                        if( readLines == line )
                            break;
                    }
                }
            }
            catch( IOException e ) {
                return -1;
            }

            try {
                reader.close();
            }
            catch( IOException e ) {
            }
        }
        return currentIndex + character;
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

package nextflow.lsp.util

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Position

@CompileStatic
class Positions {

    static final Comparator<Position> COMPARATOR = (Position a, Position b) -> {
        a.getLine() != b.getLine()
            ? a.getLine() - b.getLine()
            : a.getCharacter() - b.getCharacter()
    }

    static boolean isValid(Position p) {
        return p.getLine() >= 0 || p.getCharacter() >= 0
    }

    /**
     * Map a two-dimensional position (line and character) to a
     * one-dimensional index in a string buffer.
     *
     * @param string
     * @param position
     */
    static int getOffset(String string, Position position) {
        int line = position.getLine()
        int character = position.getCharacter()
        int currentIndex = 0
        if( line > 0 ) {
            final reader = new BufferedReader(new StringReader(string))
            try {
                int readLines = 0
                while( true ) {
                    final currentChar = (char) reader.read()
                    if( currentChar == -1 )
                        return -1
                    currentIndex++
                    if( currentChar == '\n' ) {
                        readLines++
                        if( readLines == line )
                            break
                    }
                }
            }
            catch( IOException e ) {
                return -1
            }

            try {
                reader.close()
            }
            catch( IOException e ) {
            }
        }
        return currentIndex + character
    }

    /**
     * Map a one-dimensional index in a string buffer to a
     * two-dimensional position (line and character).
     *
     * @param string
     * @param offset
     */
    static Position getPosition(String string, int offset) {
        int line = 0
        int character = 0
        if( offset > 0 ) {
            final reader = new BufferedReader(new StringReader(string))
            try {
                while( true ) {
                    final currentChar = (char) reader.read()
                    if( currentChar == -1 )
                        return new Position(-1, -1)
                    offset--
                    character++
                    if( currentChar == '\n' ) {
                        line++
                        character = 0
                    }
                    if( offset == 0 )
                        break
                }
            }
            catch( IOException e ) {
                return new Position(-1, -1)
            }

            try {
                reader.close()
            }
            catch( IOException e ) {
            }
        }
        return new Position(line, character)
    }

}

package nextflow.lsp.util

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Position

@CompileStatic
class Positions {

    static final Comparator<Position> COMPARATOR = (Position p1, Position p2) -> {
        p1.getLine() != p2.getLine()
            ? p1.getLine() - p2.getLine()
            : p1.getCharacter() - p2.getCharacter()
    }

    static boolean isValid(Position p) {
        return p.getLine() >= 0 || p.getCharacter() >= 0
    }

    /**
     * Map a two-dimensional position (line and column) to a
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

}

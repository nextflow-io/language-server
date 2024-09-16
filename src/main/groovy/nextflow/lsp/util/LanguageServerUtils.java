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

import java.net.URI;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Utility methods for mapping compiler data structures
 * to LSP data structures.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class LanguageServerUtils {

    public static int groovyToLspLine(int groovyLine) {
        return groovyLine != -1 ? groovyLine - 1 : -1;
    }

    public static int groovyToLspCharacter(int groovyColumn) {
        return groovyColumn > 0 ? groovyColumn - 1 : 0;
    }

    public static Position groovyToLspPosition(int groovyLine, int groovyColumn) {
        int lspLine = groovyToLspLine(groovyLine);
        if( lspLine == -1 )
            return null;
        return new Position(
                lspLine,
                groovyToLspCharacter(groovyColumn));
    }

    public static Range syntaxExceptionToRange(SyntaxException exception) {
        return new Range(
                groovyToLspPosition(exception.getStartLine(), exception.getStartColumn()),
                groovyToLspPosition(exception.getEndLine(), exception.getEndColumn()));
    }

    public static Range astNodeToRange(ASTNode node) {
        var start = groovyToLspPosition(node.getLineNumber(), node.getColumnNumber());
        if( start == null )
            return null;
        var end = groovyToLspPosition(node.getLastLineNumber(), node.getLastColumnNumber());
        if( end == null )
            end = start;
        return new Range(start, end);
    }

    public static Location astNodeToLocation(ASTNode node, URI uri) {
        var range = astNodeToRange(node);
        if( range == null )
            return null;
        return new Location(uri.toString(), range);
    }

}

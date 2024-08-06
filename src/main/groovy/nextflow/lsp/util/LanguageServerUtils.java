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

import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;

/**
 * Utility methods for mapping compiler data structures
 * to LSP data structures.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class LanguageServerUtils {

    public static Position createGroovyPosition(int groovyLine, int groovyColumn) {
        int lspLine = groovyLine > 0 ? groovyLine - 1 : groovyLine;
        int lspColumn = groovyColumn > 0 ? groovyColumn - 1 : groovyColumn;
        return new Position(lspLine, lspColumn);
    }

    public static Range syntaxExceptionToRange(SyntaxException exception) {
        return new Range(
                createGroovyPosition(exception.getStartLine(), exception.getStartColumn()),
                createGroovyPosition(exception.getEndLine(), exception.getEndColumn()));
    }

    public static Range astNodeToRange(ASTNode node) {
        return new Range(
                createGroovyPosition(node.getLineNumber(), node.getColumnNumber()),
                createGroovyPosition(node.getLastLineNumber(), node.getLastColumnNumber()));
    }

    public static CompletionItemKind astNodeToCompletionItemKind(ASTNode node) {
        if( node instanceof ClassNode ) {
            if( ((ClassNode) node).isEnum() )
                return CompletionItemKind.Enum;
            else
                return CompletionItemKind.Class;
        }
        else if( node instanceof MethodNode ) {
            return CompletionItemKind.Method;
        }
        else if( node instanceof Variable ) {
            if( node instanceof FieldNode || node instanceof PropertyNode )
                return CompletionItemKind.Field;
            else
                return CompletionItemKind.Variable;
        }
        return CompletionItemKind.Property;
    }

    public static SymbolKind astNodeToSymbolKind(ASTNode node) {
        if( node instanceof ClassNode ) {
            if( ((ClassNode) node).isEnum() )
                return SymbolKind.Enum;
            else
                return SymbolKind.Class;
        }
        else if( node instanceof MethodNode ) {
            return SymbolKind.Method;
        }
        else if( node instanceof Variable ) {
            if( node instanceof FieldNode || node instanceof PropertyNode )
                return SymbolKind.Field;
            else
                return SymbolKind.Variable;
        }
        return SymbolKind.Property;
    }

    public static Location astNodeToLocation(ASTNode node, URI uri) {
        return new Location(uri.toString(), astNodeToRange(node));
    }

    public static SymbolInformation astNodeToSymbolInformation(ClassNode node, URI uri) {
        return new SymbolInformation(
                node.getName(),
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                null);
    }

    public static SymbolInformation astNodeToSymbolInformation(MethodNode node, URI uri) {
        return new SymbolInformation(
                node.getName(),
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                null);
    }

    public static SymbolInformation astNodeToSymbolInformation(WorkflowNode node, URI uri) {
        return new SymbolInformation(
                node.getName() != null ? node.getName() : "<entry>",
                astNodeToSymbolKind(node),
                astNodeToLocation(node, uri),
                null);
    }

    public static SymbolInformation astNodeToSymbolInformation(Variable node, URI uri, String parentName) {
        if( !(node instanceof ASTNode) )
            return null;

        return new SymbolInformation(
                node.getName(),
                astNodeToSymbolKind((ASTNode) node),
                astNodeToLocation((ASTNode) node, uri),
                parentName);
    }

}

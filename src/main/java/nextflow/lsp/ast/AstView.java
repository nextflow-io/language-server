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
package nextflow.lsp.ast;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.Position;

/**
 * Read-only view of the built files of one kind in a project.
 *
 * A view shows the latest built state of every file. The files that
 * a request needs are guaranteed to be fresh; other files are as last
 * built, and may refer to superseded nodes of a rebuilt dependency.
 *
 * Valid only during a request body and only on the engine thread.
 * Every method throws an IllegalStateException otherwise.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface AstView {

    /**
     * Get the URIs of the built files, in sorted order.
     */
    Set<URI> getUris();

    Collection<SourceUnit> getSourceUnits();

    /**
     * Get the source unit of a file, or null if the file is unknown.
     *
     * @param uri
     */
    SourceUnit getSourceUnit(URI uri);

    boolean hasAST(URI uri);

    /**
     * Check whether a file has errors, including an internal error
     * while building it.
     *
     * @param uri
     */
    boolean hasErrors(URI uri);

    boolean hasWarnings(URI uri);

    List<SyntaxException> getErrors(URI uri);

    List<WarningMessage> getWarnings(URI uri);

    /**
     * Get the indexed nodes of every file.
     */
    Collection<ASTNode> getNodes();

    Collection<ASTNode> getNodes(URI uri);

    /**
     * Get the file that contains a node, or null if the node is not
     * indexed (built-in, plugin or superseded).
     *
     * @param node
     */
    URI getURI(ASTNode node);

    ASTNode getParent(ASTNode child);

    /**
     * Get the most specific node at a given position in a file.
     *
     * @param uri
     * @param position
     */
    ASTNode getNodeAtPosition(URI uri, Position position);

    /**
     * Get the node at a given position in a file and its ancestors,
     * innermost first.
     *
     * @param uri
     * @param position
     */
    List<ASTNode> getNodesAtPosition(URI uri, Position position);

    /**
     * Check whether a position is inside a comment. Comments are not
     * part of the AST, so a position in a comment otherwise resolves
     * to the enclosing declaration.
     *
     * @param uri
     * @param position
     */
    boolean isCommentPosition(URI uri, Position position);

    /**
     * Get the source text of a node.
     *
     * @param node
     * @param leadingIndent
     * @param maxLines
     */
    String getSourceText(ASTNode node, boolean leadingIndent, int maxLines);

    String getSourceText(ASTNode node);

}

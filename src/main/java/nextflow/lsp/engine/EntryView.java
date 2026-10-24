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
package nextflow.lsp.engine;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nextflow.lsp.ast.AstView;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Positions;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import nextflow.script.formatter.Comment;
import nextflow.script.formatter.Comments;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.util.Ranges;

/**
 * View of the built entries of one kind in a project.
 *
 * The state of the engine doesn't change during a request body, so
 * the entries are collected once, on first use.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
abstract class EntryView implements AstView {

    protected final Engine engine;

    protected final Project project;

    private final FileKind kind;

    private Map<URI, FileEntry> entries;

    EntryView(Engine engine, Project project, FileKind kind) {
        this.engine = engine;
        this.project = project;
        this.kind = kind;
    }

    /**
     * Get the built entries of this kind, sorted by URI.
     */
    protected Map<URI, FileEntry> entries() {
        engine.checkEngineThread();
        if( entries == null ) {
            var result = new LinkedHashMap<URI, FileEntry>();
            for( var entry : project.builtEntries(kind) )
                result.put(entry.uri, entry);
            entries = Collections.unmodifiableMap(result);
        }
        return entries;
    }

    /**
     * Get the entry of a file, or null if it is unknown.
     *
     * @param uri
     */
    protected FileEntry entry(URI uri) {
        if( uri == null )
            return null;
        var entries = entries();
        var result = entries.get(uri);
        if( result != null )
            return result;
        return Uris.canonical(uri).map(entries::get).orElse(null);
    }

    /**
     * Get the entry that contains an indexed node, if it is of this kind.
     *
     * @param node
     */
    private FileEntry ownerOf(ASTNode node) {
        engine.checkEngineThread();
        var owner = project.ownerOf(node);
        return owner != null && owner.kind == kind ? owner : null;
    }

    @Override
    public Set<URI> getUris() {
        return entries().keySet();
    }

    @Override
    public SourceUnit getSourceUnit(URI uri) {
        var entry = entry(uri);
        return entry != null ? entry.unit : null;
    }

    @Override
    public boolean hasAST(URI uri) {
        var sourceUnit = getSourceUnit(uri);
        return sourceUnit != null && sourceUnit.getAST() != null;
    }

    @Override
    public boolean hasErrors(URI uri) {
        var entry = entry(uri);
        return entry != null && (!entry.errors.isEmpty() || entry.internalError != null);
    }

    @Override
    public List<SyntaxException> getErrors(URI uri) {
        var entry = entry(uri);
        return entry != null ? entry.errors : List.of();
    }

    @Override
    public List<WarningMessage> getWarnings(URI uri) {
        var entry = entry(uri);
        return entry != null ? entry.warnings : List.of();
    }

    @Override
    public boolean hasSyntaxErrors(URI uri) {
        return getErrors(uri).stream()
            .anyMatch(error -> error instanceof PhaseAware pa ? pa.getPhase() == Phases.SYNTAX : true);
    }

    @Override
    public Collection<ASTNode> getNodes() {
        var result = new ArrayList<ASTNode>();
        for( var entry : entries().values() )
            result.addAll(entry.nodes);
        return result;
    }

    @Override
    public Collection<ASTNode> getNodes(URI uri) {
        var entry = entry(uri);
        return entry != null ? entry.nodes : List.of();
    }

    @Override
    public URI getURI(ASTNode node) {
        var owner = ownerOf(node);
        return owner != null ? owner.uri : null;
    }

    @Override
    public ASTNode getParent(ASTNode child) {
        if( child == null )
            return null;
        var owner = ownerOf(child);
        return owner != null ? owner.parents.get(child) : null;
    }

    @Override
    public ASTNode getNodeAtPosition(URI uri, Position position) {
        var entry = entry(uri);
        if( entry == null )
            return null;
        var nodeToRange = new HashMap<ASTNode, Range>();
        return entry.nodes.stream()
            .filter((node) -> {
                if( node.getLineNumber() == -1 )
                    return false;
                var range = LanguageServerUtils.astNodeToRange(node);
                if( range == null )
                    return false;
                if( !Ranges.containsPosition(range, position) )
                    return false;
                nodeToRange.put(node, range);
                return true;
            })
            .sorted((n1, n2) -> {
                // select node with later start position
                var r1 = nodeToRange.get(n1);
                var r2 = nodeToRange.get(n2);
                var cmpStart = Positions.COMPARATOR.compare(r1.getStart(), r2.getStart());
                if( cmpStart != 0 )
                    return -cmpStart;
                // select node with earlier end position
                var cmpEnd = Positions.COMPARATOR.compare(r1.getEnd(), r2.getEnd());
                if( cmpEnd != 0 )
                    return cmpEnd;
                // select the most descendant node
                if( contains(n1, n2) )
                    return 1;
                if( contains(n2, n1) )
                    return -1;
                return 0;
            })
            .findFirst().orElse(null);
    }

    @Override
    public List<ASTNode> getNodesAtPosition(URI uri, Position position) {
        var result = new ArrayList<ASTNode>();
        var current = getNodeAtPosition(uri, position);
        while( current != null ) {
            result.add(current);
            current = getParent(current);
        }
        return result;
    }

    /**
     * Determine whether a node is a direct or indirect parent of
     * another node.
     *
     * @param ancestor
     * @param descendant
     */
    private boolean contains(ASTNode ancestor, ASTNode descendant) {
        var current = getParent(descendant);
        while( current != null ) {
            if( current == ancestor )
                return true;
            current = getParent(current);
        }
        return false;
    }

    @Override
    public boolean isCommentPosition(URI uri, Position position) {
        var sourceUnit = getSourceUnit(uri);
        var moduleNode = sourceUnit != null ? sourceUnit.getAST() : null;
        if( moduleNode == null )
            return false;
        var comments = (Comments) moduleNode.getNodeMetaData(ASTNodeMarker.COMMENTS);
        if( comments == null )
            return false;
        var offset = Comment.position(position.getLine() + 1, position.getCharacter() + 1);
        return comments.getComments().stream().anyMatch(c -> c.start() <= offset && offset <= c.end());
    }

    @Override
    public String getSourceText(ASTNode node, boolean leadingIndent, int maxLines) {
        var uri = getURI(node);
        if( uri == null )
            return null;
        var sourceUnit = getSourceUnit(uri);
        if( sourceUnit == null )
            return null;
        var builder = new StringBuilder();
        var first = node.getLineNumber();
        var last = node.getLastLineNumber();
        var firstCol = node.getColumnNumber();
        var lastCol = node.getLastColumnNumber();
        var lastWithMax = maxLines != -1 && first + maxLines < last
            ? first + maxLines - 1
            : last;
        for( int i = first; i <= lastWithMax; i++ ) {
            var line = sourceUnit.getSource().getLine(i, null);

            if( i == first && leadingIndent ) {
                int k = 0;
                while( k < line.length() && line.charAt(k) == ' ' )
                    k++;
                builder.append( line.substring(0, k) );
            }

            var begin = (i == first) ? firstCol - 1 : 0;
            var end = (i == last) ? lastCol - 1 : line.length();
            builder.append( line.substring(begin, end) );
            builder.append('\n');
        }

        return builder.toString();
    }

    @Override
    public String getSourceText(ASTNode node) {
        return getSourceText(node, true, -1);
    }

}

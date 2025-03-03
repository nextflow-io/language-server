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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import nextflow.lsp.file.FileCache;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Positions;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.util.Ranges;

/**
 * Cache the AST for each compiled source file for
 * efficient querying.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public abstract class ASTNodeCache {

    private Map<URI, SourceUnit> sourcesByUri = new HashMap<>();

    private Map<URI, List<SyntaxException>> errorsByUri = new HashMap<>();

    private Map<URI, List<WarningMessage>> warningsByUri = new HashMap<>();

    private Map<URI, Set<ASTNode>> nodesByURI = new HashMap<>();

    private Map<ASTNode, LookupData> lookup = new IdentityHashMap<>();

    /**
     * Clear the cache.
     */
    public void clear() {
        sourcesByUri.clear();
        errorsByUri.clear();
        warningsByUri.clear();
        nodesByURI.clear();
        lookup.clear();
    }

    /**
     * Update the cache for a set of source files.
     *
     * @param uris
     * @param fileCache
     */
    public Set<URI> update(Set<URI> uris, FileCache fileCache) {
        // invalidate cache for each source file
        for( var uri : uris ) {
            var nodes = nodesByURI.remove(uri);
            if( nodes != null ) {
                for( var node : nodes )
                    lookup.remove(node);
            }
            sourcesByUri.remove(uri);
            errorsByUri.put(uri, new ArrayList<>());
            warningsByUri.put(uri, new ArrayList<>());
        }

        // parse source files
        var sources = uris.parallelStream()
            .map(uri -> buildAST(uri, fileCache))
            .filter(sourceUnit -> sourceUnit != null)
            .sequential()
            .collect(Collectors.toSet());

        // update ast node cache
        for( var sourceUnit : sources ) {
            var uri = sourceUnit.getSource().getURI();
            sourcesByUri.put(uri, sourceUnit);

            var parents = visitParents(sourceUnit);
            nodesByURI.put(uri, parents.keySet());

            for( var node : parents.keySet() ) {
                var parent = parents.get(node);
                lookup.put(node, new LookupData(uri, parent));
            }
        }

        // perform additional ast analysis
        var changedUris = visitAST(uris);

        // update diagnostics cache
        for( var uri : changedUris ) {
            var sourceUnit = sourcesByUri.get(uri);
            if( sourceUnit == null )
                continue;

            var errors = new ArrayList<SyntaxException>();
            var errorMessages = sourceUnit.getErrorCollector().getErrors();
            if( errorMessages != null ) {
                for( var message : errorMessages ) {
                    if( message instanceof SyntaxErrorMessage sem )
                        errors.add(sem.getCause());
                }
            }
            errorsByUri.put(uri, errors);

            var warnings = new ArrayList<WarningMessage>();
            var warningMessages = sourceUnit.getErrorCollector().getWarnings();
            if( warningMessages != null ) {
                for( var warning : warningMessages )
                    warnings.add(warning);
            }
            warningsByUri.put(uri, warnings);
        }

        // return the set of all invalidated files
        var result = new HashSet<URI>();
        result.addAll(uris);
        result.addAll(changedUris);
        return result;
    }

    /**
     * Parse the AST for a set of source files.
     *
     * @param uri
     * @param fileCache
     */
    protected abstract SourceUnit buildAST(URI uri, FileCache fileCache);

    /**
     * Visit the AST of a source file and retrieve the set of relevant
     * nodes and their corresponding parents.
     *
     * @param sourceUnit
     */
    protected abstract Map<ASTNode, ASTNode> visitParents(SourceUnit sourceUnit);

    /**
     * Perform additional AST analysis for a set of source files.
     * Return the set of files whose errors have changed.
     *
     * @param uris
     */
    protected abstract Set<URI> visitAST(Set<URI> uris);

    /**
     * Get the list of uris.
     */
    public Set<URI> getUris() {
        return sourcesByUri.keySet();
    }

    /**
     * Get the list of source units for all cached files.
     */
    public Collection<SourceUnit> getSourceUnits() {
        return sourcesByUri.values();
    }

    /**
     * Get the source unit for a given file.
     *
     * @param uri
     */
    public SourceUnit getSourceUnit(URI uri) {
        return sourcesByUri.get(uri);
    }

    /**
     * Check whether an AST is defined for a given file.
     *
     * @param uri
     */
    public boolean hasAST(URI uri) {
        var sourceUnit = sourcesByUri.get(uri);
        return sourceUnit != null && sourceUnit.getAST() != null;
    }

    /**
     * Check whether a source file has any errors.
     *
     * @param uri
     */
    public boolean hasErrors(URI uri) {
        var errors = errorsByUri.get(uri);
        return errors != null && errors.size() > 0;
    }

    /**
     * Check whether a source file has any warnings.
     *
     * @param uri
     */
    public boolean hasWarnings(URI uri) {
        var warnings = warningsByUri.get(uri);
        return warnings != null && warnings.size() > 0;
    }

    /**
     * Get the list of errors for a source file.
     */
    public List<SyntaxException> getErrors(URI uri) {
        var errors = errorsByUri.get(uri);
        return errors != null ? errors : Collections.emptyList();
    }

    /**
     * Get the list of warnings for a source file.
     */
    public List<WarningMessage> getWarnings(URI uri) {
        var warnings = warningsByUri.get(uri);
        return warnings != null ? warnings : Collections.emptyList();
    }

    /**
     * Get the list of ast nodes across all cached files.
     */
    public Collection<ASTNode> getNodes() {
        var result = new ArrayList<ASTNode>();
        for( var nodes : nodesByURI.values() )
            result.addAll(nodes);
        return result;
    }

    /**
     * Get the list of ast nodes for a given file.
     *
     * @param uri
     */
    public Collection<ASTNode> getNodes(URI uri) {
        return nodesByURI.getOrDefault(uri, Collections.emptySet());
    }

    /**
     * Get the file that contains an ast node.
     *
     * @param node
     */
    public URI getURI(ASTNode node) {
        var lookupData = lookup.get(node);
        return lookupData != null ? lookupData.uri : null;
    }

    /**
     * Get the most specific ast node at a given location in a file.
     *
     * @param uri
     * @param line
     * @param column
     */
    public ASTNode getNodeAtLineAndColumn(URI uri, int line, int column) {
        var position = new Position(line, column);
        var nodeToRange = new HashMap<ASTNode, Range>();
        var nodes = nodesByURI.get(uri);
        if( nodes == null )
            return null;

        return nodes.stream()
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
                var p1 = nodeToRange.get(n1);
                var p2 = nodeToRange.get(n2);
                var cmpStart = Positions.COMPARATOR.compare(p1.getStart(), p2.getStart());
                if( cmpStart != 0 )
                    return -cmpStart;
                // select node with earlier end position
                var cmpEnd = Positions.COMPARATOR.compare(p1.getEnd(), p2.getEnd());
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

    /**
     * Get the tree of nodes at a given location in a file.
     *
     * @param uri
     * @param line
     * @param column
     */
    public List<ASTNode> getNodesAtLineAndColumn(URI uri, int line, int column) {
        var offsetNode = getNodeAtLineAndColumn(uri, line, column);
        var result = new ArrayList<ASTNode>();
        ASTNode current = offsetNode;
        while( current != null ) {
            result.add(current);
            current = getParent(current);
        }
        return result;
    }

    /**
     * Determine whether an ast node is a direct or indirect
     * parent of another node.
     *
     * @param ancestor
     * @param descendant
     */
    private boolean contains(ASTNode ancestor, ASTNode descendant) {
        ASTNode current = getParent(descendant);
        while( current != null ) {
            if( current == ancestor )
                return true;
            current = getParent(current);
        }
        return false;
    }

    /**
     * Get the parent of a given ast node.
     *
     * @param child
     */
    public ASTNode getParent(ASTNode child) {
        if( child == null )
            return null;
        var lookupData = lookup.get(child);
        return lookupData != null ? lookupData.parent : null;
    }

    /**
     * Get the source text for an AST node.
     *
     * @param node
     * @param leadingIndent
     * @param maxLines
     */
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

    public String getSourceText(ASTNode node) {
        return getSourceText(node, true, -1);
    }

    private static record LookupData(
        URI uri,
        ASTNode parent
    ) {
    }

}

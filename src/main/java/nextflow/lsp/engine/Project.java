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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import nextflow.lsp.compiler.LanguageServerCompiler;
import nextflow.util.PathUtils;
import org.codehaus.groovy.ast.ASTNode;

/**
 * The managed files of a workspace folder (or of the default
 * project, which has no root), with their compilers, dependency
 * graph and index status.
 *
 * Freshness is computed from build stamps (see {@link FileEntry})
 * on every query, so there is no cached state to invalidate.
 *
 * Engine-confined.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class Project {

    enum IndexStatus {
        /** Waiting for the client to send `initialized`. */
        NOT_STARTED,
        /** The directory walk is running. */
        WALKING,
        /** Discovered files are being built. */
        BUILDING,
        /** Every file has been built at least once. */
        READY
    }

    final String name;

    final Path root;

    private final Workspace workspace;

    LanguageServerCompiler scriptCompiler;

    final LanguageServerCompiler configCompiler;

    private final Map<URI, FileEntry> files = new LinkedHashMap<>();

    final DependencyGraph graph = new DependencyGraph();

    final Map<ASTNode, FileEntry> nodeOwner = new IdentityHashMap<>();

    IndexStatus index;

    long walkGeneration;

    // roots of the workspace folders nested in this one
    Set<Path> innerRoots = Set.of();

    // files created or loaded since the current walk started, which
    // the walk result may not include
    final Set<URI> addedDuringWalk = new HashSet<>();

    Project(String name, Path root, Workspace workspace) {
        this.name = name;
        this.root = root;
        this.workspace = workspace;
        this.scriptCompiler = LanguageServerCompiler.forScripts(root);
        this.configCompiler = LanguageServerCompiler.forConfigs();
        this.index = root != null ? IndexStatus.NOT_STARTED : IndexStatus.READY;
    }

    // -- read API

    /**
     * The workspace folder name, or "" for the default project.
     */
    String name() {
        return name;
    }

    /**
     * The workspace folder, or null for the default project.
     */
    Path root() {
        return root;
    }

    boolean isDefault() {
        return root == null;
    }

    FileEntry entry(URI uri) {
        return files.get(uri);
    }

    /**
     * Get every entry, sorted by URI.
     */
    List<FileEntry> entries() {
        var result = new ArrayList<>(files.values());
        result.sort((a, b) -> a.uri.compareTo(b.uri));
        return result;
    }

    /**
     * Get the entries of the given kind that have been built,
     * sorted by URI.
     *
     * @param kind
     */
    List<FileEntry> builtEntries(FileKind kind) {
        var result = new ArrayList<FileEntry>();
        for( var entry : entries() ) {
            if( entry.kind == kind && entry.unit != null )
                result.add(entry);
        }
        return result;
    }

    /**
     * Get the entry that contains an indexed AST node, or null if
     * the node is not indexed (built-in, plugin or superseded).
     *
     * @param node
     */
    FileEntry ownerOf(ASTNode node) {
        return node != null ? nodeOwner.get(node) : null;
    }

    boolean isIndexed() {
        return index == IndexStatus.READY;
    }

    /**
     * Whether a file belongs to the project: it is under the project
     * root and not under a nested workspace folder (always false for
     * the default project).
     *
     * @param path
     */
    boolean contains(Path path) {
        if( root == null || !path.startsWith(root) )
            return false;
        for( var inner : innerRoots ) {
            if( path.startsWith(inner) )
                return false;
        }
        return true;
    }

    /**
     * Whether a path matches the exclude patterns. For a folder, the
     * path and each of its ancestors under the root are checked, the
     * same way as the directory walk.
     *
     * @param path
     */
    boolean isExcluded(Path path) {
        var patterns = workspace.excludePatterns();
        if( root == null || !path.startsWith(root) )
            return PathUtils.isExcluded(path, patterns);
        for( var current = path; current != null && !current.equals(root); current = current.getParent() ) {
            if( PathUtils.isExcluded(current, patterns) )
                return true;
        }
        return false;
    }

    // -- mutation

    /**
     * Get the entry for a file, or create it (unbuilt) if the file is
     * of a managed kind. An existing entry is promoted to WORKSPACE
     * if that role is requested.
     *
     * @param uri
     * @param role
     * @return the entry, or null if the file is not of a managed kind
     */
    FileEntry getOrCreate(URI uri, FileEntry.Role role) {
        var entry = files.get(uri);
        if( entry != null ) {
            if( role == FileEntry.Role.WORKSPACE )
                entry.role = role;
            return entry;
        }
        var path = Path.of(uri);
        var kind = FileKind.of(path);
        if( kind.isEmpty() )
            return null;
        entry = new FileEntry(uri, path, kind.get(), role);
        entry.inputVersion = workspace.nextSeq();
        files.put(uri, entry);
        return entry;
    }

    /**
     * Mark the input of an entry as changed.
     *
     * @param entry
     */
    void bump(FileEntry entry) {
        entry.otherInputChanged = true;
        bumpText(entry);
    }

    /**
     * Mark the text of an entry as changed, without any other input
     * change. If the text turns out to be the same as in the last
     * build, the build is kept.
     *
     * @param entry
     */
    void bumpText(FileEntry entry) {
        entry.inputVersion = workspace.nextSeq();
    }

    /**
     * Remove an entry and release its AST. Its dependents become
     * stale, since one of their dependencies no longer exists.
     *
     * @param entry
     */
    void remove(FileEntry entry) {
        if( files.remove(entry.uri) == null )
            return;
        compilerFor(entry.kind).getSources().remove(entry.uri);
        unindex(entry);
        entry.unit = null;
        graph.setEdges(entry.uri, entry.depStamps.keySet(), List.of());
    }

    /**
     * Remove an entry, then remove the dependency-only entries that
     * nothing depends on any more as a result.
     *
     * @param entry
     * @param isOpen
     * @return the URIs of the removed entries
     */
    Set<URI> removeAndCollect(FileEntry entry, Predicate<URI> isOpen) {
        var candidates = new TreeSet<>(entry.depStamps.keySet());
        remove(entry);
        var result = collectGarbage(candidates, isOpen);
        result.add(entry.uri);
        return result;
    }

    /**
     * Remove the dependency-only entries among the candidates that
     * no entry depends on, and then their dependencies, recursively.
     * Open files are kept.
     *
     * @param candidates
     * @param isOpen
     * @return the URIs of the removed entries
     */
    Set<URI> collectGarbage(Collection<URI> candidates, Predicate<URI> isOpen) {
        var result = new TreeSet<URI>();
        var queue = new ArrayDeque<URI>(new TreeSet<>(candidates));
        while( !queue.isEmpty() ) {
            var uri = queue.remove();
            var entry = files.get(uri);
            if( entry == null || entry.role != FileEntry.Role.DEPENDENCY || isOpen.test(uri) )
                continue;
            if( !graph.dependents(uri).isEmpty() )
                continue;
            queue.addAll(entry.depStamps.keySet());
            remove(entry);
            result.add(uri);
        }
        return result;
    }

    void unindex(FileEntry entry) {
        for( var node : entry.nodes ) {
            if( nodeOwner.get(node) == entry )
                nodeOwner.remove(node);
        }
        entry.nodes = List.of();
        entry.parents = Map.of();
    }

    LanguageServerCompiler compilerFor(FileKind kind) {
        return kind == FileKind.SCRIPT ? scriptCompiler : configCompiler;
    }

    // -- freshness

    /**
     * Whether an entry and every dependency it was built against are
     * unchanged since its last build.
     *
     * @param entry
     */
    boolean isFresh(FileEntry entry) {
        return isClosureFresh(List.of(entry.uri));
    }

    /**
     * Whether every entry reachable from the given roots is fresh,
     * which is the case iff all of them pass the local checks.
     *
     * @param roots
     */
    private boolean isClosureFresh(Collection<URI> roots) {
        var visited = new HashSet<URI>(roots);
        var queue = new ArrayDeque<URI>(roots);
        while( !queue.isEmpty() ) {
            var entry = files.get(queue.remove());
            if( entry == null )
                continue;
            if( !isLocallyFresh(entry) )
                return false;
            for( var dep : entry.depStamps.keySet() ) {
                if( visited.add(dep) )
                    queue.add(dep);
            }
        }
        return true;
    }

    /**
     * Compute the freshness of the entries reachable from the given
     * roots. Members of a cycle are fresh iff all of them pass the
     * local checks and every edge leaving the cycle points to a fresh
     * entry.
     *
     * @param roots
     */
    private Map<URI, Boolean> freshness(Collection<URI> roots) {
        var result = new HashMap<URI, Boolean>();
        var components = DependencyGraph.components(roots, (uri) -> existingDependencies(files.get(uri)));
        // dependencies come first
        for( var component : components ) {
            var fresh = true;
            for( var uri : component ) {
                var e = files.get(uri);
                if( !isLocallyFresh(e) ) {
                    fresh = false;
                    break;
                }
                for( var dep : existingDependencies(e) ) {
                    if( !component.contains(dep) && !result.get(dep) ) {
                        fresh = false;
                        break;
                    }
                }
                if( !fresh )
                    break;
            }
            for( var uri : component )
                result.put(uri, fresh);
        }
        return result;
    }

    /**
     * Get the stamp of an entry, or MISSING if there is none.
     *
     * @param uri
     */
    long stampOf(URI uri) {
        var entry = files.get(uri);
        return entry != null ? entry.stamp : FileEntry.MISSING;
    }

    private boolean isLocallyFresh(FileEntry entry) {
        if( entry.builtInputVersion != entry.inputVersion )
            return false;
        for( var dep : entry.depStamps.entrySet() ) {
            if( stampOf(dep.getKey()) != dep.getValue() )
                return false;
        }
        return true;
    }

    private List<URI> existingDependencies(FileEntry entry) {
        var result = new ArrayList<URI>(entry.depStamps.size());
        for( var uri : entry.depStamps.keySet() ) {
            if( files.containsKey(uri) )
                result.add(uri);
        }
        return result;
    }

    /**
     * Whether every entry of the project is fresh, which is the case
     * iff every entry passes the local checks.
     */
    boolean isFresh() {
        for( var entry : files.values() ) {
            if( !isLocallyFresh(entry) )
                return false;
        }
        return true;
    }

    /**
     * Get the stale entries reachable from the given roots, grouped
     * into components in post-order (dependencies first).
     *
     * @param roots
     */
    List<List<FileEntry>> staleClosure(Collection<FileEntry> roots) {
        var rootUris = roots.stream().map(e -> e.uri).sorted().toList();
        if( isClosureFresh(rootUris) )
            return List.of();
        return staleComponents(rootUris, freshness(rootUris));
    }

    /**
     * Get every stale entry of the project, grouped into components
     * in post-order (dependencies first), in priority order:
     * the closure of the given open files, then their dependents,
     * then everything else by path.
     *
     * @param open
     */
    List<List<FileEntry>> staleEntries(Collection<URI> open) {
        if( isFresh() )
            return List.of();
        var roots = new ArrayList<URI>();
        var openUris = new TreeSet<URI>();
        for( var uri : open ) {
            if( files.containsKey(uri) )
                openUris.add(uri);
        }
        roots.addAll(openUris);
        roots.addAll(graph.transitiveDependents(openUris));
        var rest = new TreeSet<>(files.keySet());
        rest.removeAll(roots);
        roots.addAll(rest);
        return staleComponents(roots, freshness(roots));
    }

    private List<List<FileEntry>> staleComponents(List<URI> roots, Map<URI, Boolean> fresh) {
        var components = DependencyGraph.components(roots, (uri) ->
            !fresh.get(uri) ? existingDependencies(files.get(uri)) : List.of());
        var result = new ArrayList<List<FileEntry>>();
        for( var component : components ) {
            var entries = new ArrayList<FileEntry>(component.size());
            for( var uri : component ) {
                if( !fresh.get(uri) )
                    entries.add(files.get(uri));
            }
            if( !entries.isEmpty() )
                result.add(Collections.unmodifiableList(entries));
        }
        return result;
    }

    int size() {
        return files.size();
    }

    int builtCount() {
        var count = 0;
        for( var e : files.values() ) {
            if( e.isBuilt() )
                count++;
        }
        return count;
    }

    @Override
    public String toString() {
        return "Project[" + (root != null ? name : "<default>") + "]";
    }

}

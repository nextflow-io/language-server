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
 * and memoised until the next change to the project.
 *
 * Engine-confined.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class Project {

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

    /**
     * Source of the external stamps and input versions, shared by
     * every project of a workspace.
     */
    interface Context {
        long nextInputVersion();
        long extStamp(Project project, ExtKey key);
        List<String> excludePatterns();
        void entryCreated(Project project, FileEntry entry);
    }

    final String id;

    final String name;

    final Path root;

    private final Context context;

    LanguageServerCompiler scriptCompiler;

    final LanguageServerCompiler configCompiler;

    private final Map<URI, FileEntry> files = new LinkedHashMap<>();

    final DependencyGraph graph = new DependencyGraph();

    final Map<ASTNode, FileEntry> nodeOwner = new IdentityHashMap<>();

    // includers of each missing include target
    private final Map<Path, Set<URI>> missingIndex = new HashMap<>();

    // entries whose input changed since their last build
    private final Set<URI> inputStale = new HashSet<>();

    // set when an entry may be stale without an input change
    private boolean dirty;

    private long epoch;

    private long memoEpoch = -1;

    private final Map<URI, Boolean> freshMemo = new HashMap<>();

    private Boolean projectFreshMemo;

    IndexStatus index;

    long walkGeneration;

    // roots of the workspace folders nested in this one
    Set<Path> innerRoots = Set.of();

    // files created or loaded since the current walk started, which
    // the walk result may not include
    final Set<URI> addedDuringWalk = new HashSet<>();

    long libStamp;

    Project(String id, String name, Path root, Context context) {
        this.id = id;
        this.name = name;
        this.root = root;
        this.context = context;
        this.scriptCompiler = LanguageServerCompiler.forScripts(root);
        this.configCompiler = LanguageServerCompiler.forConfigs();
        this.index = root != null ? IndexStatus.NOT_STARTED : IndexStatus.READY;
    }

    // -- read API

    /**
     * The workspace folder name, or "" for the default project.
     */
    public String name() {
        return name;
    }

    /**
     * The workspace folder, or null for the default project.
     */
    public Path root() {
        return root;
    }

    public boolean isDefault() {
        return root == null;
    }

    public FileEntry entry(URI uri) {
        return files.get(uri);
    }

    /**
     * Get every entry, sorted by URI.
     */
    public List<FileEntry> entries() {
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
    public List<FileEntry> builtEntries(FileKind kind) {
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
    public FileEntry ownerOf(ASTNode node) {
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
        var patterns = context.excludePatterns();
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
     * of a managed kind.
     *
     * @param uri
     * @param role role of a new entry
     * @return the entry, or null if the file is not of a managed kind
     */
    FileEntry getOrCreate(URI uri, FileEntry.Role role) {
        var entry = files.get(uri);
        if( entry != null )
            return entry;
        var path = Path.of(uri);
        var kind = FileKind.of(path);
        if( kind.isEmpty() )
            return null;
        entry = new FileEntry(uri, path, kind.get(), role);
        entry.inputVersion = context.nextInputVersion();
        files.put(uri, entry);
        inputStale.add(uri);
        changed();
        context.entryCreated(this, entry);
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
        entry.inputVersion = context.nextInputVersion();
        inputStale.add(entry.uri);
        changed();
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
        graph.removeNode(entry.uri);
        setMissingTargets(entry, Set.of());
        inputStale.remove(entry.uri);
        if( !graph.dependents(entry.uri).isEmpty() )
            dirty = true;
        changed();
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

    /**
     * Record that an input of some entries changed without bumping
     * their input versions (a dependency was rebuilt, or an external
     * input changed).
     */
    void markDirty() {
        dirty = true;
        changed();
    }

    /**
     * Record that an entry was built at its current input version.
     *
     * @param entry
     */
    void built(FileEntry entry) {
        if( entry.builtInputVersion == entry.inputVersion )
            inputStale.remove(entry.uri);
        changed();
    }

    void setMissingTargets(FileEntry entry, Set<Path> targets) {
        for( var target : entry.missingTargets ) {
            var includers = missingIndex.get(target);
            if( includers != null ) {
                includers.remove(entry.uri);
                if( includers.isEmpty() )
                    missingIndex.remove(target);
            }
        }
        entry.missingTargets = Set.copyOf(targets);
        for( var target : targets )
            missingIndex.computeIfAbsent(target, (k) -> new TreeSet<>()).add(entry.uri);
    }

    /**
     * Get the entries that tried to include the given path when it
     * did not exist.
     *
     * @param path
     */
    List<FileEntry> includersOfMissing(Path path) {
        var includers = missingIndex.get(path);
        if( includers == null )
            return List.of();
        var result = new ArrayList<FileEntry>();
        for( var uri : includers ) {
            var entry = files.get(uri);
            if( entry != null )
                result.add(entry);
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

    private void changed() {
        epoch++;
    }

    // -- freshness

    /**
     * Whether an entry and every dependency it was built against are
     * unchanged since its last build.
     *
     * @param entry
     */
    boolean isFresh(FileEntry entry) {
        resetMemo();
        var memo = freshMemo.get(entry.uri);
        if( memo != null )
            return memo;
        // members of a cycle are fresh iff all of them pass the local
        // checks and every edge leaving the cycle points to a fresh entry
        var components = DependencyGraph.components(List.of(entry.uri), (uri) -> {
            if( freshMemo.containsKey(uri) )
                return List.of();
            var e = files.get(uri);
            return e != null ? existingDependencies(e) : List.of();
        });
        for( var component : components ) {
            if( freshMemo.containsKey(component.get(0)) )
                continue;
            var fresh = true;
            for( var uri : component ) {
                var e = files.get(uri);
                if( !isLocallyFresh(e) ) {
                    fresh = false;
                    break;
                }
                for( var dep : existingDependencies(e) ) {
                    if( !component.contains(dep) && !freshMemo.getOrDefault(dep, false) ) {
                        fresh = false;
                        break;
                    }
                }
                if( !fresh )
                    break;
            }
            for( var uri : component )
                freshMemo.put(uri, fresh);
        }
        return freshMemo.get(entry.uri);
    }

    private boolean isLocallyFresh(FileEntry entry) {
        if( entry.builtInputVersion != entry.inputVersion )
            return false;
        for( var dep : entry.depStamps.entrySet() ) {
            var target = files.get(dep.getKey());
            if( target == null || target.stamp != dep.getValue() )
                return false;
        }
        for( var ext : entry.extKeys.entrySet() ) {
            if( context.extStamp(this, ext.getKey()) != ext.getValue() )
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
     * Whether every entry of the project is fresh. O(1) when nothing
     * changed since the last check, or when no input changed and no
     * dependency or external input was invalidated.
     */
    boolean isFresh() {
        if( inputStale.isEmpty() && !dirty )
            return true;
        resetMemo();
        if( projectFreshMemo == null ) {
            projectFreshMemo = files.values().stream().allMatch(this::isFresh);
            if( projectFreshMemo && inputStale.isEmpty() )
                dirty = false;
        }
        return projectFreshMemo;
    }

    private void resetMemo() {
        if( memoEpoch != epoch ) {
            freshMemo.clear();
            projectFreshMemo = null;
            memoEpoch = epoch;
        }
    }

    /**
     * Get the stale entries reachable from the given roots, grouped
     * into components in post-order (dependencies first).
     *
     * @param roots
     */
    List<List<FileEntry>> staleClosure(Collection<FileEntry> roots) {
        var rootUris = roots.stream().map(e -> e.uri).sorted().toList();
        var components = DependencyGraph.components(rootUris, (uri) -> {
            var e = files.get(uri);
            return e != null && !isFresh(e) ? existingDependencies(e) : List.of();
        });
        return staleComponents(components);
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
        var components = DependencyGraph.components(roots, (uri) -> {
            var e = files.get(uri);
            return e != null && !isFresh(e) ? existingDependencies(e) : List.of();
        });
        return staleComponents(components);
    }

    private List<List<FileEntry>> staleComponents(List<List<URI>> components) {
        var result = new ArrayList<List<FileEntry>>();
        for( var component : components ) {
            var entries = new ArrayList<FileEntry>(component.size());
            for( var uri : component ) {
                var e = files.get(uri);
                if( e != null && !isFresh(e) )
                    entries.add(e);
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

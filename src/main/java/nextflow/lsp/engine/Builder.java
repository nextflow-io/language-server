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

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

import nextflow.config.ast.ConfigIncludeNode;
import nextflow.config.control.ConfigResolveVisitor;
import nextflow.config.ast.ConfigVisitorSupport;
import nextflow.config.ast.ConfigNode;
import nextflow.lsp.services.config.ConfigAstParentVisitor;
import nextflow.lsp.services.config.ConfigSpecVisitor;
import nextflow.lsp.services.script.ParameterSchemaVisitor;
import nextflow.lsp.services.script.PluginCallVisitor;
import nextflow.lsp.services.script.ResolvePluginIncludeVisitor;
import nextflow.lsp.services.script.ScriptAstParentVisitor;
import nextflow.lsp.spec.PluginRef;
import nextflow.lsp.spec.PluginSpec;
import nextflow.lsp.util.Includes;
import nextflow.script.ast.ScriptNode;
import nextflow.script.control.CallArityVisitor;
import nextflow.script.control.ModuleResolver;
import nextflow.script.control.ResolveIncludeVisitor;
import nextflow.script.control.ScriptResolveVisitor;
import nextflow.script.control.TypeCheckingVisitor;
import nextflow.script.dsl.Types;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.MessageType;

/**
 * Builds a dependency-closed batch of entries of one project.
 *
 * Every entry in the batch is re-parsed from its current text and
 * analysed from scratch, so that its AST always reflects one
 * complete build. Parsing runs in parallel on the compile pool;
 * everything else runs on the engine thread, because analysis reads
 * and writes across files.
 *
 * An exception while building one file is contained to that file,
 * which gets an internal error instead of its analysis results.
 *
 * Engine-confined.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class Builder {

    /**
     * Services used by a build.
     */
    interface Context {
        Engine engine();
        PluginSpecs plugins();
        long nextStamp();
        long extStamp(Project project, ExtKey key);
    }

    /**
     * The result of a build.
     *
     * @param built the entries that were built, in build order
     * @param removed the URIs of entries that were removed
     */
    record Result(List<FileEntry> built, Set<URI> removed) {
        Set<URI> touched() {
            var result = new TreeSet<URI>(removed);
            for( var entry : built )
                result.add(entry.uri);
            return result;
        }
    }

    static final String MAIN_CONFIG = "nextflow.config";

    private final Context context;

    Builder(Context context) {
        this.context = context;
    }

    /**
     * Build a batch of entries, plus any dependencies that are
     * discovered along the way and are not fresh.
     *
     * @param project
     * @param initial
     */
    Result build(Project project, Collection<FileEntry> initial) {
        return new Build(project).run(initial);
    }

    /**
     * Mark the entries of a batch that failed as a whole as built,
     * with an internal error and no AST, so that they are rebuilt
     * on their next input change rather than in a loop.
     *
     * @param project
     * @param batch
     * @param error
     */
    void fail(Project project, Collection<FileEntry> batch, Throwable error) {
        for( var entry : batch ) {
            if( project.entry(entry.uri) != entry )
                continue;
            project.compilerFor(entry.kind).getSources().remove(entry.uri);
            project.unindex(entry);
            entry.unit = null;
            entry.builtText = null;
            entry.otherInputChanged = false;
            entry.errors = List.of();
            entry.warnings = List.of();
            entry.internalError = error;
            entry.depStamps = Map.of();
            entry.extKeys = Map.of();
            entry.stamp = context.nextStamp();
            entry.builtInputVersion = entry.inputVersion;
            project.graph.setEdges(entry.uri, List.of());
            project.built(entry);
        }
        project.markDirty();
    }

    /**
     * A file after parsing.
     */
    private static final class Parsed {
        final FileEntry entry;
        final long inputVersion;
        final Integer documentVersion;
        String text;
        SourceUnit unit;
        boolean missing;
        IOException readError;
        Throwable internalError;
        // discovered dependencies
        final Set<URI> targets = new TreeSet<>();
        final Set<Path> missingTargets = new TreeSet<>();
        final Map<ExtKey, Long> extKeys = new LinkedHashMap<>();
        final List<PluginRef> pluginRefs = new ArrayList<>();
        boolean hasPluginInclude;
        // dependencies of the previous build
        final Set<URI> previousTargets;

        Parsed(FileEntry entry, long inputVersion, Integer documentVersion) {
            this.entry = entry;
            this.inputVersion = inputVersion;
            this.documentVersion = documentVersion;
            this.previousTargets = Set.copyOf(entry.depStamps.keySet());
        }

        boolean isAnalysable() {
            return unit != null && internalError == null;
        }
    }

    private final class Build {

        private final Project project;

        private final Map<URI, Parsed> batch = new LinkedHashMap<>();

        // text read while checking for unchanged files
        private final Map<URI, String> texts = new LinkedHashMap<>();

        Build(Project project) {
            this.project = project;
        }

        Result run(Collection<FileEntry> initial) {
            var kept = keepUnchanged(initial);

            // parse and discover dependencies until the batch is closed
            var pending = new ArrayList<FileEntry>();
            for( var entry : initial ) {
                if( kept.contains(entry) || project.isFresh(entry) )
                    continue;
                if( !batch.containsKey(entry.uri) && project.entry(entry.uri) == entry ) {
                    batch.put(entry.uri, null);
                    pending.add(entry);
                }
            }
            while( !pending.isEmpty() ) {
                for( var parsed : parseAll(pending) )
                    batch.put(parsed.entry.uri, parsed);
                var next = new ArrayList<FileEntry>();
                for( var entry : pending ) {
                    var parsed = batch.get(entry.uri);
                    register(parsed);
                    if( parsed.isAnalysable() )
                        discover(parsed, next);
                }
                pending = next;
            }

            var order = postOrder();
            var removed = new TreeSet<URI>();
            var built = new ArrayList<FileEntry>(kept);
            var analysed = new ArrayList<Parsed>();
            for( var parsed : order ) {
                if( parsed.missing )
                    removed.add(parsed.entry.uri);
                else
                    analysed.add(parsed);
            }

            // analyse configs first, so that scripts see their plugins
            for( var parsed : analysed ) {
                if( parsed.entry.kind == FileKind.CONFIG )
                    contained(parsed, "analysing", () -> analyseConfig(parsed));
            }
            for( var parsed : analysed ) {
                if( parsed.entry.kind == FileKind.SCRIPT )
                    contained(parsed, "resolving includes in", () -> resolveIncludes(parsed));
            }
            for( var parsed : analysed ) {
                if( parsed.entry.kind == FileKind.SCRIPT )
                    contained(parsed, "resolving names in", () -> resolveNames(parsed));
            }
            // dependencies come first, so that their inferred types are
            // available when their dependents are checked
            for( var parsed : analysed ) {
                if( parsed.entry.kind == FileKind.SCRIPT )
                    contained(parsed, "type checking", () -> typeCheck(parsed));
            }
            for( var parsed : analysed ) {
                index(parsed);
                built.add(parsed.entry);
            }

            commit(analysed, removed);
            return new Result(built, collectGarbage(order, removed));
        }

        // -- unchanged files

        /**
         * Keep the last build of each entry whose text is the same as
         * in its last build, when nothing else changed, so that it
         * keeps its stamp and its dependents stay fresh. This happens
         * when a file is opened or closed without changes.
         *
         * @param initial
         * @return the kept entries
         */
        private List<FileEntry> keepUnchanged(Collection<FileEntry> initial) {
            var documents = context.engine().documents();
            var pool = context.engine().environment().compilePool();
            var files = context.engine().environment().files();
            var candidates = new ArrayList<FileEntry>();
            var reads = new ArrayList<Future<String>>();
            for( var entry : initial ) {
                if( project.entry(entry.uri) != entry || entry.builtInputVersion == entry.inputVersion )
                    continue;
                if( entry.otherInputChanged || entry.builtText == null || entry.internalError != null )
                    continue;
                var document = documents.get(entry.uri);
                var path = entry.path;
                candidates.add(entry);
                reads.add(document.isPresent()
                    ? CompletableFuture.completedFuture(document.get().text())
                    : submit(pool, () -> files.readText(path)));
            }
            var result = new ArrayList<FileEntry>();
            // dependencies come first, so that their dependents can be kept too
            for( int i = 0; i < candidates.size(); i++ ) {
                var entry = candidates.get(i);
                String text;
                try {
                    text = reads.get(i).get();
                }
                catch( InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    continue;
                }
                catch( ExecutionException e ) {
                    if( e.getCause() instanceof VirtualMachineError vme )
                        throw vme;
                    // reported by the build
                    continue;
                }
                if( !text.equals(entry.builtText) || !isOtherwiseFresh(entry) ) {
                    texts.put(entry.uri, text);
                    continue;
                }
                entry.builtInputVersion = entry.inputVersion;
                entry.builtFromVersion = documents.get(entry.uri).map(DocumentStore.OpenDocument::version).orElse(null);
                project.built(entry);
                result.add(entry);
            }
            return result;
        }

        /**
         * Whether every dependency and external input of an entry is
         * unchanged since its last build.
         */
        private boolean isOtherwiseFresh(FileEntry entry) {
            for( var dep : entry.depStamps.entrySet() ) {
                var target = project.entry(dep.getKey());
                if( target == null || target.stamp != dep.getValue() || !project.isFresh(target) )
                    return false;
            }
            for( var ext : entry.extKeys.entrySet() ) {
                if( context.extStamp(project, ext.getKey()) != ext.getValue() )
                    return false;
            }
            return true;
        }

        // -- parse

        private List<Parsed> parseAll(List<FileEntry> entries) {
            var documents = context.engine().documents();
            var pool = context.engine().environment().compilePool();
            var tasks = new ArrayList<Parsed>(entries.size());
            var futures = new ArrayList<Future<?>>(entries.size());
            for( var entry : entries ) {
                var document = documents.get(entry.uri);
                var parsed = new Parsed(entry, entry.inputVersion, document.map(DocumentStore.OpenDocument::version).orElse(null));
                var text = document.map(DocumentStore.OpenDocument::text).or(() -> Optional.ofNullable(texts.get(entry.uri)));
                Callable<Void> task = () -> {
                    parse(parsed, text);
                    return null;
                };
                tasks.add(parsed);
                futures.add(submit(pool, task));
            }
            for( int i = 0; i < futures.size(); i++ ) {
                try {
                    futures.get(i).get();
                }
                catch( InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    tasks.get(i).internalError = e;
                }
                catch( ExecutionException e ) {
                    if( e.getCause() instanceof VirtualMachineError vme )
                        throw vme;
                    tasks.get(i).internalError = e.getCause();
                }
            }
            for( var parsed : tasks ) {
                if( parsed.internalError != null )
                    logInternalError(parsed, "parsing", parsed.internalError);
                if( parsed.readError != null )
                    context.engine().log(MessageType.Warning, "Failed to read " + parsed.entry.path + " -- " + parsed.readError);
            }
            return tasks;
        }

        private <T> Future<T> submit(ExecutorService pool, Callable<T> task) {
            try {
                return pool.submit(task);
            }
            catch( RejectedExecutionException e ) {
                // the pool is shut down -- run on this thread
                var inline = new FutureTask<>(task);
                inline.run();
                return inline;
            }
        }

        /**
         * Parse a file. Runs on the compile pool, so it must not touch
         * any shared state.
         */
        private void parse(Parsed parsed, Optional<String> document) {
            var entry = parsed.entry;
            String text;
            if( document.isPresent() ) {
                text = document.get();
            }
            else {
                try {
                    text = context.engine().environment().files().readText(entry.path);
                }
                catch( NoSuchFileException e ) {
                    parsed.missing = true;
                    return;
                }
                catch( IOException e ) {
                    // treated as missing, and retried on the next event
                    parsed.readError = e;
                    parsed.missing = true;
                    return;
                }
            }
            parsed.text = text;
            var compiler = project.compilerFor(entry.kind);
            var unit = compiler.createSourceUnit(entry.uri, text);
            parsed.unit = unit;
            try {
                compiler.parse(unit);
            }
            catch( VirtualMachineError e ) {
                throw e;
            }
            catch( Throwable e ) {
                parsed.internalError = e;
            }
        }

        // -- register and discover

        private void register(Parsed parsed) {
            var sources = project.compilerFor(parsed.entry.kind).getSources();
            if( parsed.unit != null )
                sources.put(parsed.entry.uri, parsed.unit);
            else
                sources.remove(parsed.entry.uri);
        }

        private void discover(Parsed parsed, List<FileEntry> next) {
            try {
                if( parsed.entry.kind == FileKind.SCRIPT )
                    discoverScript(parsed);
                else
                    discoverConfig(parsed);
            }
            catch( VirtualMachineError e ) {
                throw e;
            }
            catch( Throwable e ) {
                parsed.internalError = e;
                logInternalError(parsed, "resolving dependencies of", e);
            }
            for( var target : parsed.targets ) {
                var entry = project.entry(target);
                if( entry == null )
                    entry = project.getOrCreate(target, roleFor(Path.of(target)));
                if( entry == null || batch.containsKey(target) )
                    continue;
                if( entry.isBuilt() && project.isFresh(entry) )
                    continue;
                for( var component : project.staleClosure(List.of(entry)) ) {
                    for( var stale : component ) {
                        if( !batch.containsKey(stale.uri) ) {
                            batch.put(stale.uri, null);
                            next.add(stale);
                        }
                    }
                }
                if( !batch.containsKey(target) ) {
                    batch.put(target, null);
                    next.add(entry);
                }
            }
        }

        private FileEntry.Role roleFor(Path path) {
            return project.contains(path) && !project.isExcluded(path)
                ? FileEntry.Role.WORKSPACE
                : FileEntry.Role.DEPENDENCY;
        }

        private void discoverScript(Parsed parsed) {
            if( !(parsed.unit.getAST() instanceof ScriptNode sn) )
                return;
            for( var node : sn.getIncludes() ) {
                var source = node.source.getText();
                if( source.startsWith("plugin/") ) {
                    parsed.hasPluginInclude = true;
                    continue;
                }
                URI target;
                try {
                    target = ModuleResolver.getIncludeUri(parsed.entry.uri, source, project.root);
                }
                catch( Exception e ) {
                    // reported by include resolution
                    continue;
                }
                addTarget(parsed, target);
            }
            if( parsed.hasPluginInclude ) {
                for( var config : governingConfigs(parsed.entry.path) )
                    addTarget(parsed, config.toUri());
            }
        }

        private void discoverConfig(Parsed parsed) {
            if( !(parsed.unit.getAST() instanceof ConfigNode cn) )
                return;
            var unit = parsed.unit;
            var sources = new ArrayList<String>();
            new ConfigVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return unit;
                }

                @Override
                public void visitConfigInclude(ConfigIncludeNode node) {
                    if( node.source instanceof ConstantExpression )
                        sources.add(node.source.getText());
                }
            }.visit(cn);
            for( var source : sources ) {
                var target = Includes.configIncludeUri(parsed.entry.uri, source);
                if( target != null )
                    addTarget(parsed, target);
            }
        }

        private void addTarget(Parsed parsed, URI target) {
            var canonical = Uris.canonical(target);
            if( canonical.isEmpty() || canonical.get().equals(parsed.entry.uri) )
                return;
            var path = Path.of(canonical.get());
            if( !FileKind.isManaged(path) )
                return;
            if( context.engine().documents().isOpen(canonical.get()) || context.engine().environment().files().exists(path) )
                parsed.targets.add(canonical.get());
            else
                parsed.missingTargets.add(path);
        }

        /**
         * Get the `nextflow.config` files that may declare the plugins
         * of a script: one in each ancestor directory of the script, up
         * to the project root. In the default project, only open or
         * already loaded config files are considered.
         */
        private List<Path> governingConfigs(Path script) {
            var result = new ArrayList<Path>();
            for( var dir = script.getParent(); dir != null; dir = dir.getParent() ) {
                if( project.root != null && !dir.startsWith(project.root) )
                    break;
                var config = dir.resolve(MAIN_CONFIG);
                var uri = config.toUri();
                if( project.root != null || project.entry(uri) != null || context.engine().documents().isOpen(uri) )
                    result.add(config);
                if( dir.equals(project.root) )
                    break;
            }
            return result;
        }

        private List<Parsed> postOrder() {
            var components = DependencyGraph.components(batch.keySet().stream().sorted().toList(), (uri) -> {
                var parsed = batch.get(uri);
                return parsed != null ? parsed.targets.stream().filter(batch::containsKey).toList() : List.of();
            });
            var result = new ArrayList<Parsed>(batch.size());
            for( var component : components ) {
                for( var uri : component )
                    result.add(batch.get(uri));
            }
            return result;
        }

        // -- analysis

        private void analyseConfig(Parsed parsed) {
            var unit = parsed.unit;
            var errorCollector = unit.getErrorCollector();
            var includes = new nextflow.config.control.ResolveIncludeVisitor(unit);
            includes.visit();
            for( var error : includes.getErrors() )
                errorCollector.addErrorAndContinue(error);
            new ConfigResolveVisitor(unit, project.configCompiler.compilationUnit(), Types.DEFAULT_CONFIG_IMPORTS).visit();
            new ConfigSpecVisitor(unit, new ConfigSpecVisitor.PluginLookup() {
                @Override
                public PluginSpec get(PluginRef ref) {
                    var lookup = context.plugins().lookup(ref);
                    parsed.extKeys.put(new ExtKey.Plugin(ref), 0L);
                    return lookup.spec().orElse(null);
                }

                @Override
                public boolean isPending(PluginRef ref) {
                    return context.plugins().lookup(ref).pending();
                }

                @Override
                public void declare(URI uri, List<PluginRef> refs) {
                    parsed.pluginRefs.addAll(refs);
                }
            }).visit();
        }

        private void resolveIncludes(Parsed parsed) {
            var unit = parsed.unit;
            var visitor = new ResolveIncludeVisitor(unit, project.root, project.scriptCompiler);
            visitor.visit();
            for( var error : visitor.getErrors() )
                unit.getErrorCollector().addErrorAndContinue(error);
            if( !parsed.hasPluginInclude )
                return;
            var refs = governingPluginRefs(parsed.entry.path);
            new ResolvePluginIncludeVisitor(unit, new ResolvePluginIncludeVisitor.PluginResolver() {
                @Override
                public PluginSpec get(String name) {
                    var lookup = lookup(name);
                    return lookup != null ? lookup.spec().orElse(null) : null;
                }

                @Override
                public boolean isPending(String name) {
                    var lookup = lookup(name);
                    return lookup != null && lookup.pending();
                }

                private PluginSpecs.Lookup lookup(String name) {
                    var ref = refs.stream().filter(r -> r.name().equals(name)).findFirst().orElse(null);
                    if( ref == null )
                        return null;
                    var lookup = context.plugins().lookup(ref);
                    parsed.extKeys.put(new ExtKey.Plugin(ref), 0L);
                    return lookup;
                }
            }).visit();
        }

        /**
         * Get the plugins declared by the nearest governing config file
         * that declares any.
         */
        private List<PluginRef> governingPluginRefs(Path script) {
            for( var config : governingConfigs(script) ) {
                var uri = config.toUri();
                var parsed = batch.get(uri);
                if( parsed != null ) {
                    if( !parsed.pluginRefs.isEmpty() )
                        return parsed.pluginRefs;
                    continue;
                }
                var entry = project.entry(uri);
                if( entry != null && !entry.pluginRefs.isEmpty() )
                    return entry.pluginRefs;
            }
            return List.of();
        }

        private void resolveNames(Parsed parsed) {
            var unit = parsed.unit;
            parsed.extKeys.put(new ExtKey.Lib(project.id), 0L);
            parsed.extKeys.put(new ExtKey.Schema(parsed.entry.path.getParent()), 0L);
            try {
                new ScriptResolveVisitor(unit, project.scriptCompiler.compilationUnit(), Types.DEFAULT_SCRIPT_IMPORTS, List.of()).visit();
            }
            catch( GroovyBugError | Exception e ) {
                // a Groovy class in the lib directory failed to compile
                System.err.println("Unexpected exception while resolving " + parsed.entry.uri.getPath() + ": " + e.toString());
            }
            new ParameterSchemaVisitor(unit).visit();
            new PluginCallVisitor(unit).visit();
        }

        private void typeCheck(Parsed parsed) {
            var unit = parsed.unit;
            if( unit.getErrorCollector().hasErrors() )
                return;
            if( !(unit.getAST() instanceof ScriptNode sn) )
                return;
            if( sn.isTypingEnabled() )
                new TypeCheckingVisitor(unit).visit();
            else
                new CallArityVisitor(unit).visit();
        }

        private void contained(Parsed parsed, String step, Runnable action) {
            if( !parsed.isAnalysable() )
                return;
            try {
                action.run();
            }
            catch( VirtualMachineError e ) {
                throw e;
            }
            catch( Throwable e ) {
                parsed.internalError = e;
                logInternalError(parsed, step, e);
            }
        }

        // -- index and commit

        private void index(Parsed parsed) {
            var entry = parsed.entry;
            entry.parses++;
            project.unindex(entry);
            entry.unit = parsed.unit;
            entry.builtText = parsed.unit != null ? parsed.text : null;
            entry.internalError = parsed.internalError;
            entry.errors = parsed.unit != null ? errors(parsed.unit) : List.of();
            entry.warnings = parsed.unit != null ? warnings(parsed.unit) : List.of();
            if( parsed.internalError != null )
                return;
            try {
                var parents = entry.kind == FileKind.SCRIPT
                    ? scriptParents(parsed.unit)
                    : configParents(parsed.unit);
                entry.parents = parents;
                entry.nodes = List.copyOf(parents.keySet());
                for( var node : entry.nodes )
                    project.nodeOwner.put(node, entry);
            }
            catch( VirtualMachineError e ) {
                throw e;
            }
            catch( Throwable e ) {
                parsed.internalError = e;
                entry.internalError = e;
                logInternalError(parsed, "indexing", e);
            }
        }

        private static Map<ASTNode, ASTNode> scriptParents(SourceUnit unit) {
            var visitor = new ScriptAstParentVisitor(unit);
            visitor.visit();
            return visitor.getParents();
        }

        private static Map<ASTNode, ASTNode> configParents(SourceUnit unit) {
            var visitor = new ConfigAstParentVisitor(unit);
            visitor.visit();
            return visitor.getParents();
        }

        private static List<SyntaxException> errors(SourceUnit unit) {
            var result = new ArrayList<SyntaxException>();
            var messages = unit.getErrorCollector().getErrors();
            if( messages != null ) {
                for( var message : messages ) {
                    if( message instanceof SyntaxErrorMessage sem )
                        result.add(sem.getCause());
                }
            }
            return List.copyOf(result);
        }

        private static List<WarningMessage> warnings(SourceUnit unit) {
            var warnings = unit.getErrorCollector().getWarnings();
            return warnings != null ? List.copyOf(warnings) : List.of();
        }

        private void commit(List<Parsed> analysed, Set<URI> removed) {
            // stamps first, so that dependencies in the batch carry their new stamps
            for( var parsed : analysed )
                parsed.entry.stamp = context.nextStamp();
            for( var uri : removed ) {
                var entry = project.entry(uri);
                if( entry != null )
                    project.remove(entry);
            }
            var invalidatesOthers = false;
            for( var parsed : analysed ) {
                var entry = parsed.entry;
                var depStamps = new TreeMap<URI, Long>();
                var missing = new TreeSet<>(parsed.missingTargets);
                for( var target : parsed.targets ) {
                    var dep = project.entry(target);
                    if( dep != null )
                        depStamps.put(target, dep.stamp);
                    else
                        missing.add(Path.of(target));
                }
                var extKeys = new LinkedHashMap<ExtKey, Long>();
                for( var key : parsed.extKeys.keySet() )
                    extKeys.put(key, context.extStamp(project, key));
                entry.depStamps = depStamps;
                entry.extKeys = extKeys;
                entry.builtInputVersion = parsed.inputVersion;
                entry.builtFromVersion = parsed.documentVersion;
                entry.otherInputChanged = false;
                entry.pluginRefs = List.copyOf(parsed.pluginRefs);
                project.graph.setEdges(entry.uri, depStamps.keySet());
                project.setMissingTargets(entry, missing);
                project.built(entry);
                for( var dependent : project.graph.dependents(entry.uri) ) {
                    if( !batch.containsKey(dependent) )
                        invalidatesOthers = true;
                }
            }
            if( invalidatesOthers )
                project.markDirty();
        }

        /**
         * Remove dependency-only entries that no entry depends on any
         * more, and return the URIs of every removed entry.
         */
        private Set<URI> collectGarbage(List<Parsed> order, Set<URI> removed) {
            var candidates = new TreeSet<URI>();
            for( var parsed : order )
                candidates.addAll(parsed.previousTargets);
            var result = new TreeSet<URI>(removed);
            result.addAll(project.collectGarbage(candidates, context.engine().documents()::isOpen));
            return result;
        }

        private void logInternalError(Parsed parsed, String step, Throwable e) {
            context.engine().logError("Internal error while " + step + " " + parsed.entry.uri.getPath(), e);
        }
    }

}

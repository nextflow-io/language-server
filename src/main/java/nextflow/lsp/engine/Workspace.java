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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import nextflow.lsp.ast.ProjectView;
import nextflow.lsp.services.ErrorReportingMode;
import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.lsp.util.Logger;
import nextflow.util.PathUtils;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

/**
 * The language service state driven by the engine: configuration,
 * one project per workspace folder plus the default project,
 * request routing, plugin specs, diagnostics and indexing progress.
 *
 * Input events only bump the input versions of the affected
 * entries. Stale entries are rebuilt on demand by requests, and in
 * the background in slices: immediately while a project is
 * indexing, and after the debounce delay otherwise.
 *
 * Engine-confined: every method is called on the engine thread.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class Workspace implements EngineState, Project.Context, Builder.Context, DiagnosticsLedger.Owners {

    static final int DEFAULT_MAX_SLICE_FILES = 64;

    static final Duration SLICE_BUDGET = Duration.ofMillis(100);

    static final String DEFAULT_PROJECT_NAME = "";

    // extensions of watched files that are not managed, so a watcher
    // event for any other unmanaged path may be for a directory
    private static final Set<String> NOT_DIRECTORIES = Set.of("groovy", "jar", "json");

    private static final Logger log = Logger.getInstance();

    private final Engine engine;

    private final int maxSliceFiles;

    private LanguageServerConfiguration configuration = LanguageServerConfiguration.defaults();

    // in folder order
    private final List<Project> folders = new ArrayList<>();

    private final Project defaultProject;

    private PluginSpecs plugins;

    private final DiagnosticsLedger ledger;

    private final IndexProgress progress;

    private final Builder builder;

    private final Map<Path, Long> schemaStamps = new HashMap<>();

    private long inputSeq;

    private long stampSeq;

    private int projectCount;

    private boolean initialized;

    private boolean watchersSupported;

    // estimated build time per file, for sizing slices
    private double millisPerFile;

    private long builds;

    private long builtFiles;

    private Workspace(Engine engine, int maxSliceFiles) {
        this.engine = engine;
        this.maxSliceFiles = maxSliceFiles;
        this.defaultProject = new Project("default", DEFAULT_PROJECT_NAME, null, this);
        this.plugins = new PluginSpecs(engine, configuration.pluginRegistryUrl());
        this.ledger = new DiagnosticsLedger(engine, this);
        this.progress = new IndexProgress(engine);
        this.builder = new Builder(this);
    }

    public static EngineState.Factory factory() {
        return factory(DEFAULT_MAX_SLICE_FILES);
    }

    /**
     * Create a factory with the given maximum number of files per
     * background slice.
     *
     * @param maxSliceFiles
     */
    public static EngineState.Factory factory(int maxSliceFiles) {
        return (engine) -> new Workspace(engine, maxSliceFiles);
    }

    // -- read API

    public LanguageServerConfiguration configuration() {
        return configuration;
    }

    /**
     * Get every project: the workspace folders in folder order,
     * then the default project.
     */
    public List<Project> projects() {
        var result = new ArrayList<Project>(folders.size() + 1);
        result.addAll(folders);
        result.add(defaultProject);
        return result;
    }

    public Project defaultProject() {
        return defaultProject;
    }

    /**
     * Get the workspace folder with the given name.
     *
     * @param name
     */
    public Optional<Project> folder(String name) {
        return folders.stream().filter(p -> p.name.equals(name)).findFirst();
    }

    /**
     * Get the project that a file routes to: the workspace folder
     * with the longest root that contains it, or the default project.
     *
     * @param path
     */
    public Project projectFor(Path path) {
        Project result = defaultProject;
        for( var project : folders ) {
            if( project.contains(path) && (result.root == null || project.root.getNameCount() > result.root.getNameCount()) )
                result = project;
        }
        return result;
    }

    /**
     * The number of builds so far, for tests.
     */
    long buildCount() {
        return builds;
    }

    /**
     * The number of files built so far, for tests.
     */
    long builtFileCount() {
        return builtFiles;
    }

    // -- EngineState

    @Override
    public void apply(Event event) {
        if( event instanceof Event.Init e )
            onInit(e.params());
        else if( event instanceof Event.Initialized )
            onInitialized();
        else if( event instanceof Event.DidOpen e )
            Uris.canonical(e.params().getTextDocument().getUri()).ifPresent(this::onOpen);
        else if( event instanceof Event.DidChange e )
            Uris.canonical(e.params().getTextDocument().getUri()).ifPresent(this::onChange);
        else if( event instanceof Event.DidClose e )
            Uris.canonical(e.params().getTextDocument().getUri()).ifPresent(this::onClose);
        else if( event instanceof Event.WatchedFiles e )
            onWatchedFiles(e);
        else if( event instanceof Event.ConfigChanged e )
            onConfigChanged(e);
        else if( event instanceof Event.FoldersChanged e )
            onFoldersChanged(e);
        else if( event instanceof Event.WalkDone e )
            onWalkDone(e);
        else if( event instanceof Event.PluginFetched e )
            onPluginFetched(e);
        else if( event instanceof Event.ProgressCreated e )
            progress.onCreated(e);
        updateProgress();
    }

    @Override
    public Optional<Route> route(RequestSpec spec) {
        plugins.retryFailed();
        if( spec.need() == Need.ALL )
            return Optional.of(new WorkspaceRoute(spec.need(), projects(), null, null, null));
        if( spec.uri() == null ) {
            if( spec.projectName() != null ) {
                return folder(spec.projectName())
                    .map(p -> new WorkspaceRoute(spec.need(), List.of(p), p, null, null));
            }
            return Optional.of(new WorkspaceRoute(spec.need(), List.of(), null, null, null));
        }
        var uri = Uris.canonical(spec.uri());
        if( uri.isEmpty() )
            return Optional.empty();
        var path = Path.of(uri.get());
        var kind = FileKind.of(path);
        if( kind.isEmpty() )
            return Optional.empty();
        var project = projectFor(path);
        // excludes apply to the scan, not to files that the client opened
        if( project.isExcluded(path) && !engine.documents().isOpen(uri.get()) )
            return Optional.empty();
        return Optional.of(new WorkspaceRoute(spec.need(), List.of(project), project, uri.get(), kind.get()));
    }

    @Override
    public boolean runSlice() {
        plugins.retryFailed();
        var open = engine.documents().uris();
        for( var project : projects() ) {
            if( !canBuild(project) )
                continue;
            var stale = project.staleEntries(open);
            if( stale.isEmpty() ) {
                markReadyIfDone(project);
                continue;
            }
            build(project, takeSlice(stale));
            markReadyIfDone(project);
            updateProgress();
            return hasBackgroundWork();
        }
        updateProgress();
        return false;
    }

    @Override
    public void shutdown() {
        folders.clear();
    }

    // -- Project.Context

    @Override
    public long nextInputVersion() {
        return ++inputSeq;
    }

    @Override
    public long extStamp(Project project, ExtKey key) {
        if( key instanceof ExtKey.Lib lib )
            return lib.projectId().equals(project.id) ? project.libStamp : -1;
        if( key instanceof ExtKey.Schema schema )
            return schemaStamps.getOrDefault(schema.dir(), 0L);
        if( key instanceof ExtKey.Plugin plugin )
            return plugins.generation(plugin.ref());
        throw new IllegalArgumentException(key.toString());
    }

    @Override
    public List<String> excludePatterns() {
        return configuration.excludePatterns();
    }

    @Override
    public void entryCreated(Project project, FileEntry entry) {
        matchMissing(entry.path);
    }

    // -- Builder.Context

    @Override
    public Engine engine() {
        return engine;
    }

    @Override
    public PluginSpecs plugins() {
        return plugins;
    }

    @Override
    public long nextStamp() {
        return ++stampSeq;
    }

    // -- DiagnosticsLedger.Owners

    @Override
    public FileEntry owner(URI uri) {
        var path = Path.of(uri);
        var routed = projectFor(path);
        if( routed.isExcluded(path) && !engine.documents().isOpen(uri) )
            return null;
        var entry = routed.entry(uri);
        if( entry != null )
            return entry;
        for( var project : projects() ) {
            if( project == routed )
                continue;
            entry = project.entry(uri);
            if( entry != null && entry.role == FileEntry.Role.DEPENDENCY )
                return entry;
        }
        return null;
    }

    @Override
    public ErrorReportingMode errorReportingMode() {
        return configuration.errorReportingMode();
    }

    // -- lifecycle events

    @SuppressWarnings("deprecation")
    private void onInit(InitializeParams params) {
        var capabilities = params.getCapabilities();
        if( capabilities != null ) {
            var window = capabilities.getWindow();
            progress.setSupported(window != null && Boolean.TRUE.equals(window.getWorkDoneProgress()));
            var workspace = capabilities.getWorkspace();
            var watched = workspace != null ? workspace.getDidChangeWatchedFiles() : null;
            watchersSupported = watched != null && Boolean.TRUE.equals(watched.getDynamicRegistration());
        }
        var workspaceFolders = params.getWorkspaceFolders();
        if( workspaceFolders != null && !workspaceFolders.isEmpty() ) {
            for( var folder : workspaceFolders )
                addFolder(folder);
        }
        else if( params.getRootUri() != null ) {
            // fallback for clients that only send the (deprecated) root URI
            addFolder(new WorkspaceFolder(params.getRootUri(), DEFAULT_PROJECT_NAME));
        }
    }

    private void onInitialized() {
        if( initialized )
            return;
        initialized = true;
        if( watchersSupported )
            WatcherRegistration.register(engine);
        for( var project : folders )
            startWalk(project);
    }

    private Project addFolder(WorkspaceFolder folder) {
        var uri = Uris.canonical(folder.getUri());
        if( uri.isEmpty() ) {
            engine.log(MessageType.Warning, "Ignoring workspace folder with unsupported URI: " + folder.getUri());
            return null;
        }
        var root = Path.of(uri.get());
        if( folders.stream().anyMatch(p -> p.root.equals(root)) )
            return null;
        var project = new Project("p" + (++projectCount), folderName(folder, root), root, this);
        folders.add(project);
        updateInnerRoots();
        // open documents under the new folder move to it, and so do the
        // files of an enclosing folder, which its next walk drops
        var documents = engine.documents();
        for( var openUri : documents.uris() ) {
            if( projectFor(Path.of(openUri)) == project )
                project.getOrCreate(openUri, FileEntry.Role.WORKSPACE);
        }
        for( var other : projects() ) {
            if( other == project )
                continue;
            for( var entry : other.entries() ) {
                if( other.entry(entry.uri) != entry || entry.role != FileEntry.Role.WORKSPACE || projectFor(entry.path) != project )
                    continue;
                if( documents.isOpen(entry.uri) )
                    ledger.publish(release(other, entry));
                else if( !project.isExcluded(entry.path) )
                    project.getOrCreate(entry.uri, FileEntry.Role.WORKSPACE);
            }
        }
        rewalkEnclosing(root);
        return project;
    }

    /**
     * Record in each folder the roots of the folders nested in it.
     */
    private void updateInnerRoots() {
        for( var project : folders ) {
            var inner = new TreeSet<Path>();
            for( var other : folders ) {
                if( other != project && other.root.startsWith(project.root) )
                    inner.add(other.root);
            }
            project.innerRoots = Set.copyOf(inner);
        }
    }

    private void rewalkEnclosing(Path root) {
        for( var project : folders ) {
            if( !project.root.equals(root) && root.startsWith(project.root) && project.index != Project.IndexStatus.NOT_STARTED )
                startWalk(project);
        }
    }

    private static String folderName(WorkspaceFolder folder, Path root) {
        var name = folder.getName();
        if( name != null && !name.isEmpty() )
            return name;
        var fileName = root.getFileName();
        return fileName != null ? fileName.toString() : (name != null ? name : DEFAULT_PROJECT_NAME);
    }

    private void removeFolder(Project project) {
        folders.remove(project);
        updateInnerRoots();
        rewalkEnclosing(project.root);
        var touched = new TreeSet<URI>();
        var documents = engine.documents();
        for( var entry : project.entries() ) {
            touched.add(entry.uri);
            // open documents move to their new owner, and so do the files
            // of the removed folder if it was nested in another folder
            var target = projectFor(entry.path);
            var moved = documents.isOpen(entry.uri)
                || (entry.role == FileEntry.Role.WORKSPACE && !target.isDefault() && !target.isExcluded(entry.path));
            if( moved )
                target.getOrCreate(entry.uri, FileEntry.Role.WORKSPACE).role = FileEntry.Role.WORKSPACE;
        }
        ledger.publish(touched);
    }

    private void onFoldersChanged(Event.FoldersChanged event) {
        var change = event.params().getEvent();
        if( change == null )
            return;
        if( change.getRemoved() != null ) {
            for( var folder : change.getRemoved() ) {
                var uri = Uris.canonical(folder.getUri());
                if( uri.isEmpty() )
                    continue;
                var root = Path.of(uri.get());
                folders.stream()
                    .filter(p -> p.root.equals(root))
                    .findFirst()
                    .ifPresent(this::removeFolder);
            }
        }
        if( change.getAdded() != null ) {
            for( var folder : change.getAdded() ) {
                var project = addFolder(folder);
                if( project != null && initialized )
                    startWalk(project);
            }
        }
        scheduleBackground(Duration.ZERO);
    }

    private void startWalk(Project project) {
        var generation = ++project.walkGeneration;
        project.index = Project.IndexStatus.WALKING;
        project.addedDuringWalk.clear();
        var id = project.id;
        var root = project.root;
        var excluded = walkExclusions(project);
        var files = engine.environment().files();
        engine.submitIo("walk " + root, () -> {
            try {
                var result = files.walk(root, excluded, FileKind::isManaged);
                return new Event.WalkDone(id, generation, result, null);
            }
            catch( IOException e ) {
                return new Event.WalkDone(id, generation, null, e);
            }
        });
    }

    /**
     * Get the paths that a walk of the project skips: excluded paths
     * and nested workspace folders. Safe to use on any thread.
     *
     * @param project
     */
    private Predicate<Path> walkExclusions(Project project) {
        var patterns = List.copyOf(configuration.excludePatterns());
        var innerRoots = project.innerRoots;
        return (path) -> innerRoots.contains(path) || PathUtils.isExcluded(path, patterns);
    }

    private void onWalkDone(Event.WalkDone event) {
        var project = folders.stream().filter(p -> p.id.equals(event.projectId())).findFirst().orElse(null);
        if( project == null || project.walkGeneration != event.generation() || project.index != Project.IndexStatus.WALKING )
            return;
        project.index = Project.IndexStatus.BUILDING;
        if( event.error() != null ) {
            // keep the current file set, and retry on the next re-walk
            engine.logError("Failed to scan workspace folder " + project.root, event.error());
            scheduleBackground(Duration.ZERO);
            return;
        }
        var discovered = new TreeSet<URI>();
        for( var path : event.files() )
            discovered.add(path.toUri());
        for( var uri : discovered ) {
            var entry = project.getOrCreate(uri, FileEntry.Role.WORKSPACE);
            if( entry != null )
                entry.role = FileEntry.Role.WORKSPACE;
        }
        // files that are no longer in the set (deleted or newly excluded)
        var documents = engine.documents();
        var removed = new TreeSet<URI>();
        for( var entry : project.entries() ) {
            if( entry.role != FileEntry.Role.WORKSPACE || discovered.contains(entry.uri) )
                continue;
            if( documents.isOpen(entry.uri) && projectFor(entry.path) == project )
                continue;
            if( project.addedDuringWalk.contains(entry.uri) )
                continue;
            if( project.entry(entry.uri) != entry )
                continue;
            if( hasDependents(project, entry) )
                entry.role = FileEntry.Role.DEPENDENCY;
            else
                removed.addAll(project.removeAndCollect(entry, documents::isOpen));
        }
        project.addedDuringWalk.clear();
        ledger.publish(removed);
        scheduleBackground(Duration.ZERO);
    }

    private static boolean hasDependents(Project project, FileEntry entry) {
        for( var dependent : project.graph.dependents(entry.uri) ) {
            if( project.entry(dependent) != null )
                return true;
        }
        return false;
    }

    // -- document events

    private void onOpen(URI uri) {
        var path = Path.of(uri);
        if( !FileKind.isManaged(path) )
            return;
        bumpAll(uri);
        // an open file is part of the project, even if it is excluded
        projectFor(path).getOrCreate(uri, FileEntry.Role.WORKSPACE).role = FileEntry.Role.WORKSPACE;
        scheduleEdit();
    }

    private void onChange(URI uri) {
        bumpAll(uri);
        scheduleEdit();
    }

    private void onClose(URI uri) {
        bumpAll(uri);
        var path = Path.of(uri);
        var project = projectFor(path);
        var entry = project.entry(uri);
        if( entry != null && (project.isDefault() || project.isExcluded(path)) ) {
            var released = new TreeSet<>(release(project, entry));
            released.add(uri);
            ledger.publish(released);
        }
        scheduleEdit();
    }

    /**
     * Drop a file that is not part of the file set of a project,
     * unless another file of the project depends on it.
     *
     * @return the URIs of the removed entries
     */
    private Set<URI> release(Project project, FileEntry entry) {
        if( hasDependents(project, entry) ) {
            entry.role = FileEntry.Role.DEPENDENCY;
            return Set.of();
        }
        return project.removeAndCollect(entry, engine.documents()::isOpen);
    }

    private void bumpAll(URI uri) {
        for( var project : projects() ) {
            var entry = project.entry(uri);
            if( entry != null )
                project.bumpText(entry);
        }
    }

    private void matchMissing(Path path) {
        for( var project : projects() ) {
            for( var includer : project.includersOfMissing(path) )
                project.bump(includer);
        }
    }

    private void onWatchedFiles(Event.WatchedFiles event) {
        var changes = event.params().getChanges();
        if( changes == null )
            return;
        var documents = engine.documents();
        for( var change : changes ) {
            var canonical = Uris.canonical(change.getUri());
            if( canonical.isEmpty() || change.getType() == null )
                continue;
            var uri = canonical.get();
            var path = Path.of(uri);
            var fileName = path.getFileName() != null ? path.getFileName().toString() : "";
            for( var project : folders ) {
                var lib = project.root.resolve("lib");
                if( path.startsWith(lib) )
                    libChanged(project, fileName.endsWith(".jar") || path.equals(lib));
            }
            if( "nextflow_schema.json".equals(fileName) ) {
                schemaStamps.merge(path.getParent(), 1L, Long::sum);
                projects().forEach(Project::markDirty);
            }
            if( !FileKind.isManaged(path) && !NOT_DIRECTORIES.contains(extension(fileName)) ) {
                directoryChanged(path, change.getType());
                continue;
            }
            if( !FileKind.isManaged(path) || documents.isOpen(uri) )
                continue;
            if( change.getType() != FileChangeType.Deleted ) {
                // a changed file may be unknown if its last read failed
                var project = projectFor(path);
                if( !project.isDefault() && !project.isExcluded(path) ) {
                    project.getOrCreate(uri, FileEntry.Role.WORKSPACE);
                    noteAdded(project, uri);
                }
                matchMissing(path);
            }
            else {
                for( var project : folders )
                    project.addedDuringWalk.remove(uri);
            }
            bumpAll(uri);
        }
        scheduleEdit();
    }

    private static String extension(String fileName) {
        var dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }

    /**
     * Handle a watcher event that may be for a directory, since some
     * clients report only the directory when it is deleted or moved.
     * A deleted directory deletes every file under it, and the files
     * under a created directory are discovered with a walk.
     *
     * @param path
     * @param type
     */
    private void directoryChanged(Path path, FileChangeType type) {
        var documents = engine.documents();
        if( type == FileChangeType.Deleted ) {
            for( var project : projects() ) {
                for( var entry : project.entries() ) {
                    if( entry.path.startsWith(path) && !documents.isOpen(entry.uri) )
                        project.bumpText(entry);
                }
            }
            return;
        }
        if( type != FileChangeType.Created )
            return;
        var project = projectFor(path);
        if( project.isDefault() || project.isExcluded(path) )
            return;
        var excluded = walkExclusions(project);
        var files = engine.environment().files();
        engine.submitIo("walk " + path, () -> {
            List<Path> result;
            try {
                result = files.walk(path, excluded, FileKind::isManaged);
            }
            catch( IOException e ) {
                // not a directory, or deleted since
                return null;
            }
            if( result.isEmpty() )
                return null;
            var created = result.stream()
                .map(file -> new FileEvent(file.toUri().toString(), FileChangeType.Created))
                .toList();
            return new Event.WatchedFiles(new DidChangeWatchedFilesParams(created));
        });
    }

    private static void noteAdded(Project project, URI uri) {
        if( project.index == Project.IndexStatus.WALKING )
            project.addedDuringWalk.add(uri);
    }

    private void libChanged(Project project, boolean jar) {
        if( jar ) {
            // a new classpath needs a new compiler, so every script is rebuilt
            project.scriptCompiler = nextflow.lsp.compiler.LanguageServerCompiler.forScripts(project.root);
            for( var entry : project.entries() ) {
                if( entry.kind == FileKind.SCRIPT )
                    project.bump(entry);
            }
        }
        project.libStamp++;
        project.markDirty();
    }

    // -- configuration

    private void onConfigChanged(Event.ConfigChanged event) {
        var settings = event.params().getSettings();
        var debug = Settings.debug(settings);
        if( debug != null )
            Logger.setDebugEnabled(debug);
        var previous = configuration;
        configuration = Settings.apply(previous, settings, (message) -> engine.log(MessageType.Warning, message));

        if( !Objects.equals(previous.pluginRegistryUrl(), configuration.pluginRegistryUrl()) ) {
            plugins = new PluginSpecs(engine, configuration.pluginRegistryUrl());
            for( var project : projects() ) {
                for( var entry : project.entries() ) {
                    if( !entry.pluginRefs.isEmpty() )
                        project.bump(entry);
                }
                project.markDirty();
            }
        }
        var excludesChanged = !Objects.equals(previous.excludePatterns(), configuration.excludePatterns());
        if( excludesChanged ) {
            for( var project : folders ) {
                if( project.index != Project.IndexStatus.NOT_STARTED )
                    startWalk(project);
            }
        }
        if( excludesChanged || previous.errorReportingMode() != configuration.errorReportingMode() )
            ledger.republishAll(allEntryUris());
        scheduleEdit();
    }

    private Set<URI> allEntryUris() {
        var result = new TreeSet<URI>();
        for( var project : projects() ) {
            for( var entry : project.entries() )
                result.add(entry.uri);
        }
        return result;
    }

    // -- plugins

    private void onPluginFetched(Event.PluginFetched event) {
        if( !plugins.onFetched(event) )
            return;
        projects().forEach(Project::markDirty);
        scheduleBackground(Duration.ZERO);
    }

    // -- building

    private void scheduleEdit() {
        // while indexing, background slices keep running without delay,
        // so that an edit doesn't pause indexing for the debounce period
        scheduleBackground(isIndexing() ? Duration.ZERO : engine.debounce());
    }

    private void scheduleBackground(Duration delay) {
        engine.scheduleBackground(delay);
    }

    /**
     * Whether some project is walking or building its initial index.
     */
    boolean isIndexing() {
        for( var project : folders ) {
            if( project.index == Project.IndexStatus.WALKING || project.index == Project.IndexStatus.BUILDING )
                return true;
        }
        return false;
    }

    private static boolean canBuild(Project project) {
        return project.index == Project.IndexStatus.BUILDING || project.index == Project.IndexStatus.READY;
    }

    private boolean hasBackgroundWork() {
        for( var project : projects() ) {
            if( canBuild(project) && (project.index == Project.IndexStatus.BUILDING || !project.isFresh()) )
                return true;
        }
        return false;
    }

    private void markReadyIfDone(Project project) {
        if( project.index == Project.IndexStatus.BUILDING && project.isFresh() )
            project.index = Project.IndexStatus.READY;
    }

    /**
     * Take the longest prefix of the stale components that fits the
     * slice budget, without splitting a component.
     */
    private List<FileEntry> takeSlice(List<List<FileEntry>> components) {
        var budget = millisPerFile > 0
            ? (int) Math.max(1, Math.min(maxSliceFiles, SLICE_BUDGET.toMillis() / millisPerFile))
            : maxSliceFiles;
        var result = new ArrayList<FileEntry>();
        for( var component : components ) {
            if( !result.isEmpty() && result.size() + component.size() > budget )
                break;
            result.addAll(component);
        }
        return result;
    }

    /**
     * Build a batch and publish its diagnostics.
     */
    private void build(Project project, List<FileEntry> batch) {
        var clock = engine.environment().clock();
        var start = clock.millis();
        Builder.Result result;
        try {
            result = builder.build(project, batch);
        }
        catch( VirtualMachineError e ) {
            throw e;
        }
        catch( Throwable e ) {
            engine.logError("Unexpected error while building " + project, e);
            builder.fail(project, batch, e);
            ledger.publish(batch.stream().map(entry -> entry.uri).toList());
            return;
        }
        var elapsed = clock.millis() - start;
        var count = Math.max(1, result.built().size());
        millisPerFile = millisPerFile == 0 ? (double) elapsed / count : 0.8 * millisPerFile + 0.2 * elapsed / count;
        builds++;
        builtFiles += result.built().size() - result.reused().size();
        if( log.isDebugEnabled() )
            log.debug("built " + result.built().size() + " files of " + project + " in " + elapsed + " ms");
        ledger.publish(result.touched());
    }

    /**
     * Build the stale closure of the given entries, in slices,
     * without yielding.
     */
    private void buildClosure(Project project, Collection<FileEntry> roots) {
        for( int i = 0; i <= project.size() + 1; i++ ) {
            var live = roots.stream().filter(e -> project.entry(e.uri) == e).toList();
            var stale = project.staleClosure(live);
            if( stale.isEmpty() )
                return;
            build(project, takeSlice(stale));
        }
    }

    /**
     * Build every stale entry of a project, in slices, without yielding.
     */
    private void buildAll(Project project) {
        for( int i = 0; i <= project.size() + 1; i++ ) {
            var stale = project.staleEntries(engine.documents().uris());
            if( stale.isEmpty() )
                break;
            build(project, takeSlice(stale));
        }
        markReadyIfDone(project);
    }

    private void updateProgress() {
        var indexing = isIndexing();
        var done = 0;
        var total = 0;
        if( indexing ) {
            for( var project : folders ) {
                if( project.index == Project.IndexStatus.BUILDING || project.index == Project.IndexStatus.WALKING ) {
                    done += project.builtCount();
                    total += project.size();
                }
            }
        }
        progress.update(indexing, done, total);
    }

    // -- routing

    private final class WorkspaceRoute implements Route {

        private final Need need;

        private final List<Project> targets;

        private final Project project;

        private final URI uri;

        private final FileKind kind;

        WorkspaceRoute(Need need, List<Project> targets, Project project, URI uri, FileKind kind) {
            this.need = need;
            this.targets = targets;
            this.project = project;
            this.uri = uri;
            this.kind = kind;
        }

        @Override
        public void prepare() {
            switch( need ) {
                case NONE -> {}
                case FILE -> prepareFile(false, false);
                case FILE_AND_DEPENDENTS -> prepareFile(false, true);
                case PROJECT_CONFIGS -> prepareFile(true, false);
                case PROJECT, ALL -> targets.forEach(Workspace.this::buildAll);
            }
            updateProgress();
        }

        private void prepareFile(boolean configs, boolean dependents) {
            if( project == null )
                return;
            var roots = new ArrayList<FileEntry>();
            if( configs ) {
                for( var entry : project.entries() ) {
                    if( entry.kind == FileKind.CONFIG )
                        roots.add(entry);
                }
            }
            if( uri != null ) {
                // load the file on demand if it is not known yet
                var entry = project.entry(uri);
                if( entry == null ) {
                    entry = project.getOrCreate(uri, project.isDefault() ? FileEntry.Role.DEPENDENCY : FileEntry.Role.WORKSPACE);
                    noteAdded(project, uri);
                }
                if( entry != null && !roots.contains(entry) )
                    roots.add(entry);
            }
            buildClosure(project, roots);
            if( dependents && uri != null ) {
                var includers = new ArrayList<FileEntry>();
                for( var dependent : project.graph.transitiveDependents(List.of(uri)) ) {
                    var entry = project.entry(dependent);
                    if( entry != null )
                        includers.add(entry);
                }
                buildClosure(project, includers);
            }
            markReadyIfDone(project);
        }

        @Override
        public boolean isSatisfied() {
            return targets.stream().allMatch(p -> p.isIndexed() && p.isFresh());
        }

        @Override
        public boolean isIndexed() {
            return targets.stream().allMatch(Project::isIndexed);
        }

        @Override
        public RequestContext context(CancelChecker cancelChecker) {
            var configuration = Workspace.this.configuration;
            var view = project != null ? new ProjectHandle(engine, project, uri, kind) : null;
            var all = projects().stream()
                .map(p -> (ProjectView) new ProjectHandle(engine, p, null, null))
                .toList();
            return new RequestContext() {
                @Override
                public LanguageServerConfiguration configuration() {
                    return configuration;
                }

                @Override
                public ProjectView project() {
                    return view;
                }

                @Override
                public List<ProjectView> allProjects() {
                    return all;
                }

                @Override
                public CancelChecker cancelChecker() {
                    return cancelChecker;
                }

                @Override
                public String clientUri(String uri) {
                    return Uris.canonical(uri)
                        .flatMap(engine.documents()::get)
                        .map(DocumentStore.OpenDocument::clientUri)
                        .orElse(uri);
                }
            };
        }
    }

}

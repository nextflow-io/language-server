# Single-threaded language service core

- Authors: Ben Sherman
- Status: accepted
- Date: 2026-10-23

## Summary

The core of the language server (document state, compilation, AST cache, debouncing and workspace scan) has grown through a series of patches into a lock-and-timer design with many races and stale-state bugs. Replace it with a single engine thread that owns all state, processes notifications and requests in arrival order, and rebuilds files from their text whenever they are out of date.

## Problem Statement

Each workspace folder has a `ScriptService` and a `ConfigService`. Each service owns a `FileCache`, a `DebouncingExecutor`, an AST cache and an nf-lang `Compiler`. Notifications mutate the file cache on the lsp4j reader thread and schedule a debounced `update()` under the service monitor. Requests run on the ForkJoin common pool and either force an update, wait for the pending one with a timeout, or read the caches without any synchronization. A dependent file is re-analysed in place on its already-analysed AST, and the workspace scan is a deferred phase that runs only when an update round has no pending changes.

The bugs fall into a few categories:

- **Stale or partial answers.** The scan is deferred for as long as edits keep arriving, and the "initialize" progress ends before any scan, so references, rename and call hierarchy return partial results at startup and diagnostics stay stale until a file is touched (#175). `awaitUpdate` gives up after twice the debounce and returns whatever state exists. Only direct includers are re-checked when a module changes (#165), and config plugin versions, `lib/` and `nextflow_schema.json` invalidate nothing.
- **Non-idempotent re-analysis.** Running analysis phases again on an analysed AST duplicates warnings in a dependent on every edit of an included file (#108), wipes plugin-include errors when the file also has a local include, and leaves stale inferred types behind.
- **Races and deadlocks.** Hover, definition, references and workspace symbol read `HashMap`s and an `IdentityHashMap` while an update mutates them, causing a `ConcurrentModificationException` in `workspace/symbol` (#175). `FileCache` is written on the reader thread and read on the update thread, `Compiler.sourcesByUri` is written from a `parallelStream`, and plugin spec maps are shared between the two services' threads. A lock-order deadlock between `executeNow` and a scheduled update was fixed only recently (`f8c3857`).
- **Blocking and wasted work.** Plugin specs are fetched over HTTP inside the config update while it holds the service monitor, and failed fetches are retried on every update. The reader thread takes the service monitor in `initialize()`. An update with nothing to do still re-runs include resolution over every source unit, and it runs on every completion request (#158).

## Goals

- A request observes every notification that arrived before it, and never a state in the middle of an update.
- Published diagnostics converge to those of a fresh server on the same inputs.
- The lsp4j reader thread never blocks on a lock, I/O, compilation or the network.
- No deadlocks or data races by construction, rather than by careful lock ordering.
- An edit rebuilds only the edited file and its transitive dependents.
- Typing and hover stay responsive while a large workspace is indexed.

## Non-goals

- Changing feature providers (hover, completion, formatting, etc.) beyond the type of the AST view they receive.
- Changing `ServerCapabilities` or response shapes, apart from registering file watchers.
- Serving requests from multiple threads. This requires changes in nf-lang (see below).

## Considered Options

### Keep the current design and fix bugs individually

- Good, because the change to each bug is small.
- Bad, because most bugs come from the same few causes (shared mutable state, timed waits, in-place re-analysis), so each fix tends to expose the next one.

### Immutable snapshots read concurrently by a reader pool

Each change produces a new immutable snapshot, and requests run in parallel against the latest one (cf. gopls, rust-analyzer).

- Good, because requests don't wait for each other.
- Bad, because nf-lang ASTs are not safe to read concurrently. `TypeCheckingUtils.getType` caches inferred types on the node it reads, and hover and completion call it. Groovy node metadata is stored in a non-thread-safe map, and analysing one file writes into the nodes of the files it includes. A snapshot would need either deep copies of every AST, which nf-lang can't produce, or a lock around every read.

### Single engine thread with a FIFO queue

All state is confined to one thread, and requests run in the same queue as notifications (cf. tsserver).

- Good, because ordering and consistency follow from the queue, with no locks.
- Good, because it can be upgraded to concurrent readers later without changing providers.
- Bad, because a slow request delays the requests behind it.

## Solution

A single engine thread owns all documents, projects, compilers, ASTs and diagnostics. It processes a FIFO queue of events and requests, rebuilds out-of-date files by re-parsing them, and tracks freshness with dependency build stamps.

## Rationale & discussion

### Engine thread and FIFO queue

The lsp4j reader thread delivers notifications and requests in order on one thread. Each handler converts the message into an event or a request task and submits it to the engine's single-threaded executor, then returns. The engine therefore sees messages in exactly the order the client sent them, and a request observes every earlier notification and no later one. Every mutable structure has one owner, so there are no locks and no torn reads. The engine never waits on another thread except to join its own file reads, so it cannot deadlock.

A request that needs a single file brings that file and its dependencies up to date before it runs, but not its dependents. A request that needs the whole project (e.g. references and rename) is parked until the project is indexed, rather than answered with partial results. Once indexed, it builds whatever is stale synchronously and runs, so it never waits for background work to catch up with edits. Cancellation, not a timeout, is the escape hatch. A parked position-based request whose document changed while it waited fails with `ContentModified`, and a background build that keeps failing fails the parked requests instead of leaving them waiting.

### Re-parse instead of patching ASTs

nf-lang analysis phases are not idempotent on an analysed AST, and nf-lang has no AST copy API. Any file that must be rebuilt is re-parsed from its current text and analysed from scratch, so every AST in the cache reflects one complete build. This removes the whole class of duplicated and lost diagnostics, along with `LanguageServerErrorCollector.updatePhase` and `CachingResolveIncludeVisitor`. The cost is a parse per rebuilt file, and dependency tracking limits rebuilds to the files that need them. A file whose inputs are unchanged since its last build keeps that build, so opening or closing a file without edits does not invalidate its dependents.

### Dependency build stamps

Each file entry records the version of its input, a build stamp, and the stamps of the files it depends on. Edges are script includes, `includeConfig`, and from a script with plugin includes to the governing `nextflow.config`. Every path that an include could resolve to is an edge, whether or not the file exists (`./foo` resolves to `foo/main.nf` if `foo` is a directory, and to `foo.nf` otherwise), so creating or deleting a file invalidates its includers. An entry is fresh if its input is unchanged and every dependency it was built against is still fresh with the same stamp. An input change bumps one counter, and staleness of dependents follows from the stamps instead of being pushed eagerly. Freshness is computed on each query, in time linear in the files involved, so there is no cache to invalidate. Inputs that are not project files (`lib/`, `nextflow_schema.json`, plugin specs) bump the entries that read them when they change. Builds run in dependency order, so dependencies are type-checked before their dependents (#165).

### Workspace scan

The directory walk starts on `initialized` and runs on the IO pool. Its result enters the engine as an ordinary event that adds every discovered file as unbuilt. Building is ordinary background work, done in small time-bounded slices so that queued messages are handled in between, with open files and their dependencies first. There is no separate scan phase that can be deferred. Work-done progress ends only when every project is fully indexed, so a client can rely on it to mean that cross-file requests are complete. File watchers registered in `initialized` keep the file set current across git checkouts and external edits (#27).

### Debouncing

Debouncing exists to avoid rebuilding dependents and republishing diagnostics on every keystroke. It applies only to background builds, with a default of 500 ms. Requests never wait for a debounce; they build what they need immediately. Indexing and parked requests schedule background work with no delay. `DebouncingExecutor` is replaced by a single cancellable task on the engine.

### One project per workspace folder

The script and config services are merged into one `Project` per workspace folder, with a script `Compiler` and a config `Compiler` and one build pipeline. The script-to-config coupling for plugin versions becomes an ordinary dependency edge, so editing the plugin versions in `nextflow.config` updates the scripts that use them. One engine serves every folder, so cross-folder requests see a consistent view and each URI is published by exactly one project. Files outside every folder go to a default project with no root and no scan.

### Work off the engine thread

- **File reads** for a build run on a read pool. Parsing, name resolution and type checking stay on the engine: nf-lang writes to Groovy's shared `ClassHelper` nodes while parsing, and analysis writes across files and shares non-thread-safe caches.
- **Directory walks** run on an IO pool. Results return as events.
- **Plugin spec fetches** run on their own pool. While a spec is pending, errors that depend on it (such as "plugin does not exist") are suppressed. When the fetch completes, the configs that asked for it and the scripts that depend on them are rebuilt. Failed fetches are retried at most once every few minutes.

## Consequences

- Requests are serialized. A long request (references over a large workspace, workspace preview) delays the requests behind it, and long providers check for cancellation inside their loops to limit this.
- A cross-file request issued during indexing waits for indexing instead of returning partial results.
- A single-file request does not rebuild the dependents of its file, so data read from them can lag behind edits until the debounced background build runs.
- Rebuilding a widely included module re-parses all of its dependents. The cost is bounded by the dependency graph and spread over background slices.
- Providers receive a read-only view that is valid only during the request and only on the engine thread. Using it elsewhere throws.
- `LanguageService`, `ScriptService`, `ConfigService`, the AST caches, `FileCache`, `DebouncingExecutor` and `PluginSpecCache` are replaced.
- Concurrent readers remain possible. If nf-lang makes AST reads side-effect free, request bodies can run on a reader pool against immutable views of fresh entries. The engine already re-parses instead of mutating built entries, and the provider-facing view does not expose the threading model, so providers would not change.

## Links

- Community issues: #23, #27, #108, #158, #165, #175
- Prior art:
  - [gopls](https://github.com/golang/tools/tree/master/gopls)
  - [rust-analyzer](https://github.com/rust-lang/rust-analyzer)
  - [TypeScript tsserver](https://github.com/microsoft/TypeScript)

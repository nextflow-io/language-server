# Glossary

Terms of the language server. Nextflow language terms (process, workflow, config scope, ...) are defined by the [Nextflow docs](https://docs.nextflow.io/).

## Engine

**Engine**
: The single thread (`nextflow-engine`) that owns all state of the language server, and processes notifications and requests in arrival order from its mailbox. See [Internals](docs/internals.md#threads).

**Workspace**
: The `Workspace` class: the engine state that holds every project, the configuration, plugin specs, and diagnostics. Not to be confused with the client's workspace, which is a set of workspace folders.

**Workspace folder**
: A folder that the client opens as part of its workspace. Each one maps to a project.

**Project**
: The files of one workspace folder, with a script compiler, a config compiler, and a dependency graph. Files outside every workspace folder belong to the **default project**, which has no root and is never indexed.

**Document store**
: The open documents (text, version, client URI), keyed by canonical URI. An open document's text takes precedence over the file on disk.

**Canonical URI**
: The normalized `file:` URI that keys all engine state. The **client URI** is the spelling that the client sent, used only in responses and published diagnostics.

**Entry**
: The build state of one file in one project (`FileEntry`): its input version, stamp, dependency stamps, AST, and diagnostics. A **workspace** entry is part of the project's files; a **dependency** entry is loaded only because another file depends on it.

**Owner**
: The entry whose diagnostics are published for a URI, so that each URI is published by exactly one project.

## Builds

**Build**
: One run of the `Builder` over a batch: it parses each file from its text, analyses it, and commits the result. A build never re-analyses an AST that was already analysed. See [Internals](docs/internals.md#builds).

**Batch**
: The stale entries that one build processes, closed over their stale dependencies.

**Dependency graph**
: The edges from each file to the files it was built against: include targets for scripts, and governing `nextflow.config` files for plugins. Used to find the transitive dependents of a change.

**Stamp**
: The sequence number that an entry gets each time it is built. Dependents record the stamps they were built against.

**Input version**
: A sequence number that is bumped whenever an input of an entry may have changed. **Bumping** an entry makes it stale.

**Fresh**
: An entry is fresh if it was built from its current input version, and every dependency that it was built against still has the recorded stamp, all the way down. Otherwise it is **stale**.

**Indexing**
: The initial pass over a workspace folder: walk the directory, then build every file in slices, until the project is ready.

**Slice**
: A bounded piece of background building (at most 64 files or about 100 ms), run between mailbox tasks so that typing and hover stay responsive while indexing.

## Requests

**Need**
: How fresh a request needs the state to be before its body runs: the file (`FILE`), the configs of a project (`PROJECT_CONFIGS`), the whole project (`PROJECT`), or every project (`ALL`).

**Parked request**
: A request whose need cannot be met until a project is indexed. It waits in the engine and is retried after each slice.

**Provider**
: A class in `services.script` or `services.config` that answers one LSP feature, such as hover or completion, from a view.

**View**
: The read-only `ProjectView`, `ScriptView`, or `ConfigView` that a provider receives. Valid only during the request body, on the engine thread.

## Plugins

**Plugin spec**
: The config scopes, factories, functions, and operators of a plugin version, fetched from the registry and used to check config files and plugin includes.

**Registry**
: The Nextflow plugin registry at `nextflow.pluginRegistryUrl`, from which plugin specs are fetched off the engine thread and cached per registry. See [Configuration](docs/configuration.md#plugins).

## Distribution

**nf-lang**
: The Nextflow module that provides the parser, the AST, and the analysis passes for scripts and config files. Built from a Nextflow checkout in `../nextflow`.

**Standalone bundle**
: The release package for one platform: the shadow jar, a `jlink` runtime, and a launcher. See [ADR 20261021](docs/adr/20261021-standalone-bundle-aot-cache.md).

**Launcher**
: The `bin/nextflow-lsp` script of the bundle, which starts the bundled JVM and creates the AOT cache on first run.

**AOT cache**
: A JVM cache of loaded and linked classes that speeds up startup. Created on the user's machine by a training run (`AotTraining`), since it is tied to the exact runtime and class path.

**Release line**
: The language server versions for one stable release of Nextflow, such as 26.10.x, maintained on a `STABLE-*` branch and patched independently of Nextflow.

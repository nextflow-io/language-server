# Nextflow Language Server

The official [language server](https://microsoft.github.io/language-server-protocol/) for [Nextflow](https://nextflow.io/) scripts and config files.

The following language features are currently supported:

- code navigation (outline, go to definition, find references)
- completion
- diagnostics (errors, warnings)
- formatting
- hover hints
- rename
- semantic highlighting
- DAG preview for workflows
- config preview for processes

## Requirements

The language server requires Java 25 or later. The standalone bundle includes its own Java runtime.

Building requires a Corretto 25 JDK, which Gradle downloads if no matching JDK is installed.

## Configuration

The language server exposes a set of configuration settings that can be controlled through the `workspace/didChangeConfiguration` event. See the [Nextflow VS Code extension](https://github.com/nextflow-io/vscode-language-nextflow/blob/master/package.json) for an example of how to set up these configuration settings with sensible defaults.

## Development

To build from the command line:

```sh
# clone Nextflow repository
git clone https://github.com/nextflow-io/nextflow ../nextflow

# build language server (with nf-lang module)
make
```

To run the language server:

```sh
java -jar build/libs/language-server-all.jar
```

Protocol messages are exchanged using standard input/output.

### Standalone bundle

Standalone bundles are built for Linux (x64, arm64) and macOS (arm64). Windows is not supported.

To build a standalone bundle for the current OS/arch:

```sh
make bundle
```

To build the bundles for all platforms (on any supported host):

```sh
make bundle-all
```

Each bundle (`build/bundle/nextflow-language-server-<version>-<os>-<arch>`, plus a `.tar.gz`) contains the shadow jar, a `jlink` runtime with only the required JDK modules, and a training workspace (the script and config files of [nf-core/methylseq](https://github.com/nf-core/methylseq)). The runtime is linked from a pinned Corretto release (see `gradle/bundle.gradle`), which is downloaded for each platform and verified against its SHA-256 checksum. The version is taken from `git describe`, and can be overridden with `-Pversion=<version>`. Only the bundle for the current OS/arch is tested by the build; the CI tests each bundle on its platform. Run the server with `bin/nextflow-lsp`; extra JVM options can be passed with `NXF_LSP_JAVA_OPTS`, and JVM messages are written to stderr.

The launcher uses an AOT cache to speed up startup and warm-up. The cache is created on the user's machine, since it is only valid for the exact runtime, jar and JVM options. On first launch, the server starts without it while the cache is trained in the background (~30 s, at low priority), which indexes, edits and queries every file of the training workspace. Run `bin/nextflow-lsp --train` to create it in the foreground instead (e.g. after installing the bundle), or set `NXF_LSP_AOT=false` to disable it. The cache is stored in `lib/` if writable, otherwise in the user cache directory (`$XDG_CACHE_HOME` or `~/.cache` on Linux, `~/Library/Caches` on macOS), and it is recreated when the jar is newer.

## Testing

To run unit tests:

```sh
make test
```

To run the performance benchmark:

```sh
make bench
```

The benchmark (`scripts/bench.py`, requires Python 3) builds the shadow jar, clones [nf-core/rnaseq](https://github.com/nf-core/rnaseq) into `build/bench/rnaseq`, and drives the language server over stdio through a scripted session: startup and workspace indexing, opening and editing `workflows/rnaseq/main.nf`, and common requests (hover, completion, definition, references, rename, etc). It reports min/median/p95/max latency for each operation, as well as the peak RSS of the server. Latencies for didOpen and didChange are measured up to the corresponding diagnostics, so they include the 1 second update debounce.

Every response is validated, so the benchmark also serves as an integration test. Use `--server-cmd` (or `NXF_LSP_BENCH_SERVER`) to benchmark a different server build, e.g. `scripts/bench.py --workspace build/bench/rnaseq --server-cmd ./nextflow-lsp`. The workspace is cloned by `scripts/bench-workspace.sh`.

To compare the current checkout against another version:

```sh
make bench-compare
scripts/bench-compare.sh --base v26.04.5 --rounds 5
scripts/bench-compare.sh --base-cmd "java -jar old.jar" --head-cmd ./nextflow-lsp
```

This builds the base ref in a git worktree at `build/bench/base`, runs the benchmark alternately for base and head in each round (results in `build/bench/compare`), and compares the pooled samples with `scripts/bench_diff.py`. A change is reported as faster/slower only if it is significant (Mann-Whitney U test, p < 0.05) and at least 5%; otherwise it is reported as noise. When `--base-cmd` is given, the base worktree is not built. See `scripts/bench-compare.sh --help` for all options. The diff script can also be used directly, e.g. `scripts/bench_diff.py --base a1.json a2.json --head b1.json b2.json`.

## Releasing

The Nextflow language server follows the versioning scheme of Nextflow. For each stable release of Nextflow, there is a corresponding stable release of the language server. There is no correlation between patch releases of Nextflow and the language server -- they are patched independently of each other.

A separate branch is maintained for each stable release, starting with `STABLE-24.10.x`. The `main` branch corresponds to the upcoming stable release. Updates to `main` should be backported as needed to maintain a consistent user experience, for example, when a new LSP configuration option is added.

When updating the nf-lang version in `build.gradle`, make sure to also update the `nextflowRuntime` dependencies, which can be retrieved from `plugins-info.txt` in the Nextflow repo.

To make a new release of the language server:

1. Checkout the appropriate branch of Nextflow (`STABLE-*` if present, otherwise `master`).
2. Checkout the appropriate branch of the language server (`STABLE-*` if present, otherwise `main`).
3. Build the language server locally.
4. Run `make test` and `make bench`. Stop the release if anything fails.
5. Create a new GitHub release with the language server JAR and a list of notable changes. No release commit.

## Troubleshooting

Sometimes the language server might crash or get out of sync with your workspace. If this happens, you can restart the server from the command palette. You can also view the server logs from the "Output" tab under "Nextflow Language Server".

When reporting an issue, please include a minimal code snippet that triggers the issue as well as any error traces from the server logs.

## Credits

Based on [GroovyLanguageServer/groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server).

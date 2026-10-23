# Contributing

## Building

The language server is built with Gradle and a Corretto 25 JDK, which Gradle downloads if no matching JDK is installed. It depends on the nf-lang module of [Nextflow](https://github.com/nextflow-io/nextflow), which `make` builds from `../nextflow`:

```sh
git clone https://github.com/nextflow-io/nextflow ../nextflow
make
```

The jar is compiled for Java 17, since it is also released for platforms without a standalone bundle. To run it:

```sh
java -jar build/libs/language-server-all.jar
```

Design decisions are recorded in [`adr/`](adr).

### Standalone bundle

The standalone bundle contains the shadow jar, a `jlink` runtime with only the required JDK modules, and a launcher (`bin/nextflow-lsp`) that creates an AOT cache on the user's machine to speed up startup. See `gradle/bundle.gradle` and `src/bundle/nextflow-lsp` for details.

```sh
make bundle        # current platform, verified by training the AOT cache
make bundle-all    # all platforms: linux-x64, linux-arm64, macos-arm64
```

Bundles are written to `build/bundle/`. The version is taken from `git describe` and can be overridden with `-Pversion=<version>`. The runtime is linked from the latest Corretto 25 release, which can be overridden with `-PcorrettoVersion=<version>`.

### Testing with VS Code

To install a local build for the [Nextflow VS Code extension](https://github.com/nextflow-io/vscode-language-nextflow):

```sh
VERSION=<version> make install
```

This builds the bundle for the current platform and extracts it into `~/.nextflow/lsp/vX.Y/`, where the extension installs downloaded bundles. The extension uses it in place of the release with the same version, so the version must be the latest release of the release line selected in the extension (`nextflow.languageVersion`), e.g. `26.10.1`.

## Testing

```sh
make test                     # all unit tests
make test class=<test class>  # a single test class
```

Tests resolve plugins from a local registry that serves the fixtures in `src/test/resources/registry/`. To test against a new plugin release, add the registry response for it (`GET https://registry.nextflow.io/api/v1/plugins/<name>/<version>`) as `registry/v1/plugins/<name>/<version>.json`.

### Benchmark

```sh
make bench
```

The benchmark clones [nf-core/rnaseq](https://github.com/nf-core/rnaseq) into `build/bench/rnaseq` and drives the language server through a scripted LSP session, reporting the latency of each operation and the peak memory of the server. Every response is validated, so the benchmark also serves as an integration test. See `scripts/bench.py --help`.

To compare the current checkout against another version:

```sh
make bench-compare                                   # against main
scripts/bench-compare.sh --base v26.04.5 --rounds 5
scripts/bench-compare.sh --base-cmd "java -jar old.jar" --head-cmd ./nextflow-lsp
```

See `scripts/bench-compare.sh --help` for all options.

## Releasing

The Nextflow language server follows the versioning scheme of Nextflow. For each stable release of Nextflow, there is a corresponding stable release of the language server. There is no correlation between patch releases of Nextflow and the language server -- they are patched independently of each other.

A separate branch is maintained for each stable release, starting with `STABLE-24.10.x`. The `main` branch corresponds to the upcoming stable release. Updates to `main` should be backported as needed to maintain a consistent user experience, for example, when a new LSP configuration option is added.

When updating the nf-lang version in `build.gradle`, make sure to also update the `nextflowRuntime` dependencies, which can be retrieved from `plugins-info.txt` in the Nextflow repo.

To make a new release of the language server:

1. Checkout the appropriate branch of Nextflow (`STABLE-*` if present, otherwise `master`) and the language server (`STABLE-*` if present, otherwise `main`).
2. Run `make test` and `make bench`. Stop the release if anything fails.
3. Run the [Bundle](.github/workflows/bundle.yml) workflow manually from the same branch of the language server, with the release version (e.g. `26.10.1`). The workflow builds the bundles for all platforms, tests each one on its platform, and creates a draft GitHub release `v<version>` with the bundles, their SHA-256 checksums, and the language server JAR.
4. Add a list of notable changes to the draft release and publish it. The release tag is created at the built commit when the release is published. No release commit.

The JAR is included as a fallback for platforms without a bundle and for releases of the VS Code extension that predate the bundle, so it must run on Java 17.

Stable branches that predate the bundle (`STABLE-26.04.x` and earlier) are released with the language server JAR only:

1. Checkout the appropriate branch of Nextflow (`STABLE-*`) and the language server (`STABLE-*`).
2. Build the language server locally.
3. Run `make test` and `make bench`. Stop the release if anything fails.
4. Create a new GitHub release with the language server JAR (`build/libs/language-server-all.jar`) and a list of notable changes. No release commit.

# Distribute the language server as a GraalVM native image

- Authors: Ben Sherman
- Status: proposed
- Deciders: Ben Sherman (pending review on #146)
- Date: 2026-08-04
- Tags: build native-image graalvm distribution ci reflection testing

Technical Story: https://github.com/nextflow-io/language-server/pull/146

## Summary

The language server ships as a fat JAR that the client launches with `java -jar`, costing ~400 ms of JVM startup on every editor session and requiring a JRE on the user's machine. We add a GraalVM `native-image` build producing prebuilt `nextflow-lsp` binaries for four platforms, which start in ~10 ms and need no JRE. The JAR remains the primary artifact; the binaries are additive.

## Problem Statement

A language server is launched once per editor session, and again after every crash or manual restart, and the editor has no Nextflow features until it answers `initialize`. Measured locally on Linux x86_64, the JAR takes ~400 ms to answer `initialize`; a native image takes ~10 ms. The JAR also requires a compatible JRE, which the VS Code extension must locate or bundle.

Compiling this particular application ahead of time is not routine, because much of what it does is dynamic:

- Gson deserializes every LSP message type reflectively.
- Groovy's `Java8.configureClassNode` reads `getDeclaredFields`/`getDeclaredMethods` on the Nextflow DSL and value-type classes to build the `ClassNode`s that back completion, hover, and type checking.
- Groovy's `invokedynamic` call sites reach `MethodHandleNatives` methods that `native-image` cannot compile at all.

So the decision is not only "should we ship a binary" but "can we produce a binary that behaves identically to the JAR, and how would we know". The second half is where the risk concentrates: a native image missing reflection metadata does not fail to build and does not crash. It answers `initialize` and then returns empty — or plausible but wrong — results.

## Goals or Decision Drivers

- **Startup latency.** The user-visible cost of launching the server should be negligible.
- **No JRE prerequisite** for consumers of the binary.
- **Behavioural equivalence with the JAR.** A binary that differs from the JAR is worse than no binary, because the difference is invisible to whoever ships it.
- **No silent failure.** A metadata gap must fail the build or the test, not degrade the binary in production.
- **Metadata derived from the code, not from a script's behaviour.** Adding a request to a test script must not be a prerequisite for a class being registered.
- **CI cost proportionate to value.** Four native builds per commit is expensive, and one of the four runs on a billed runner.
- **Don't disturb the existing distribution.** The JAR and its release process must keep working unchanged.

## Non-goals

- **Replacing the JAR.** It remains the primary artifact and the thing `make install` and the README describe. The binaries are additional release assets.
- **Wiring the binaries into a client.** Nothing consumes them yet; teaching the VS Code extension to prefer a native binary is separate work.
- **Windows support.** `build-native.sh` and the matrix cover Linux and macOS only.
- **Minimising binary size.** 78 MB is accepted.
- **Runtime performance tuning.** `native-image` recommends G1GC, PGO and `-march=native`; none are applied, since startup was the objective and steady-state throughput is already adequate.
- **Fixing the config-option type nondeterminism found while testing.** Filed as #172; a pre-existing display bug, unrelated to the image.

## Considered Options

- Keep the JAR only
- GraalVM `native-image`
- AppCDS or Project CRaC on a normal JVM
- `jlink`/`jpackage` bundling a trimmed runtime with the JAR

## Pros and Cons of the Options

### Keep the JAR only

- Good, because it is one artifact for all platforms and needs no per-platform CI.
- Good, because behaviour is whatever the tests already cover — no second execution model to validate.
- Bad, because ~400 ms of startup is paid on every session and every restart.
- Bad, because it makes the client responsible for finding a JRE.

### GraalVM `native-image`

- Good, because it removes essentially all startup cost (~400 ms → ~10 ms) and the JRE dependency.
- Good, because a single self-contained file is the simplest thing for a client to launch.
- Bad, because correctness now depends on reflection metadata declared ahead of time, and the failure mode is silent.
- Bad, because it needs a build per platform, including a billed macOS runner for Intel.
- Bad, because it depends on `--report-unsupported-elements-at-runtime`, which is deprecated upstream (see Rationale).
- Bad, because the binary is 78 MB against a 15 MB JAR, per platform.

### AppCDS or CRaC

- Good, because it keeps one execution model, so existing tests remain representative.
- Good, because no reflection metadata problem arises.
- Bad, because it reduces JVM startup rather than removing it — not the order-of-magnitude change we want. Not benchmarked here, so this is a judgement rather than a measurement.
- Bad, because CRaC needs a specific JDK and a checkpoint step, and still requires a runtime on the user's machine.

### `jlink`/`jpackage`

- Good, because it removes the JRE prerequisite without ahead-of-time compilation.
- Good, because behaviour is identical to the JAR by construction.
- Bad, because startup remains JVM startup.
- Bad, because it produces a per-platform bundle anyway, so the CI cost is comparable without the latency benefit.

## Solution or decision outcome

Add a GraalVM 21 `native-image` build producing `nextflow-lsp` for linux-amd64, linux-arm64, macos-intel and macos-silicon; derive the reflection metadata from the resolved classpath rather than from a traced session; and gate the build on a JAR-versus-binary response equivalence check. The JAR remains the primary artifact.

## Rationale & discussion

### Toolchain and build entry point

GraalVM 21 (Oracle distribution) via `graalvm/setup-graalvm`, driven by the `org.graalvm.buildtools.native` Gradle plugin with `toolchainDetection = false`, so the build uses the GraalVM JDK it was invoked with. The project still compiles to Java 17 bytecode with a 21 toolchain. `build-native.sh` is the entry point for both CI and local use: it checks for `native-image`, invokes `nativeCompile`, and runs the equivalence test.

`--initialize-at-build-time` is set for the Groovy, SLF4J and `java.beans` packages, which is what makes the Groovy runtime usable in an image at all.

### How reflection metadata is obtained

This is the crux of the approach, and the original PR got it wrong in a way worth recording.

The metadata came from running the tracing agent against one scripted LSP session (`lsp-simulator.sh`), which makes it a function of the script rather than of the code. The script never sent `workspace/didChangeConfiguration`, which is what `NextflowLanguageServer.initializeWorkspaces()` responds to, so the language services were never initialized during tracing and the agent observed almost nothing beyond lsp4j plumbing: 2 of its 198 entries were under `nextflow.*`. Two failures were then confirmed against the built binary:

- Missing `DidChangeConfigurationParams` — the server answered `initialize` and returned empty results for every language feature.
- Missing `nextflow.script.dsl.*` — the server reported `Unrecognized process input qualifier 'val'` on a valid script. A well-formed, non-empty, wrong answer that a user would file as a language bug.

Neither failed the build. Groovy's indy call sites force `--report-unsupported-elements-at-runtime`, which converts what would be build errors into runtime no-ops, so missing metadata degrades the binary silently. The build's own check grepped for an `initialize` response, which a fully broken binary still produces.

Broadening the simulator (send `didChangeConfiguration`, open a `.config` file, invoke `executeCommand`) fixed both failures and took tracing from 198 to 270 entries. It was still not the right strategy, because coverage remained accidental — even broadened, tracing reaches 2 of the 35 `nextflow.script.types.**` classes.

So `generateNativeImageMetadata` walks the resolved runtime classpath and emits `reflect-config.json`, `resource-config.json` and `proxy-config.json`, registering **629 classes** statically. This is sound because the reflected-over sets are *closed*: Gson reflects over every type in `org.eclipse.lsp4j`, and Groovy's `configureClassNode` over every class handed to `ClassHelper.makeCached`. Those are properties of the jars, so a scan enumerates them exhaustively, and a dependency upgrade is picked up automatically.

Flags are per group rather than uniform, because cost is driven by reachability, not entry count. lsp4j's message types need `allDeclaredFields` plus the no-arg constructor Gson calls and nothing else — Gson never invokes their accessors, and `allDeclaredMethods` across 538 classes is the single largest size lever in the config. The Nextflow DSL and value-type classes *do* need `allDeclaredMethods`, because `Java8.configureAnnotation` invokes annotation accessors to read `@Description`/`@Constant`/`@Ops` members. Classes only walked for members get query-only flags. (A related correction: the lsp4j types have public no-arg constructors, so they need `allDeclaredConstructors`. Gson falls back to `UnsafeAllocator` — whose error message names `unsafeAllocated`, which is misleading — only when the constructor is unregistered.)

Four gaps no traced session reached, each established from a call site rather than inferred:

- `nextflow.script.types.**`, including the `.shim` value types and their *package-private* `*Ops` interfaces, which back completion and hover on `String`/`List`/`Map`/`Path`.
- `nextflow.config.spec.**`. An earlier draft named `nextflow.config.schema.` — a near-identical decoy package (both carry `ConfigOption`, `ConfigScope`, `ScopeName`) that nothing in the codebase imports.
- `spec/definitions.json`. `ConfigSpecFactory` is the only classpath-resource read in the server, and it runs only for `.config` files, which no traced session opened.
- The `LanguageClient`/`Endpoint` proxy pair. A control build without the traced config dies at startup with `MissingReflectionRegistrationError`.

Tracing is kept as a secondary source for three categories that resist enumeration: Groovy indy call sites reaching `MethodHandleNatives`; JDK-internal resources with version-specific paths (`jdk/internal/icu/impl/data/icudt72b/...`); and the JSSE provider graph loaded reflectively for the plugin-registry HTTPS call. Config directories are ordered static → traced → manual, and `native-image` merges them.

### Verification

Unit tests run on the JVM and say nothing about the binary, and no self-contained assertion catches the failure that matters: the `Unrecognized process input qualifier` binary returned well-formed, non-empty, plausible responses. The correct answer is whatever the JAR says, which makes the JAR the only available oracle.

`verify-native.py` therefore runs the same LSP session against both builds and diffs 27 messages order-insensitively (`references` and `rename` legitimately differ in ordering). It was validated in both directions — it passes on a correct binary and fails on a deliberately under-registered one, reporting the missing-metadata error, the unanswered request ids, and the response diff. It replaced the grep-based checks in `build-native.sh`, which it subsumes, and it is what turns "no silent failure" from an aspiration into a gate.

Its config probe deliberately targets the `docker` scope rather than `process`, because the server's overload resolution for process directives varies between runs (#172). Holding the binary to a value the JAR does not reproduce would make the check flaky for reasons unrelated to the image.

### Platform matrix and CI cost

Four platforms: `ubuntu-latest`, `ubuntu-24.04-arm`, `macos-latest-large` (Intel), `macos-latest` (Apple Silicon). Since `macos-13` was retired there is no free Intel macOS runner, so `macos-intel` is billed per minute; the alternative is dropping that platform, which is a product call rather than a build one. `macos-latest` is free and Apple Silicon, so the original `macos-latest-xlarge` was replaced.

Builds run on pushes to `main`, on `v*` tags, and on pull requests — not on pushes to every branch, which duplicated all four builds on every PR commit for eight native builds per push. `timeout-minutes` bounds a hung compile, matching the existing `build.yml`.

### Release packaging

Each job tars its binary as `nextflow-lsp-<platform>.tar.gz`; the release job collects them, writes `checksums.txt`, and attaches everything to the release for the tag. This aligns with the repository's existing convention — releases already exist as `v*` tags matching Nextflow versions (`v26.04.3` and so on), cut from `STABLE-*` branches, and tags fire independently of the branch they point at.

The original release job could not have worked: it collected `*.tar.gz` while the build uploaded a bare binary, and read the version from `refs/tags/` while the workflow set `tags-ignore: '*'`.

### Gradle wiring

`native-image` reads its config through `-H:ConfigurationFileDirectories`, which Gradle cannot see. `dependsOn` orders tasks without invalidating anything, so a metadata change reported `nativeCompile UP-TO-DATE` and silently reused the previous binary — precisely the failure this ADR exists to prevent, occurring inside the build itself. The directories are declared with `inputs.files(tasks.named(...))`, which registers the outputs and the dependency together. This bit twice: the first fix covered only the checked-in config directory, not the generated ones.

Separately, the `jar` task carried `duplicatesStrategy = EXCLUDE` masking a real duplicate — `processResources` already copies `definitions.json` into `spec/`, so the task's own `from` block was redundant. Removing the block builds clean and still bundles the resource.

### Accepted costs

- Binary 78 MB per platform, against a 15 MB JAR.
- ~2 minutes of `native-image` compilation per platform, ~3 minutes per job, plus ~40 s for the two LSP sessions the equivalence test runs.
- One billed macOS runner per build.
- A second execution model to keep working, which is what the equivalence test exists to make cheap.

### Residual risks and open questions

- **`--report-unsupported-elements-at-runtime` is load-bearing and deprecated.** Removing it fails the build with `DeletedElementException` on `MethodHandleNatives.setCallSiteTargetNormal`, reached from Groovy's indy call sites. A future GraalVM that drops the flag will need a different answer; this is the largest strategic risk to the approach.
- **The registered package set is a judgement call.** It derives from call sites we read, so a future feature reflecting over a new package will not be registered automatically. The mitigation is `verify-native.py`: such a gap surfaces as a response divergence rather than a user report.
- **~99 Groovy AST-transformation annotation and transformation classes** were flagged as possibly needed. They are left unregistered because nothing in the server's parse-only path was shown to reach them.
- **The release action may overwrite manually authored release notes.** `softprops/action-gh-release` with `generate_release_notes: true` updates the existing release for a tag, and the current process writes those notes by hand. Worth checking on the first real tagged run.
- **Nothing consumes the binaries yet.** They are published but unused until a client selects them.

## Links

- Implemented in [PR #146](https://github.com/nextflow-io/language-server/pull/146)
- Uncovered [issue #172](https://github.com/nextflow-io/language-server/issues/172) — config completion and hover show an arbitrary type for options with multiple accepted types

## More information

- [What is an ADR and why should you use them](https://github.com/thomvaill/log4brains/tree/master#-what-is-an-adr-and-why-should-you-use-them)
- [ADR GitHub organization](https://adr.github.io/)
- [GraalVM native image reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)

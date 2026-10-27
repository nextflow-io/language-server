# Standalone bundle with Java 25 and an AOT cache

- Authors: Ben Sherman
- Status: accepted
- Date: 2026-10-21

## Summary

Distribute the language server as a standalone bundle for each platform, containing the jar, a trimmed Java 25 runtime, and a launcher that creates a JDK AOT cache on the user's machine. The jar remains available as a fallback.

## Problem statement

The language server was distributed only as a jar, so every user had to install a compatible Java (17 or later) and make sure that the editor could find it.

The server also started slowly. On nf-core/rnaseq with Java 21, `initialize` took ~420 ms and indexing the workspace took ~6 s.

## Goals

- No Java installation required on the main platforms.
- Faster startup and workspace indexing.
- No regression in editing latency (recompiling after an edit), which is what users notice most once the server is running.
- Low maintenance: the same code paths as the jar, and no extra metadata that has to be kept in sync with the code.

## Non-goals

- Windows and Intel macOS bundles. These platforms keep using the jar until we want to commit to supporting them.
- Reducing memory usage.

## Considered options

### GraalVM native image

Compile the server into a native executable ([#146](https://github.com/nextflow-io/language-server/pull/146)).

- Good, because `initialize` takes ~10 ms, indexing is ~16% faster than the bundle, peak memory is ~57% lower, and the download is smaller.
- Bad, because the closed-world assumption means any reflective or dynamic path missed by the reachability metadata fails at runtime with no fallback. This is a real risk for a Groovy-based server that compiles arbitrary user scripts.
- Bad, because recompiling after an edit is ~60 ms (~20%) slower, since the warmed-up JIT beats AOT-compiled code on the compiler hot path.
- Bad, because it is a separate build with reachability metadata to maintain.
- Bad, because GraalVM is being decoupled from the Java release train and GraalVM 25 dropped macOS x64, whereas Project Leyden is the strategic direction for OpenJDK.

### Java 25 runtime with an AOT cache

Bundle a `jlink` runtime with the jar, and use the AOT cache from Project Leyden ([JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514), [JEP 515](https://openjdk.org/jeps/515)) to preload classes and method profiles.

- Good, because `initialize` is ~42% faster and indexing ~30% faster than the jar on Java 21, with references ~29% faster and editing latency unchanged.
- Good, because code not covered by the cache runs as normal bytecode, so an incomplete cache only costs performance.
- Good, because it runs the same jar and code paths as the existing release.
- Bad, because the bundle is larger than a native executable (~33-38 MB compressed) and uses as much memory as the jar.

## Solution

Build a standalone bundle for linux-x64, linux-arm64 and macos-arm64 with a Java 25 runtime, and create the AOT cache on the user's machine. Keep releasing the jar for other platforms.

## Rationale & discussion

### Bundle contents

Each bundle contains the shadow jar, a `jlink` runtime with only the JDK modules that the server needs, and a launcher (`bin/nextflow-lsp`). The runtime is cross-linked from Amazon Corretto, so all bundles can be built on a single host. The build uses the latest Corretto 25 release rather than a pinned version, since the runtime is private to the bundle and patch releases only bring fixes.

Unused dependencies and native libraries are excluded to reduce the bundle size. This also reduces the jar size.

### AOT cache

An AOT cache is specific to the exact JVM, platform and jar, and the cache for the language server is ~46 MB. Shipping it would roughly double the download, so the launcher creates it on the user's machine instead:

- The server starts immediately. If there is no valid cache, the launcher trains one in the background at low priority, so only the first launch is slower.
- Training (`nextflow.lsp.AotTraining`) runs the real server and an LSP client in one JVM on a bundled copy of nf-core/methylseq. It exercises indexing, editing and the main requests, takes ~30 s, and doesn't need network access.
- A cache is used only if it is newer than the jar, and a failed training is not retried until the jar changes.

A cache trained on the user's machine performed within noise of one trained at build time.

### Jar fallback

The jar is still compiled for Java 17, the minimum Java version for Nextflow, so it runs on the user's Java as before. It is used for platforms without a bundle, for older release lines that only publish the jar, and by editor integrations that run the jar directly.

The VS Code extension uses the bundle if the release has one for the current platform, and falls back to the jar otherwise.

### Revisiting native image

Native image remains ahead on startup time, memory and size. It could be revisited if memory becomes the main concern, or if the AOT cache stops improving in new Java releases.

## Links

- Native image comparison: [#146](https://github.com/nextflow-io/language-server/pull/146)
- Implementation: [#191](https://github.com/nextflow-io/language-server/pull/191)
- VS Code extension: [nextflow-io/vscode-language-nextflow#249](https://github.com/nextflow-io/vscode-language-nextflow/pull/249)

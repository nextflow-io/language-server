# GraalVM native image build

- Authors: Ben Sherman
- Status: proposed
- Date: 2026-08-04

## Summary

Build the language server as a [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/), which reduces startup time from ~400 ms to ~10 ms and eliminates the JRE dependency.

## Problem Statement

A language server is launched once per editor session, and again after every crash or manual restart. The editor has no Nextflow features until it answers `initialize`. Measured locally on Linux x86_64, the JAR takes ~400 ms to answer `initialize`, while a native image takes ~10 ms. The JAR also requires a compatible JRE, which the user must provide.

Compiling this particular application ahead of time is not routine, because much of what it does is dynamic:

- Gson deserializes every LSP message type reflectively.
- Groovy's `Java8.configureClassNode` reads `getDeclaredFields`/`getDeclaredMethods` on the Nextflow DSL and value-type classes to build the `ClassNode`s that back completion, hover, and type checking.
- Groovy's `invokedynamic` call sites reach `MethodHandleNatives` methods that `native-image` cannot compile at all.

So the decision is not only "should we ship a binary" but "can we produce a binary that behaves identically to the JAR, and how would we know". The second half is where the risk concentrates: a native image missing reflection metadata does not fail to build and does not crash. It answers `initialize` and then returns empty — or plausible but wrong — results.

## Goals

- **Startup latency.** The user-visible cost of launching the server should be negligible.
- **No JRE prerequisite** for consumers of the binary.
- **Behavioral equivalence with the JAR.** A binary that differs from the JAR is worse than no binary, because the difference is invisible to whoever ships it.
- **No silent failure.** A metadata gap must fail the build or the test, not degrade the binary in production.

## Non-goals

- **Replacing the JAR.** It remains the primary artifact; the native build is an optional alternative.
- **Minimizing binary size.** 78 MB is accepted.
- **Runtime performance tuning.** `native-image` recommends G1GC, PGO and `-march=native`; none are applied, since startup was the objective and steady-state throughput is already adequate.

## Considered Options

### Keep the JAR only

- Good, because it is one artifact for all platforms and needs no per-platform CI.
- Good, because there is one execution model, so the existing tests remain representative.
- Bad, because ~400 ms of startup is paid on every session and every restart.
- Bad, because it makes the client responsible for finding a JRE.

### GraalVM `native-image`

- Good, because it removes essentially all startup cost (~400 ms → ~10 ms) and the JRE dependency.
- Good, because a single self-contained file is the simplest thing for a client to launch.
- Bad, because correctness now depends on reflection metadata declared ahead of time, and the failure mode is silent.
- Bad, because it depends on `--report-unsupported-elements-at-runtime`, which is deprecated upstream (see Residual risks).
- Bad, because the binary is 78 MB against a 15 MB JAR, per platform.

### AppCDS or CRaC

- Good, because it keeps one execution model and raises no reflection metadata problem.
- Bad, because it reduces JVM startup rather than removing it — not the order-of-magnitude change we want. Not benchmarked here, so this is a judgment rather than a measurement.
- Bad, because CRaC needs a specific JDK and a checkpoint step, and still requires a runtime on the user's machine.

### `jlink`/`jpackage`

- Good, because it removes the JRE prerequisite, with behavior identical to the JAR by construction.
- Bad, because startup remains JVM startup.
- Bad, because it produces a per-platform bundle anyway, so the CI cost is comparable without the latency benefit.

## Solution

Add a GraalVM 21 `native-image` build producing `nextflow-lsp` for linux-amd64 and linux-arm64. Derive the reflection metadata from the resolved classpath rather than a traced session. Verify the build with an equivalent check against the JAR baseline.

## Rationale & discussion

### Extracting reflection metadata

`generateNativeImageMetadata` scans the resolved runtime classpath and emits `reflect-config.json`, `resource-config.json` and `proxy-config.json`, registering ~630 classes. Registration flags are set per group rather than uniformly, since image size is driven by reachability; the per-group reasoning lives in comments in `build.gradle`. The tracing agent remains a secondary source, for the tail that resists enumeration: Groovy indy call sites reaching `MethodHandleNatives`, JDK-internal resources with version-specific paths, and the JSSE provider graph loaded reflectively for the plugin-registry HTTPS call. `native-image` merges the two, static config first.

A scan is exhaustive where a traced session is accidental. The reflected-over sets are *closed* — Gson reflects over every type in `org.eclipse.lsp4j`, and Groovy's `configureClassNode` over every class handed to `ClassHelper.makeCached` — and those are properties of the jars, so scanning enumerates them completely and picks up dependency upgrades automatically. Traced coverage is instead a function of the script: the session used here reaches 2 of the 35 `nextflow.script.types.**` classes, and whatever it misses is not reported at build time, because the `--report-unsupported-elements-at-runtime` that Groovy's indy call sites force turns what would be build errors into runtime no-ops.

### Verification

`native/verify.py` runs one scripted LSP session (`native/simulate.sh`) against the JAR and the binary and diffs the responses order-insensitively; `native/build.sh` fails if they differ, in CI and locally.

The JAR is the only available oracle. Unit tests run on the JVM and say nothing about the binary, and no self-contained assertion separates a correct response from a plausible wrong one, which is the failure mode a metadata gap produces. Differential testing also needs no expected values, so it stays valid as the language evolves. The gate was validated in both directions: it passes on a correct binary and fails on a deliberately under-registered one, which is what makes "no silent failure" a property of the build rather than an aspiration.

### Residual risks

- **`--report-unsupported-elements-at-runtime` is load-bearing and deprecated.** Removing it fails the build with `DeletedElementException` on `MethodHandleNatives.setCallSiteTargetNormal`, reached from Groovy's indy call sites. A future GraalVM that drops the flag will need a different answer; this is the largest strategic risk to the approach.

- **The registered package set is a judgment call.** It derives from call sites we read, so a future feature reflecting over a new package will not be registered automatically. The mitigation is `native/verify.py`: such a gap surfaces as a response divergence rather than as a user report.

- **A macOS-specific regression would go unnoticed**, since CI builds Linux only.

## Links

- [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/)

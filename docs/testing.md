# Testing

Tests are the executable specification of the language server. Read them first to learn how a unit behaves, and add or update them with every behavior change.

## Running tests

| Task | Command |
|---|---|
| All tests | `make test` (or `./gradlew test`) |
| One class | `make test class=nextflow.lsp.services.script.ScriptHoverTest` |
| One class (Gradle) | `./gradlew test --tests 'nextflow.lsp.services.script.ScriptHoverTest'` |
| One method | `./gradlew test --tests 'nextflow.lsp.services.script.ScriptHoverTest.showLabelWhenHoveringOverProcess'` |
| One nested group | `./gradlew test --tests 'nextflow.lsp.engine.EngineTest$Parking'` |
| Rerun when up to date | `./gradlew test --rerun` |
| Update golden files | `./gradlew test -PupdateGolden`, then `git diff` |

Reports are written to `build/reports/tests/test/index.html`. CI runs `make test`.

Tests run sequentially. The default timeout is 60 s, as a hang guard only (`src/test/resources/junit-platform.properties`).

- Each test gets its own workspace directory under `build/testWorkspaces/`, created on first use and deleted after the test, so that tests never see each other's files. It is bound to the test thread, so use the workspace, `TestServer`, and `TestUtils` only from the test thread.
- Tests must not share mutable state.
- Tests that use real threads (the production engine, pools, or an HTTP server) have a `@Timeout` hang guard.

If parallel execution is restored, dynamic tests that share a server need `@Execution(ExecutionMode.SAME_THREAD)`, and tests that use real threads should be `@Isolated`.

## Libraries

- **JUnit Jupiter** (JUnit 6, including `junit-jupiter-params`) for running tests.
- **AssertJ** for all assertions (`assertThat`, `assertThatThrownBy`). Do not use JUnit's `Assertions`.

There is no Spock, Groovy test code, or mocking library. Tests are compiled for Java 17, like the main code, so they cannot use newer language features.

## Organization and naming

- One test class per unit: `FooTest` tests `Foo`, in the same package, under `src/test/java`. A provider's test drops the suffix (`ScriptHoverTest` tests `ScriptHoverProvider`).
- A behavior that spans units is named after the behavior (`DocumentUpdateTest`, `ClientUriTest`, `LanguageServerWiringTest`).
- A large unit is split by feature: `Workspace` is specified by `WorkspaceBuildTest`, `WorkspaceBuildIncludesTest`, `WorkspaceIndexTest`, `WorkspaceEventsTest`, `WorkspaceWatchedFilesTest`, and so on. `EngineTest` groups its behaviors with `@Nested` classes instead.
- When a test class grows past ~500 lines, split it by feature (e.g. `WorkspaceBuildIncludesTest`) or group it with `@Nested` classes, at the next substantial change to it, not as a separate cleanup.
- Start each test class with a Javadoc saying what it specifies. Add one when you change a class that lacks it.
- Method names are lowerCamel sentences that state the behavior, with the unit as the implied subject, and no `test` or `should` prefix: `showSignatureAndDocumentationWhenHoveringOverFunctionCall`, `ignoreInvalidRegistryUrlButApplyTheOtherSettings`. No `@DisplayName`.
- Add a method Javadoc only for what the name cannot say, such as the setup of a golden test.
- One behavior per test, with as many assertions as that behavior needs. Write inputs inline as text blocks, so that each test reads top to bottom on its own. Helpers (`getHoverHint`, `checkFormat`) may hide mechanics, not inputs or expectations.
- Use `@ParameterizedTest` with `@CsvSource` or `@MethodSource` for tables of cases, instead of a loop inside a test.

## Fixtures and helpers

All fixtures are in `src/test/java`, in `nextflow.lsp` (server level) or `nextflow.lsp.engine` (engine level).

| Fixture | Use it for |
|---|---|
| `TestWorkspace` | JUnit extension that gives each test its workspace. Register it with `@ExtendWith(TestWorkspace.class)` on every class that uses `TestServer`, `EngineHarness`, or `TestUtils`. |
| `TestServer` | The default for feature tests. Drives the real `NextflowLanguageServer` through its lsp4j interface, with the engine on the test thread and a fake clock. Paths are relative to the workspace. `TestServer.start(o -> ...)` sets options (folders, settings, client, environment, `settle`, `initialized`, `progress`). `open`/`change`/`write`/`writeAndNotify`/`delete` change documents and files; `call` sends a request and runs the engine until it answers, `send` only sends it; `settle` runs tasks and timers until idle. |
| `TestLanguageClient` | Records what the server sends: diagnostics, progress, registrations, log messages. Can hold progress creation (`setHoldProgressCreate`). |
| `TestUtils` | Static helpers on the current workspace: `writeWorkspaceFile`, `getUri`, `executeCommand`, `formatting`, `thrownOnAnotherThread`, and the `UNUSED` diagnostic message. |
| `Golden` | Golden files (see below). |
| `TestPluginRegistry` | A local HTTP server that serves the registry fixtures. Only for tests of the real `HttpPluginRegistryClient`. |
| `EngineHarness` | Drives an `Engine` on the test thread with a fake clock. Use it directly only to test the engine against a scripted state (`EngineTest`); `TestServer` wraps one (`server.harness()`). |
| `TestEnvironment` | Deterministic `EngineEnvironment`: `ManualExecutor` engine thread, `FakeClock`, read pool and IO on the calling thread, real file system, `FixtureRegistry`. Override parts with `withIoExecutor`, `withReadPool`, `withFiles`, `withRegistry`. Must be created on the test thread. |
| `FakeClock` | A clock that moves only when the test moves it (`harness.advance(duration)`). |
| `ManualExecutor` | The engine thread of `TestEnvironment`: tasks and timers run only when the test calls `runNext`, `runPending`, or `advance`. |
| `QueuedExecutor` | Holds IO tasks (registry fetches, workspace walks) in flight until the test runs them (`runAll`, `runLast`, or `server.settle(io)`). Use it to test what happens while IO is pending. |
| `DirectExecutorService` | Runs each task on the calling thread. The default read pool and IO executor of `TestEnvironment`. |
| `ForwardingFileAccess` | Forwards to the system file access. Subclass it to inject faults or observe reads (`withFiles(new ForwardingFileAccess() { ... })`). |
| `FixtureRegistry` | In-process plugin registry client over the registry fixtures. Records `fetches`, and returns `overrides` to simulate failures. Pass your own instance with `withRegistry(url -> registry)` to inspect it. |
| `FakeState` | Scripted `EngineState` that logs what the engine asks of it, for `EngineTest` only. |

Choosing a level:

- A pure unit (`Positions`, `Uris`, `DependencyGraph`, `DocumentStore`) is tested directly, without a workspace or server.
- A language feature or workspace behavior is tested through `TestServer`, with the deterministic environment.
- A timing or ordering behavior is tested by holding work with `QueuedExecutor` or `ManualExecutor` and moving the `FakeClock`. No `Thread.sleep`.
- Real threads only to test the production wiring (`LanguageServerWiringTest`, `WorkspaceConcurrencyTest`, `ThreadSerialExecutorTest`, `HttpPluginRegistryClientTest`).

```java
/**
 * Specifies hover hints in scripts.
 */
@ExtendWith(TestWorkspace.class)
class ScriptHoverTest {

    static String getHoverHint(TestServer server, String uri, Position position) {
        var hover = server.call(s -> s.hover(new HoverParams(new TextDocumentIdentifier(uri), position)));
        return hover != null ? hover.getContents().getRight().getValue() : null;
    }

    @Test
    void showLabelWhenHoveringOverWorkflowInvocation() {
        var server = TestServer.start();
        server.open("main.nf", """
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            """);

        var value = getHoverHint(server, getUri("main.nf"), new Position(4, 4));

        assertThat(value).startsWith("```nextflow\nworkflow HELLO {");
    }
}
```

## Golden files

Golden files hold whole outputs that are unwieldy to write inline. Each case is an input file and an expected file with the same base name, next to each other under `src/test/resources`:

| Directory | Input | Expected | Test |
|---|---|---|---|
| `format/script` | `.nf` | `.formatted` | `ScriptFormattingTest.formatGoldenFiles` |
| `format/config` | `.config` | `.formatted` | `ConfigFormattingTest.formatGoldenFiles` |
| `dag` | `.nf` | `.mmd` (Mermaid) | `PreviewDagTest.previewDagGoldenFiles` |

- One `@TestFactory` per directory, with one dynamic test per input file, named after the file. Adding a case is adding a file pair.
- File names say what is tested (`include-multi-line.nf`, `collapse-empty-ternary.nf`). One behavior per case.
- Formatting inputs are deliberately non-canonical, so that a refusal to format cannot pass as a no-op, and the output must be stable when formatted again.
- Use `Golden.inputs(dir, ext)`, `Golden.sibling(input, ext)`, and `Golden.check(expected, actual)`. A failure names the expected file and the first differing line, and IDEs can show the diff.
- Golden files are compared byte for byte, with LF line endings (`.gitattributes`). Open inputs with `openVerbatim`, not `open`, which strips indentation.

**Updating**: `./gradlew test -PupdateGolden` sets `golden.update=true` and always reruns the tests. `Golden.check` then writes the actual output to the expected file instead of comparing. Review every change with `git diff` before committing: the diff is the review.

## Plugin registry fixtures

Tests never use the public plugin registry. They resolve plugins from the fixtures in `src/test/resources/registry/`, through `FixtureRegistry` (the default of `TestEnvironment`) or `TestPluginRegistry` (over HTTP).

To test against a new plugin release, add the registry response for it (`GET https://registry.nextflow.io/api/v1/plugins/<name>/<version>`) as `registry/v1/plugins/<name>/<version>.json`. A plugin or version without a fixture is "not found".

## Regression tests

A fix for a GitHub issue gets a test that fails without the fix. Name it after the behavior, not the issue, and give it a Javadoc that starts with the issue number and says what must hold.

- A regression in one unit goes in that unit's test class (e.g. `#108` in `WorkspaceBuildTest`).
- An end-to-end regression of a whole session, which belongs to no single unit, goes in `IssueRegressionTest`, one method per issue:

```java
/**
 * #175: a cross-file request sent right after didOpen, while the
 * workspace is still being indexed, waits for the index and gets
 * full results.
 */
@Test
void crossFileRequestAfterOpenGetsFullResults() { ... }
```

## Benchmark

```sh
make bench
```

The benchmark clones [nf-core/rnaseq](https://github.com/nf-core/rnaseq) into `build/bench/rnaseq` (`scripts/bench-workspace.sh`) and drives the shadow jar through a scripted LSP session, reporting the latency of each operation and the peak memory of the server. Every response is validated, so the benchmark also serves as an integration test against a real pipeline: it exits non-zero on failure. It is part of every release (see `CONTRIBUTING.md`), and the Bundle workflow runs it against each bundle. See `scripts/bench.py --help`.

To compare the current checkout against another version:

```sh
make bench-compare                                   # against main
scripts/bench-compare.sh --base v26.04.5 --rounds 5
scripts/bench-compare.sh --base-cmd "java -jar old.jar" --head-cmd ./nextflow-lsp
```

The base ref is built in a git worktree, base and head run alternately in each round, and a change is reported only if it is significant (Mann-Whitney U test, p < 0.05) and at least 5% (`scripts/bench_diff.py`). See `scripts/bench-compare.sh --help` for all options.

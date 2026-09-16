# Preview the resolved config for a process

- Authors: Ben Sherman
- Status: draft
- Date: 2026-09-16

## Summary

A process directive can be set in several places across a config tree, and there is no way to see which one wins. This ADR decides where that resolution happens, what the language server sends to the editor, and how config profiles enter the picture.

## Problem Statement

A pipeline sets `cpus` in the `process` scope, again under `withLabel: process_high`, again under `withName: ALIGN`, and again inside two profiles, spread across `nextflow.config` and three included files. Nextflow resolves all of that at runtime and the user never sees it. One possible solution the config equivalent of a browser's CSS inspector: every setting that applies to a process, in precedence order, with the winner marked.

Three constraints shape the design.

The language server has no config evaluator. `nf-lang` parses config files into an AST and stops there. The Nextflow runtime's `ConfigBuilder` is a build-time dependency used to extract language definitions, not something the server can call. Nothing can compute the *value* of `params.cpus ?: 4`.

The two AST caches are separate. `ScriptService` owns the script AST, `ConfigService` owns the config AST, and they are independent entries in the per-workspace maps in `NextflowLanguageServer`.

Precedence is not a property of a single config file. It comes from `ProcessConfigBuilder` in the Nextflow runtime: label selectors, then name selectors, then plain process-scope settings as defaults, with profiles merged into the base config before any of that runs.

## Goals

- Show every setting that applies to a process, not only the winner. The overridden entries are the point of the feature.
- Keep the precedence rules in one place, so they track `ProcessConfigBuilder` as it changes.
- Let the user see the effect of a profile without editing files or restarting anything.
- Reuse the existing CodeLens and `workspace/executeCommand` path rather than adding a new transport.

## Non-goals

- Evaluating config expressions. Closures, `params` references and environment lookups are shown as source text.
- Config sources outside the workspace: `$HOME/.nextflow/config`, `-c` files, `-params-file`.
- Ranking the three rounds of `withName` matching. Nextflow matches the process name, then the include alias, then the qualified name, each round overriding the last. The preview treats every `withName` selector as one layer, so two selectors that match the same process under different names are ordered by declaration rather than by strength.
- Merge semantics for `ext` maps. Directives that accumulate rather than overwrite (`label`, `module`, `pod`, `publishDir`) are shown as layers without a winner, but nothing models the resulting combined value.

## Considered Options

### Where the CodeLens is attached

The process definition, or each process invocation inside a workflow.

- Good, because the invocation is the only place a fully qualified name such as `WF:SUB:ALIGN` exists, and `withName:` selectors match against it.
- Bad, because a lens renders on its own line above its range, so every call in a workflow body gains a blank line above it and a ten-call workflow doubles in height.
- Bad, because a process called from three workflows yields three different answers, which needs the call-hierarchy machinery to compute.
- Good, because the definition site covers the common case with the base process name and no extra machinery.

The definition wins for the first version, and the invocation site follows as a code action rather than a second lens.

### How an invocation is reached

Once the panel exists, the user still has to name the invocation they care about. A text box for the qualified name, a dropdown of every call path the server can find, or a code action on the call itself.

- Bad, because a text box asks the user to type an answer the server already knows, and a typo yields an empty cascade with no indication that anything went wrong.
- Bad, because a dropdown lists every call path in the workspace, which on a widely reused nf-core module runs to dozens of entries that have nothing to do with the file being edited.
- Good, because a code action starts from the call the cursor is already on, so the list is at most the handful of paths that reach that one call.
- Bad, because a code action is only found by pressing `Ctrl+.`, where a lens is visible without being asked for.

The code action wins. It costs no vertical space, and starting from a call site rather than a process narrows the answer without any UI for choosing.

### Where profile selection is resolved

The server could return every entry tagged by profile and let the extension filter, or the extension could re-invoke the command whenever the selection changes.

- Bad, because filtering client-side does not work: a profile is merged into the base config *before* selector precedence runs, so a `withName:` inside a profile can outrank a base-config `withLabel:` while still losing to a later base-config `withName:`. The winner cannot be recovered by filtering a precomputed list.
- Bad, because making it work client-side means a second copy of the precedence rules in TypeScript, drifting from `ProcessConfigBuilder` on the next Nextflow release.
- Good, because re-invoking is cheap: the AST is already cached, so the server walks and sorts with no recompile.
- Good, because the rules stay in Java, next to the AST they read.

### What the command returns

The DAG preview returns a rendered Mermaid string. The config preview could return rendered markup or structured data.

- Bad, because rendered markup fixes the presentation in the server, and the toggle UI needs to re-render.
- Good, because structured data lets the extension own layout, and lets a non-VS Code client present the cascade differently.
- Bad, because it is a second response shape to maintain alongside the DAG preview's string.

## Solution

The language server resolves the cascade, including profile merging, and returns it as structured data. The editor renders it and re-invokes the command whenever the user toggles a profile.

## Rationale & discussion

The server receives the config AST cache from the config service the same way it already receives the plugin spec cache, walks the config tree from the workspace root following `includeConfig`, and flattens it into entries carrying a key, the source text of the value, the file and line that declared it, and the profile it came from. It then applies `ProcessConfigBuilder`'s precedence for the requested process and marks the winner. The response lists every entry, winners and losers, plus the set of profiles declared anywhere in the tree so the editor can draw the toggles without a second call.

Values are source text, not values. This follows from having no evaluator, but it is also the more honest answer. A resolved value for `{ task.attempt * 2 }` would be a fiction, while the expression alongside the file and line that wrote it is what the user needs to go fix it.

Profile selection travels as a command argument and the editor re-invokes on every toggle. This is the decision that keeps precedence in one language. The cost is a round trip per click on cached data, which is not a cost worth optimizing.

The command takes an ordered list of profiles because profile order decides the winner, the same way `-profile docker,test` lets `test` win. The first version applies them in declaration order and says so in the UI; ordered selection is a later refinement that does not change this design.

A single call site can carry more than one qualified name, because the workflow containing it may itself be invoked from more than one place. The server walks the call graph from each entry workflow and returns every chain that reaches the call, and the editor offers one code action per chain. Names come from the call site rather than the definition, since an include alias replaces the process name in the chain.

Returning structured data rather than rendered markup diverges from the DAG preview. The divergence is forced by the toggle UI, and it puts the presentation where presentation belongs. The DAG preview stays as it is.

## Links

- [Preview resolved config](https://github.com/nextflow-io/language-server/issues/13)

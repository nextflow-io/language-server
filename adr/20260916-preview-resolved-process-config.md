# Preview the resolved config for a process

- Authors: Ben Sherman
- Status: accepted
- Date: 2026-09-16

## Summary

Provide a way to preview the resolved config of a process definition or process call.

## Problem statement

A pipeline sets `cpus` in the `process` scope, again under `withLabel: process_high`, again under `withName: ALIGN`, and again inside two profiles, spread across `nextflow.config` and three included files. Nextflow resolves all of that at runtime and the user never sees it.

It should be possible to visualize this config resolution in the editor, similar to a browser's CSS inspector: every setting that applies to a process, in precedence order, with the winner marked.

## Goals

- Show every setting that applies to a process, not only the winner.
- Let the user see the effect of a profile without editing files.

## Non-goals

- Evaluating config expressions. Closures, `params` references, and other expressions are shown as source text.
- Config sources outside the workspace: `$HOME/.nextflow/config`, `-c` files, `-params-file`.
- Merge semantics for directives that accumulate rather than overwrite (`label`, `module`, `pod`, `publishDir`), and for `ext` maps. These are shown as layers without a winner, but not how they combine.

## Solution

The language server resolves the cascade, including profile merging, and returns it as structured data. The extension renders it and re-invokes the command whenever the user adds or removes a profile.

## Core capabilities

### Preview entrypoint

The config preview can be triggered for a process definition through a "Preview config" CodeLens (similar to "Preview DAG" for a workflow). It can also be triggered for a process call in a workflow body through a code action.

The former resolves against the process name alone, while the latter also resolves the include alias and the fully qualified name, so it can apply selectors that the definition preview cannot.

Since a workflow can also be called multiple times in a pipeline, the language server resolves every fully-qualified path to the selected process call and provides each path as a separate code action.

### Language server response

The language server returns the cascade as structured data rather than rendered markup, so that the presentation belongs to the client, and an editor other than VS Code can present it differently.

The server walks the config tree from the workspace root following `includeConfig`, and flattens it into entries carrying a key, the source text of the value, the file and line that declared it, and the profile it came from (if applicable). It then applies the precedence rules for the requested process and marks the winner. The response lists every entry, winners and losers, plus the set of profiles declared anywhere in the tree, so that the extension can offer the profile selection.

Config values are rendered as source text, not evaluated, since there is no runtime context in which to evaluate them.

When the preview cannot be resolved, because the script or a config file has errors, the response carries an `error` message instead of a `result`.

### Profiles

Profile selection travels as a command argument and the extension re-invokes the command on every change. The winner cannot be recovered by filtering a precomputed list, and resolving it in the extension would mean a second copy of the precedence rules drifting from the runtime. The cost is a round trip per change on cached data, which is not worth optimizing.

The command takes an ordered list of profiles, because profile order decides the winner the same way `-profile docker,test` lets `test` win.

### Config selectors

A `withName` selector is ranked by the name it matched rather than by where it was declared. The config docs give six priority levels, and the last three are `withName` against the process name, then the include alias, then the qualified name. A selector can match in more than one of those rounds, and the strongest match is the one that decides the winner, so the rank cannot be assigned while the config tree is being collected. It is assigned per request, once the name of the invocation is known.

## Example

Previewing `ALIGN` as invoked at `SUB:ALIGN_DNA` with the `hpc` profile active:

```json
{
  "result": {
    "process": "SUB:ALIGN_DNA",
    "labels": ["big"],
    "profiles": ["hpc", "quoted-name"],
    "activeProfiles": ["hpc"],
    "directives": [
      {
        "name": "cpus",
        "layers": [
          {
            "value": "64",
            "source": "withName:SUB:ALIGN_DNA",
            "profile": "hpc",
            "file": "nextflow.config",
            "uri": "file:///path/to/pipeline/nextflow.config",
            "line": 60,
            "active": true
          },
          {
            "value": "8",
            "source": "withLabel:big",
            "file": "nextflow.config",
            "uri": "file:///path/to/pipeline/nextflow.config",
            "line": 8,
            "active": false
          },
          {
            "value": "2",
            "source": "process body",
            "file": "modules/align.nf",
            "uri": "file:///path/to/pipeline/modules/align.nf",
            "line": 3,
            "active": false
          }
        ]
      }
    ]
  }
}
```

The layers of a directive are ordered from strongest to weakest, and `active` marks the winner. A directive that accumulates rather than overwrites has no losing layer, so every one of its layers is active, which is how the client knows not to cross any of them out. `profile` is absent for a setting that came from the base config. `file` is relative to the root config and is meant for display, while `uri` and `line` are what the client navigates to.

## Links

- Community issue: [#13](https://github.com/nextflow-io/language-server/issues/13)
- Nextflow docs: [selector priority](https://docs.seqera.io/nextflow/config#selector-priority)

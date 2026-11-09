# API index

The API index lists each name of the Nextflow language and config files, with its docs link, signature, summary, and type. Docs tools use it to link the names in code examples to the Nextflow docs, for example [starlight-codeblocks](https://github.com/ewels/starlight-codeblocks). Each release has it as the asset `api-index.json`. The language server itself does not use it.

## Format

```json
{
  "apiIndex": 1,
  "source": "Nextflow reference",
  "version": "26.10.1",
  "base": "https://docs.seqera.io/nextflow/",
  "symbols": [
    {
      "name": "Path.baseName",
      "kind": "property",
      "href": "reference/stdlib-types/path",
      "signature": "Path.baseName: String",
      "summary": "Gets the file name without its extension, e.g. `/some/path/file.tar.gz` -> `file.tar`.",
      "type": "String"
    }
  ]
}
```

| Field | Description |
|---|---|
| `apiIndex` | The version of the format: `1`. |
| `source` | The name of the docs, for the reader to show with a link. |
| `version` | The language server version. |
| `base` | The URL that each `href` is relative to. |
| `symbols[].name` | The name. A member has its namespace, type, or scope before it, with a dot: `log.info`, `Path.baseName`, `docker.enabled`. |
| `symbols[].kind` | What the name is, such as `function`, `property`, `operator`, `process directive`, or `config setting`. |
| `symbols[].context` | Where the name applies (see below). Script names, such as global functions and type members, have none. |
| `symbols[].href` | The docs link, relative to `base`. |
| `symbols[].signature` | Optional. The signature, as a hover shows it. |
| `symbols[].summary` | Optional. The first sentence of the description, in Markdown. |
| `symbols[].type` | Optional. The type of the value: the return type of a function, or the type of a property. A reader follows it to resolve members, so that `file('a.txt').baseName` resolves to `Path.baseName`. Type arguments, such as `<E>` in `Channel<E>`, are for display. |
| `symbols[].extends` | Optional, for a type. The types whose members the type also has, such as `Iterable` for `List`. |

A name can be in the index more than once, in different contexts or with different kinds. For example, `file` is a global function and an output function of a typed process, and `process` is a keyword and a config scope.

### Contexts

| Context | Names |
|---|---|
| none | Global functions and constants, namespaces and their members, types and their members, feature flags, and keywords |
| `process` | Process directives, `task`, `template`, and the sections of a process |
| `process.typed` | The sections of a typed process that differ from a legacy process: `input`, `output`, `stage`, `topic` |
| `process.input`, `process.output` | The qualifiers and output options of a legacy process |
| `process.typed.stage`, `process.typed.output` | The stage directives and output functions of a typed process |
| `workflow` | The sections of a workflow |
| `config` | Config scopes and settings |

## Sources

`ApiIndexWriter` (in `src/spec`) builds the index from nf-lang and the Nextflow runtime, the same classes that hover hints and `definitions.json` come from:

- Names, signatures, types, and descriptions: `ScriptDsl`, the namespaces, the standard types, `ProcessDsl`, `TaskConfig`, `FeatureFlagDsl`, and the config spec.
- Docs links: the docs path of each kind of name, such as `reference/process/directives/<name>` for a directive. A config scope or setting uses the `[Read more]` link of its description, if it has one.

`ApiIndexWriter` also holds what nf-lang does not define: the operators of legacy channels (nf-lang defines only those of typed channels), the keywords, the section labels, and the output options of a legacy process.

## Build and check

```sh
./gradlew buildApiIndex
scripts/check-api-index.py build/generated/api-index.json build/api-index.json
```

The check fetches each page that the index links to from the live docs, and writes the checked index:

- A symbol whose anchor is not on its page links to the page instead.
- A symbol whose page is not found is dropped.

Each fix is a warning. It usually means that the docs do not describe the name yet, or that a `[Read more]` link in nf-lang is stale. The check fails if a page cannot be fetched for another reason, if a name is in the same context twice with the same kind, or if more than 10% of the symbols need a fix, which means that the docs moved and `ApiIndexWriter` needs a change.

The [Bundle](../.github/workflows/bundle.yml) workflow runs both on each push and pull request, and attaches the checked index to the release.

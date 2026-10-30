# Configuration

The language server reads its settings from the `nextflow` section of the `workspace/didChangeConfiguration` notification. All settings are optional. See [Editor support](editors.md) for how to pass settings in each editor.

The settings object must be nested by key segment, as most clients do when they send a configuration section:

```json
{
  "nextflow": {
    "errorReportingMode": "warnings",
    "formatting": {
      "harshilAlignment": true
    }
  }
}
```

Flat keys such as `"nextflow.formatting.harshilAlignment"` are not recognized. The language server doesn't read `initializationOptions` or request `workspace/configuration`.

Until the first notification arrives, the language server uses the defaults below. Each notification updates only the settings it contains; an omitted setting keeps its current value. An invalid value is ignored with a warning in the language server log, and the other settings in the notification are still applied. Changes take effect immediately.

## Files

| Setting | Type | Default | Description |
|---|---|---|---|
| `nextflow.files.exclude` | string array | `[".git", ".lineage", ".nf-test", ".pixi", ".venv", "work"]` | Files and folders to skip when scanning the workspace. A pattern matches a path that equals it or ends with `/<pattern>` (e.g. `work` or `modules/local`); globs are not supported. Excluded files are still handled when opened in the editor. |

## Diagnostics

| Setting | Type | Default | Description |
|---|---|---|---|
| `nextflow.errorReportingMode` | string | `"warnings"` | Level of error reporting (case-insensitive): `off` (no diagnostics), `errors` (errors only), `warnings` (errors and warnings), or `paranoid` (also report best-practice warnings). |

## Completion

| Setting | Type | Default | Description |
|---|---|---|---|
| `nextflow.completion.extended` | boolean | `false` | Suggest functions, processes, and workflows from other scripts in the workspace. Selecting one adds the corresponding `include` to the current script. |
| `nextflow.completion.maxItems` | integer | `100` | Maximum number of completions to suggest at a time. |

## Formatting

| Setting | Type | Default | Description |
|---|---|---|---|
| `nextflow.formatting.harshilAlignment` | boolean | `false` | Use the [Harshil Alignment](https://nf-co.re/docs/contributing/code_editors_and_styling/harshil_alignment) when formatting scripts and config files. Not all rules are supported. |
| `nextflow.formatting.maheshForm` | boolean | `false` | Place process outputs at the end of the process body when formatting scripts. |
| `nextflow.formatting.sortDeclarations` | boolean | `false` | Sort script declarations when formatting scripts. |

## DAG preview

These settings apply to the `nextflow.server.previewDag` command, which returns a Mermaid diagram of a workflow.

| Setting | Type | Default | Description |
|---|---|---|---|
| `nextflow.dag.direction` | string | `"TB"` | Direction of the diagram: `TB` or `TD` (top to bottom) or `LR` (left to right). Any other value is treated as `TB`. |
| `nextflow.dag.verbose` | boolean | `false` | Show intermediate nodes (e.g. variables assigned in the workflow) that are hidden by default. |

## Plugins

| Setting | Type | Default | Description |
|---|---|---|---|
| `nextflow.pluginRegistryUrl` | string | `"https://registry.nextflow.io/api/"` | Plugin registry used to fetch the definitions of plugins used by the pipeline. Must be an HTTP or HTTPS URL. |

Plugins installed locally are read from the plugins directory before the registry, so that unpublished plugins can be checked too. The plugins directory is resolved like Nextflow: `$NXF_PLUGINS_DIR`, otherwise `$NXF_HOME/plugins`, otherwise `~/.nextflow/plugins`. A plugin with a version (`id 'nf-hello@1.0.0'`) uses the installed release if there is one. A plugin without a version uses the registry, or the latest installed release if the registry doesn't have it. Specs are cached for the session, so reinstalling the same version requires restarting the language server.

## Debugging

| Setting | Type | Default | Description |
|---|---|---|---|
| `nextflow.debug` | boolean | `false` | Enable debug logging, and show debug information (e.g. the AST node stack) in hover hints. |

## Client-only settings

Some clients define additional settings that the language server ignores. For example, the VS Code extension uses `nextflow.languageVersion` to choose which language server version to download, and `nextflow.java.home` to find the Java runtime.

# Editor support

The Nextflow language server can be used with any editor that supports the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/). The [VS Code extension](https://github.com/nextflow-io/vscode-language-nextflow) is the official client and supports every feature. This page describes how to use the language server with other editors.

| Editor | Support |
|---|---|
| [VS Code](#vs-code) | Official extension |
| [Zed](#zed) | Official extension |
| [Neovim](#neovim) | `nextflow_ls` in nvim-lspconfig, `nextflow-language-server` in Mason |
| [Emacs](#emacs) | `lsp-nextflow` client in lsp-mode, or manual setup with Eglot |
| [Helix](#helix) | Manual setup |
| [JetBrains IDEs](#jetbrains-ides) | Community plugin |
| [Other editors](#other-editors) | Manual setup |

Configurations for editors other than VS Code are maintained by their communities. If you find an issue with one of them, please report it to the project that maintains it.

## Installing the language server

Some editor integrations download the language server automatically (see below). Otherwise, download it from [GitHub releases](https://github.com/nextflow-io/language-server/releases):

- **Linux (x64, arm64) and macOS (arm64):** the standalone bundle for your platform, `nextflow-language-server-<version>-<os>-<arch>.tar.gz`. It includes its own Java runtime. Extract it and run `bin/nextflow-lsp`.
- **Other platforms:** `language-server-all.jar`, which requires Java 17 or later. Run it with `java -jar language-server-all.jar`.

Use the language server version that matches the version of Nextflow that you use, e.g. language server 26.10.x for Nextflow 26.10. The latest GitHub release is not necessarily the right one, since older versions also receive patch releases.

## Client requirements

The language server communicates over standard input/output.

**Workspace:** The language server indexes every Nextflow file in each workspace folder, so that features like go to definition and find references work across files. Make sure that your editor opens the pipeline directory as the workspace (or *root*), e.g. by using `nextflow.config` or `.git` as a root marker. Files outside of the workspace are handled individually.

**File types:** The language server handles files by their extension rather than by language ID: `.nf` files are treated as scripts, and `.config` files (except `nf-test.config`) are treated as config files. A single `nextflow` file type for `*.nf` and `*.config` files (or just `nextflow.config`) is sufficient.

**Syntax highlighting:** The language server provides semantic tokens, but your editor still needs a Nextflow grammar for basic syntax highlighting. See the editor sections below.

**Settings:** The language server reads settings from `workspace/didChangeConfiguration`, under the `nextflow` key. All settings are optional. See the [VS Code extension](https://github.com/nextflow-io/vscode-language-nextflow/blob/master/package.json) for the available settings and their defaults. Some of them (e.g. `nextflow.languageVersion`, `nextflow.java.home`) are used only by the extension, and are ignored by the language server.

> [!NOTE]
> Older versions of the language server require the editor to send a value for `nextflow.files.exclude` and to open a workspace folder, otherwise the language server does nothing.

**VS Code-only features:** The DAG and config previews are rendered by the VS Code extension. In other editors, the "Preview DAG" and "Preview config" code lenses do nothing, and can be ignored or disabled.

## VS Code

Install the [Nextflow extension](https://marketplace.visualstudio.com/items?itemName=nextflow.nextflow) from the VS Code marketplace (or the [Open VSX registry](https://open-vsx.org/extension/nextflow/nextflow) for other VS Code-based editors). It downloads the language server automatically.

## Zed

The [Nextflow extension for Zed](https://github.com/nextflow-io/zed-nextflow) provides syntax highlighting (with [tree-sitter-nextflow](https://github.com/nextflow-io/tree-sitter-nextflow)) and downloads the language server automatically. See the extension README for installation and settings.

## Neovim

The [nvim-lspconfig](https://github.com/neovim/nvim-lspconfig) plugin provides the `nextflow_ls` configuration, and [Mason](https://github.com/mason-org/mason.nvim) can install the language server (with `:MasonInstall nextflow-language-server`):

```lua
vim.lsp.enable('nextflow_ls')
```

To use the standalone bundle instead, override the command:

```lua
vim.lsp.config('nextflow_ls', {
  cmd = { '/path/to/nextflow-language-server-<version>-<os>-<arch>/bin/nextflow-lsp' },
})
vim.lsp.enable('nextflow_ls')
```

Neovim doesn't recognize the `nextflow` file type by default. Install [vim-language-nextflow](https://github.com/nextflow-io/vim-language-nextflow), which provides file type detection and syntax highlighting, or add the file type yourself:

```lua
vim.filetype.add({
  extension = { nf = 'nextflow' },
  filename = { ['nextflow.config'] = 'nextflow' },
})
```

[AstroNvim](https://astronvim.com/) users can use the [Nextflow community pack](https://github.com/AstroNvim/astrocommunity/tree/main/lua/astrocommunity/pack/nextflow).

## Emacs

Syntax highlighting is provided by [nextflow-mode](https://github.com/edmundmiller/nextflow-mode), which also provides a tree-sitter mode (`nextflow-ts-mode`).

**lsp-mode:** [lsp-mode](https://github.com/emacs-lsp/lsp-mode) includes a Nextflow client (`lsp-nextflow`) for `nextflow-mode`, which runs the language server jar with Java. If the automatic download doesn't work, download `language-server-all.jar` yourself and point `lsp-nextflow-server-file` to it:

```elisp
(setq lsp-nextflow-server-file (expand-file-name "~/path/to/language-server-all.jar"))
```

**Eglot:** Add the language server to `eglot-server-programs`:

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(((nextflow-mode :language-id "nextflow")
                  (nextflow-ts-mode :language-id "nextflow"))
                 . ("/path/to/nextflow-language-server-<version>-<os>-<arch>/bin/nextflow-lsp"))))
```

Replace the command with `("java" "-jar" "/path/to/language-server-all.jar")` to use the jar. Settings can be passed with `eglot-workspace-configuration`, e.g. in `.dir-locals.el`:

```elisp
((nil . ((eglot-workspace-configuration
          . (:nextflow (:formatting (:harshilAlignment t)))))))
```

## Helix

Add the language and the language server to your `languages.toml`:

```toml
[[language]]
name = "nextflow"
scope = "source.nextflow"
file-types = ["nf", "nf.test", { glob = "nextflow.config" }]
roots = ["nextflow.config"]
comment-tokens = ["//"]
block-comment-tokens = { start = "/*", end = "*/" }
indent = { tab-width = 4, unit = "    " }
language-servers = ["nextflow-language-server"]
grammar = "groovy"

[language-server.nextflow-language-server]
command = "/path/to/nextflow-language-server-<version>-<os>-<arch>/bin/nextflow-lsp"

# optional settings
[language-server.nextflow-language-server.config.nextflow.formatting]
harshilAlignment = true
```

Helix doesn't have a Nextflow grammar yet ([helix-editor/helix#14484](https://github.com/helix-editor/helix/issues/14484)). The configuration above uses the Groovy grammar, which also needs the Groovy highlight queries for the `nextflow` language, e.g. by copying `runtime/queries/groovy` to `runtime/queries/nextflow` in your Helix configuration directory.

## JetBrains IDEs

The community [Nextflow Language](https://plugins.jetbrains.com/plugin/31666) plugin uses the language server in IntelliJ IDEA and other JetBrains IDEs. See [#14](https://github.com/nextflow-io/language-server/issues/14) for the status of official support.

## Other editors

For other editors with an LSP client (e.g. Sublime Text with [LSP](https://github.com/sublimelsp/LSP), Kate), configure the client to:

1. Start the language server with `bin/nextflow-lsp` (standalone bundle) or `java -jar language-server-all.jar`.
2. Attach it to `*.nf` and `*.config` files, using the pipeline directory (containing `nextflow.config`) as the workspace root.
3. Optionally, send [settings](#client-requirements) under the `nextflow` key.

## Troubleshooting

If the language server starts but doesn't report any diagnostics:

- If you use an older version of the language server, see the note in [Client requirements](#client-requirements).
- Check that the file is not excluded by `nextflow.files.exclude`.
- Set `nextflow.debug` to `true` and check the language server logs in your editor, e.g. `:LspLog` in Neovim, the `*EGLOT events*` buffer in Emacs, or `~/.cache/helix/helix.log` in Helix.

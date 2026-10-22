# Nextflow Language Server

The official [language server](https://microsoft.github.io/language-server-protocol/) for [Nextflow](https://nextflow.io/) scripts and config files.

The following language features are currently supported:

- code navigation (outline, go to definition, find references)
- completion
- diagnostics (errors, warnings)
- formatting
- hover hints
- rename
- semantic highlighting
- DAG preview for workflows
- config preview for processes

## Installation

The [Nextflow VS Code extension](https://github.com/nextflow-io/vscode-language-nextflow) downloads and runs the language server automatically.

For other editors, download the language server from [GitHub releases](https://github.com/nextflow-io/language-server/releases):

- Linux (x64, arm64) and macOS (arm64): download the standalone bundle for your platform, which includes its own Java runtime, and run `bin/nextflow-lsp`.
- Other platforms: download `language-server-all.jar` and run it with Java 17 or later: `java -jar language-server-all.jar`.

The language server communicates over standard input/output. See [Editor support](docs/editors.md) for how to set it up with other editors, including Zed, Neovim, and Emacs.

## Configuration

The language server exposes a set of configuration settings that can be controlled through the `workspace/didChangeConfiguration` event. See the [Nextflow VS Code extension](https://github.com/nextflow-io/vscode-language-nextflow/blob/master/package.json) for an example of how to set up these configuration settings with sensible defaults.

## Development

```sh
# clone Nextflow next to the language server (for the nf-lang module)
git clone https://github.com/nextflow-io/nextflow ../nextflow

make                            # build the language server jar
make test                       # run unit tests
make bench                      # run the performance benchmark
make bundle                     # build the standalone bundle for the current platform
VERSION=<version> make install  # install a local build for the VS Code extension
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for more details on building, testing and releasing the language server.

## Troubleshooting

Sometimes the language server might crash or get out of sync with your workspace. If this happens, you can restart the server from the command palette. You can also view the server logs from the "Output" tab under "Nextflow Language Server".

When reporting an issue, please include a minimal code snippet that triggers the issue as well as any error traces from the server logs.

## Credits

Based on [GroovyLanguageServer/groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server).

# Nextflow Language Server

A [language server](https://microsoft.github.io/language-server-protocol/) for [Nextflow](https://nextflow.io/) scripts and config files.

The following language features are currently supported:

- code navigation (outline, go to definition, find references)
- completion
- diagnostics (errors, warnings)
- formatting
- hover hints
- rename
- semantic highlighting
- DAG preview for workflows

## Requirements

The language server requires Java 17 or later.

## Configuration

The language server exposes a set of configuration settings that can be controlled through the `workspace/didChangeConfiguration` event. See the [Nextflow VS Code extension](https://github.com/nextflow-io/vscode-language-nextflow/blob/master/package.json) for an example of how to set up these configuration settings with sensible defaults.

## Development

To build from the command line:

```sh
make
```

To run unit tests:

```sh
make test
```

To run the language server:

```sh
java -jar build/libs/language-server-all.jar
```

Protocol messages are exchanged using standard input/output.

## Releasing

The Nextflow language server follows the versioning scheme of Nextflow. For each stable release of Nextflow, there is a corresponding stable release of the language server. There is no correlation between patch releases of Nextflow and the language server -- they are patched independently of each other.

A separate branch is maintained for each stable release, starting with `STABLE-24.10.x`. The `main` branch corresponds to the upcoming stable release. Updates to `main` should be backported as needed to maintain a consistent user experience, for example, when a new configuration option is added.

To make a new release of the language server:

1. Build the language server locally.
2. Create a new GitHub release with the language server JAR and a list of notable changes.

## Troubleshooting

Sometimes the language server might crash or get out of sync with your workspace. If this happens, you can restart the server from the command palette. You can also view the server logs from the "Output" tab under "Nextflow Language Server".

When reporting an issue, please include a minimal code snippet that triggers the issue as well as any error traces from the server logs.

## Credits

Based on [GroovyLanguageServer/groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server).

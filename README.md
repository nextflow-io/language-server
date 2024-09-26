# Nextflow Language Server

A [language server](https://microsoft.github.io/language-server-protocol/) for [Nextflow](https://nextflow.io/) scripts and config files.

The following language features are currently supported:

- code navigation (outline, go to definition, find references)
- completion
- diagnostics (errors, warnings)
- formatting
- hover hints
- rename

## Requirements

The language server requires Java 17 or later.

## Development

To build from the command line:

```sh
./gradlew build
```

To run unit tests:

```sh
./gradlew test
```

To run the language server:

```sh
java -jar build/libs/language-server-all.jar
```

Protocol messages are exchanged using standard input/output.

## Troubleshooting

Sometimes the language server might crash or get out of sync with your workspace. If this happens, you can restart the server from the command palette. You can also view the server logs from the "Output" tab under "Nextflow Language Server".

When reporting an issue, please include a minimal code snippet that triggers the issue as well as any error traces from the server logs.

## Credits

Based on [GroovyLanguageServer/groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server).

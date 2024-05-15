# Nextflow Language Server

A [language server](https://microsoft.github.io/language-server-protocol/) for [Nextflow](https://nextflow.io/). Based on [GroovyLanguageServer/groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server).

The following LSP requests are currently supported:

- completion
- document symbols
- hover

## Build

To build from the command line, run the following command:

```sh
./gradlew build
```

## Run

To run the language server, use the following command:

```sh
java -jar build/libs/language-server-all.jar
```

Protocol messages are exchanged using standard input/output.

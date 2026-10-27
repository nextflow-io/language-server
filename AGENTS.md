# AGENTS.md

The Nextflow language server: an LSP server for Nextflow scripts and config files, written in Java on lsp4j. Parsing and analysis come from the nf-lang module of Nextflow, built from a checkout in `../nextflow`. Building, releasing, and the `make` targets are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Docs

- [`GLOSSARY.md`](GLOSSARY.md): the shared vocabulary. Read it before naming a concept or writing docs, and use its terms in code, docs, and commit messages.
- [`docs/adr/`](docs/adr): design decisions, named `YYYYMMDD-name.md` after the template. Write an ADR for a decision that changes the architecture, threading, dependencies, or distribution, and settle it with the maintainer before implementing.
- [`docs/configuration.md`](docs/configuration.md): every client setting. Update it when a setting is added or changed.
- [`docs/internals.md`](docs/internals.md): read before changing `engine/`, threading, builds, or how a provider gets its view. Its invariants are the rules the engine relies on.
- [`docs/testing.md`](docs/testing.md): read before writing or changing a test.

Docs describe the current state. Update `docs/`, `GLOSSARY.md`, and `README.md` in the same change as the behavior they describe.

#!/usr/bin/env python3
"""
AOT training workload for the Nextflow language server.

Indexes a workspace, then opens, edits and queries every script and config
file, and shuts the server down cleanly so that the JVM can write the AOT
cache (e.g. when launched with -XX:AOTCacheOutput=FILE).

Edits are compiled immediately by following each change with a completion
request (which calls updateNow()), instead of waiting for the 1s debounce.

Usage:
  scripts/aot-train.py --workspace DIR --server-cmd CMD [--passes 2]
"""

import argparse
import re
import shlex
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ in scripts/
import bench  # noqa: E402

EXCLUDE = {'.git', '.nf-test', 'work'}

EDITS = [
    ('insert', '// train edit\n'),
    ('remove', None),
    # syntax error, to exercise error recovery and reporting
    ('insert', 'x = (\n'),
    ('remove', None),
]

PATTERNS = {
    '.nf': (r'include \{\s*(\w+)', r'\b([A-Z][A-Z0-9_]{3,})\s*\(', r'\b(ch_\w+)\.', r'\b(params\.\w+)', r'\b(meta\.\w+)', r'\b(task\.\w+)'),
    '.config': (r'\b(params\.\w+)', r'\b(withName:\s*\S+)', r'\b(process)\s*\{', r'\b(ext\.\w+)', r'\b(task\.\w+)'),
}

DOT_PATTERNS = {
    '.nf': r'\b(?:ch_\w+|params|meta)\.',
    '.config': r'\b(?:params|process|task)\.',
}


def position(text, index):
    line = text.count('\n', 0, index)
    start = text.rfind('\n', 0, index) + 1
    return {'line': line, 'character': len(text[start:index].encode('utf-16-le')) // 2}


class Trainer:

    def __init__(self, client, timeout):
        self.client = client
        self.timeout = timeout
        self.version = 1
        self.failed = 0

    def request(self, method, params):
        try:
            return self.client.request(method, params, timeout=self.timeout)
        except bench.BenchError:
            # some positions resolve nothing; only the server's work matters here
            self.failed += 1
            return None

    def flush(self, path, notify):
        """Send a notification and compile it immediately."""
        uri = path.as_uri()
        count = self.client.diagnostics_count(uri)
        notify()
        self.request('textDocument/completion', {'textDocument': {'uri': uri}, 'position': {'line': 0, 'character': 0}})
        self.client.wait_diagnostics(uri, count, self.timeout)

    def exercise(self, path, debounce=False):
        text = path.read_text(encoding='utf-8')
        uri = path.as_uri()
        doc = {'uri': uri}

        open_params = {'textDocument': {'uri': uri, 'languageId': 'nextflow', 'version': self.version, 'text': text}}
        if debounce:
            count = self.client.diagnostics_count(uri)
            self.client.notify('textDocument/didOpen', open_params)
            self.client.wait_diagnostics(uri, count, self.timeout)
        else:
            self.flush(path, lambda: self.client.notify('textDocument/didOpen', open_params))

        for kind, insert in EDITS:
            self.version += 1
            if kind == 'insert':
                change = {'range': {'start': {'line': 0, 'character': 0}, 'end': {'line': 0, 'character': 0}}, 'text': insert}
            else:
                change = {'range': {'start': {'line': 0, 'character': 0}, 'end': {'line': 1, 'character': 0}}, 'text': ''}
            params = {'textDocument': {'uri': uri, 'version': self.version}, 'contentChanges': [change]}
            self.flush(path, lambda: self.client.notify('textDocument/didChange', params))

        positions = []
        for pattern in PATTERNS[path.suffix]:
            for m in list(re.finditer(pattern, text))[:3]:
                positions.append(position(text, m.start(1) + 2))
        dot_positions = [position(text, m.end()) for m in list(re.finditer(DOT_PATTERNS[path.suffix], text))[:3]]

        self.request('textDocument/documentSymbol', {'textDocument': doc})
        self.request('textDocument/formatting', {'textDocument': doc, 'options': {'tabSize': 4, 'insertSpaces': True}})
        self.request('textDocument/semanticTokens/full', {'textDocument': doc})
        self.request('textDocument/documentLink', {'textDocument': doc})
        self.request('textDocument/codeLens', {'textDocument': doc})
        for p in positions:
            self.request('textDocument/hover', {'textDocument': doc, 'position': p})
            self.request('textDocument/definition', {'textDocument': doc, 'position': p})
            self.request('textDocument/references', {'textDocument': doc, 'position': p, 'context': {'includeDeclaration': True}})
            self.request('textDocument/rename', {'textDocument': doc, 'position': p, 'newName': 'RENAMED_X'})
            items = self.request('textDocument/prepareCallHierarchy', {'textDocument': doc, 'position': p})
            if items:
                self.request('callHierarchy/incomingCalls', {'item': items[0]})
                self.request('callHierarchy/outgoingCalls', {'item': items[0]})
            self.request('textDocument/completion', {'textDocument': doc, 'position': p})
        for p in dot_positions:
            self.request('textDocument/completion', {'textDocument': doc, 'position': p})

        # compiled by the next flush (or the debounce)
        self.client.notify('textDocument/didClose', {'textDocument': doc})
        self.version += 1


def workspace_files(workspace):
    # same files as ScriptService/ConfigService.matchesFile()
    files = [p for p in workspace.rglob('*') if p.suffix in PATTERNS and p.name != 'nf-test.config' and p.is_file()]
    return sorted(p for p in files if not EXCLUDE.intersection(p.relative_to(workspace).parts))


def main():
    parser = argparse.ArgumentParser(description='Nextflow language server AOT training workload')
    parser.add_argument('--workspace', required=True, help='workspace directory (e.g. nf-core/methylseq checkout)')
    parser.add_argument('--server-cmd', required=True, help='command to launch the server')
    parser.add_argument('--passes', type=int, default=2, help='passes over the workspace files')
    parser.add_argument('--timeout', type=int, default=300, help='timeout in seconds for any single wait')
    args = parser.parse_args()

    start = time.perf_counter()
    workspace = Path(args.workspace).resolve()
    files = workspace_files(workspace)
    if not files:
        parser.error('no .nf or .config files in %s' % workspace)

    client = None
    try:
        client, t_init, t_indexed = bench.start_server(shlex.split(args.server_cmd), workspace, args.timeout)
        print('initialize %.0f ms, indexed %.0f ms, %d files' % (t_init, t_indexed, len(files)), flush=True)
        trainer = Trainer(client, args.timeout)
        for i in range(args.passes):
            for j, path in enumerate(files):
                trainer.exercise(path, debounce=(j == 0))
            for query in ('FASTQ', 'ALIGN', 'main', ''):
                trainer.request('workspace/symbol', {'query': query})
            print('pass %d done at %.0f ms' % (i + 1, (time.perf_counter() - start) * 1000), flush=True)

        client.request('shutdown', None, timeout=60)
        client.notify('exit', None)
        # the JVM writes the AOT cache on exit
        rc = client.proc.wait(timeout=600)
    except bench.BenchError as e:
        print('FAILED: %s' % e, file=sys.stderr)
        if client is not None:
            print('server stderr:\n  ' + '\n  '.join(client.stderr[-20:]), file=sys.stderr)
            client.proc.kill()
        return 1

    print('server exited with %d after %.0f ms (%d requests without result)' % (rc, (time.perf_counter() - start) * 1000, trainer.failed))
    if client.stderr:
        print('server stderr:\n  ' + '\n  '.join(client.stderr[-20:]), file=sys.stderr)
    if client.log_errors:
        print('FAILED: server reported %d error(s), first: %s' % (len(client.log_errors), client.log_errors[0][:500]), file=sys.stderr)
        return 1
    # e.g. a ClassNotFoundException for a JDK module missing from the runtime
    exceptions = [line for line in client.stderr if re.search(r'(Exception|Error)\b', line)]
    if exceptions:
        print('FAILED: server stderr contains %d exception(s), first: %s' % (len(exceptions), exceptions[0]), file=sys.stderr)
        return 1
    return rc


if __name__ == '__main__':
    sys.exit(main())

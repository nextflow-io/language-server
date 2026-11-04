#!/usr/bin/env python3
"""
Performance benchmark for the Nextflow language server.

Drives the server over stdio through a scripted session against a real
pipeline (nf-core/rnaseq) and reports latency statistics for typical LSP
operations. Every response is validated, so the benchmark also serves as an
end-to-end integration test (non-zero exit on failure).

The session covers startup and workspace indexing, opening and editing a file,
and common requests (hover, completion, definition, references, rename, etc).
didOpen/didChange are measured up to the corresponding diagnostics, so they
include the server's 500 ms debounce of background builds. The peak RSS of the
server is also reported.

Usage:
  scripts/bench.py --workspace build/bench/rnaseq [--iterations 10] [--json out.json]
  scripts/bench.py --workspace build/bench/rnaseq --server-cmd ./nextflow-lsp

The JSON output contains per-scenario summaries (`results`) and raw samples in
ms (`samples`), which can be compared across builds with scripts/bench_diff.py.
"""

import argparse
import datetime
import json
import math
import os
import shlex
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

DEFAULT_SERVER_CMD = 'java -jar build/libs/language-server-all.jar'

# settings shape as read by NextflowLanguageServer.didChangeConfiguration();
SETTINGS = {
    'nextflow': {
        'debug': False,
        'errorReportingMode': 'warnings',
        'files': {'exclude': ['.git', '.nf-test', 'work']},
        'completion': {'extended': False, 'maxItems': 100},
        'formatting': {'harshilAlignment': False, 'maheshForm': False, 'sortDeclarations': False},
    }
}

CLIENT_CAPABILITIES = {
    'textDocument': {
        'synchronization': {'didSave': True},
        'hover': {'contentFormat': ['markdown', 'plaintext']},
        'completion': {'completionItem': {'snippetSupport': True}},
        'definition': {},
        'references': {},
        'documentSymbol': {'hierarchicalDocumentSymbolSupport': True},
        'formatting': {},
        'rename': {},
        'callHierarchy': {},
        'publishDiagnostics': {},
        'semanticTokens': {
            'requests': {'full': True},
            'tokenTypes': [],
            'tokenModifiers': [],
            'formats': ['relative'],
        },
    },
    'workspace': {'workspaceFolders': True, 'configuration': True, 'symbol': {}},
    'window': {'workDoneProgress': True},
}


class BenchError(Exception):
    pass


class LspClient:
    """Minimal LSP client over stdio with Content-Length framing."""

    def __init__(self, cmd, cwd):
        self.proc = subprocess.Popen(
            cmd, cwd=cwd,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.next_id = 1
        self.lock = threading.Condition()
        self.responses = {}
        self.diagnostics = {}       # uri -> number of publishDiagnostics received
        self.log_errors = []        # error messages from window/logMessage, window/showMessage
        self.stderr = []
        self.closed = False
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    # -- transport

    def _send(self, message):
        body = json.dumps(message).encode('utf-8')
        header = ('Content-Length: %d\r\n\r\n' % len(body)).encode('ascii')
        self.proc.stdin.write(header + body)
        self.proc.stdin.flush()

    def _read_message(self):
        stream = self.proc.stdout
        length = None
        while True:
            line = stream.readline()
            if not line:
                return None
            line = line.rstrip(b'\r\n')
            if not line:
                break
            name, _, value = line.decode('ascii').partition(':')
            if name.strip().lower() == 'content-length':
                length = int(value.strip())
        if length is None:
            raise BenchError('message without Content-Length header')
        body = stream.read(length)
        if len(body) < length:
            return None
        return json.loads(body.decode('utf-8'))

    def _read_stdout(self):
        try:
            while True:
                message = self._read_message()
                if message is None:
                    break
                self._dispatch(message)
        finally:
            with self.lock:
                self.closed = True
                self.lock.notify_all()

    def _read_stderr(self):
        for line in self.proc.stderr:
            self.stderr.append(line.decode('utf-8', 'replace').rstrip())

    def _dispatch(self, message):
        method = message.get('method')
        if method is None:
            with self.lock:
                self.responses[message.get('id')] = message
                self.lock.notify_all()
        elif 'id' in message:
            self._send({'jsonrpc': '2.0', 'id': message['id'], 'result': self._server_request(method, message.get('params'))})
        elif method == 'textDocument/publishDiagnostics':
            with self.lock:
                uri = message['params']['uri']
                self.diagnostics[uri] = self.diagnostics.get(uri, 0) + 1
                self.lock.notify_all()
        elif method in ('window/logMessage', 'window/showMessage'):
            if message['params'].get('type') == 1:
                self.log_errors.append(message['params'].get('message'))

    def _server_request(self, method, params):
        if method == 'workspace/configuration':
            return [SETTINGS.get(item.get('section'), SETTINGS) if item.get('section') else SETTINGS
                    for item in params.get('items', [])]
        # client/registerCapability, window/workDoneProgress/create, etc.
        return None

    # -- api

    def notify(self, method, params):
        self._send({'jsonrpc': '2.0', 'method': method, 'params': params})

    def request(self, method, params, timeout=60):
        with self.lock:
            id = self.next_id
            self.next_id += 1
        self._send({'jsonrpc': '2.0', 'id': id, 'method': method, 'params': params})
        deadline = time.monotonic() + timeout
        with self.lock:
            while id not in self.responses:
                remaining = deadline - time.monotonic()
                if self.closed:
                    raise BenchError('%s: server exited before responding' % method)
                if remaining <= 0:
                    raise BenchError('%s: no response after %ds' % (method, timeout))
                self.lock.wait(remaining)
            response = self.responses.pop(id)
        if 'error' in response:
            raise BenchError('%s: %s' % (method, response['error'].get('message')))
        return response.get('result')

    def diagnostics_count(self, uri):
        with self.lock:
            return self.diagnostics.get(uri, 0)

    def wait_diagnostics(self, uri, count, timeout=60):
        """Wait until more than `count` diagnostics have been published for `uri`."""
        deadline = time.monotonic() + timeout
        with self.lock:
            while self.diagnostics.get(uri, 0) <= count:
                remaining = deadline - time.monotonic()
                if self.closed:
                    raise BenchError('server exited while waiting for diagnostics: %s' % uri)
                if remaining <= 0:
                    raise BenchError('no diagnostics published after %ds: %s' % (timeout, uri))
                self.lock.wait(remaining)

    def peak_rss_kb(self):
        try:
            for line in Path('/proc/%d/status' % self.proc.pid).read_text().splitlines():
                if line.startswith('VmHWM:'):
                    return int(line.split()[1])
        except OSError:
            pass
        return None

    def shutdown(self):
        try:
            self.request('shutdown', None, timeout=10)
            self.notify('exit', None)
            self.proc.wait(timeout=10)
        except Exception:
            self.proc.kill()
            self.proc.wait()


# -- helpers

def position_of(text, needle, offset=0, occurrence=1):
    """Return the LSP position (UTF-16 columns) of `needle` in `text`, plus `offset` characters."""
    index = -1
    for _ in range(occurrence):
        index = text.find(needle, index + 1)
        if index < 0:
            raise BenchError('identifier not found in benchmark file: %r' % needle)
    index += offset
    line = text.count('\n', 0, index)
    line_start = text.rfind('\n', 0, index) + 1
    character = len(text[line_start:index].encode('utf-16-le')) // 2
    return {'line': line, 'character': character}


def check(condition, message):
    if not condition:
        raise BenchError(message)


def summarize(samples):
    ordered = sorted(samples)
    p95 = ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]
    return {
        'n': len(ordered),
        'min': ordered[0],
        'median': statistics.median(ordered),
        'p95': p95,
        'max': ordered[-1],
    }


def workspace_tag(workspace):
    """Return the git tag checked out in the workspace, if any."""
    try:
        out = subprocess.run(['git', '-C', workspace, 'tag', '--points-at', 'HEAD'],
                             capture_output=True, text=True, timeout=10).stdout.split()
    except (OSError, subprocess.SubprocessError):
        return None
    return out[0] if out else None


def timed(fn):
    start = time.perf_counter()
    result = fn()
    return (time.perf_counter() - start) * 1000, result


# -- scenarios

def start_server(cmd, workspace, timeout):
    """Launch the server and wait for the workspace to be indexed.

    The `nextflow.server.previewWorkspace` command is a project-wide request,
    which the server answers only once the workspace is indexed and every file
    is built, so its response is a deterministic "indexing done" signal.
    """
    root_uri = workspace.as_uri()
    start = time.perf_counter()
    client = LspClient(cmd, cwd=Path(__file__).resolve().parent.parent)
    client.request('initialize', {
        'processId': os.getpid(),
        'rootUri': root_uri,
        'workspaceFolders': [{'uri': root_uri, 'name': workspace.name}],
        'capabilities': CLIENT_CAPABILITIES,
    }, timeout=timeout)
    t_init = (time.perf_counter() - start) * 1000
    client.notify('initialized', {})
    client.notify('workspace/didChangeConfiguration', {'settings': SETTINGS})
    result = client.request('workspace/executeCommand', {
        'command': 'nextflow.server.previewWorkspace',
        'arguments': [workspace.name],
    }, timeout=timeout)
    t_indexed = (time.perf_counter() - start) * 1000
    check(result, 'previewWorkspace returned an empty result')
    check(len(client.diagnostics) > 0, 'no diagnostics published during workspace indexing')
    return client, t_init, t_indexed


def run(args):
    workspace = Path(args.workspace).resolve()
    script = workspace / args.file
    check(script.is_file(), 'benchmark file not found: %s' % script)
    text = script.read_text(encoding='utf-8')
    uri = script.as_uri()
    doc = {'uri': uri}
    # on Windows, a command line is passed as is (e.g. a backslash path to a .cmd launcher)
    cmd = args.server_cmd if os.name == 'nt' else shlex.split(args.server_cmd)
    n = args.iterations
    results = {}
    raw = {}

    def record(name, samples):
        results[name] = summarize(samples)
        raw[name] = [round(s, 3) for s in samples]
        print('  %-34s %s' % (name, ' '.join('%.0f' % s for s in samples)), file=sys.stderr)

    # cold start
    t_inits, t_indexeds = [], []
    client = None
    for i in range(args.cold_iterations):
        if client is not None:
            client.shutdown()
        client, t_init, t_indexed = start_server(cmd, workspace, args.timeout)
        t_inits.append(t_init)
        t_indexeds.append(t_indexed)
    record('startup: initialize', t_inits)
    record('startup: workspace indexed', t_indexeds)

    try:
        version = 1

        # didOpen -> publishDiagnostics
        samples = []
        for i in range(n):
            count = client.diagnostics_count(uri)
            start = time.perf_counter()
            client.notify('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'nextflow', 'version': version, 'text': text}})
            client.wait_diagnostics(uri, count, args.timeout)
            samples.append((time.perf_counter() - start) * 1000)
            if i < n - 1:
                count = client.diagnostics_count(uri)
                client.notify('textDocument/didClose', {'textDocument': doc})
                client.wait_diagnostics(uri, count, args.timeout)
        record('didOpen -> diagnostics', samples)

        # didChange -> publishDiagnostics (alternately insert and remove a comment line)
        def edit(insert):
            nonlocal version
            version += 1
            change = {'range': {'start': {'line': 0, 'character': 0}, 'end': {'line': 0, 'character': 0}}, 'text': '// bench edit\n'} if insert \
                else {'range': {'start': {'line': 0, 'character': 0}, 'end': {'line': 1, 'character': 0}}, 'text': ''}
            count = client.diagnostics_count(uri)
            start = time.perf_counter()
            client.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': version}, 'contentChanges': [change]})
            client.wait_diagnostics(uri, count, args.timeout)
            return (time.perf_counter() - start) * 1000

        samples = [edit(i % 2 == 0) for i in range(n)]
        if n % 2 == 1:
            edit(False)
        record('didChange -> diagnostics', samples)

        # requests
        call_pos = position_of(text, 'FASTQ_QC_TRIM_FILTER_SETSTRANDEDNESS (', offset=5)
        # references/rename currently resolve nothing at a call site, so use the include
        include_pos = position_of(text, '{ FASTQ_QC_TRIM_FILTER_SETSTRANDEDNESS', offset=7)
        complete_pos = position_of(text, 'ch_multiqc_files.mix(', offset=len('ch_'))
        target = 'subworkflows/nf-core/fastq_qc_trim_filter_setstrandedness/main.nf'

        def hover():
            r = client.request('textDocument/hover', {'textDocument': doc, 'position': call_pos})
            check(r and r.get('contents'), 'hover: empty result')

        def completion():
            r = client.request('textDocument/completion', {'textDocument': doc, 'position': complete_pos})
            items = r.get('items') if isinstance(r, dict) else r
            check(items, 'completion: no items')

        def definition():
            r = client.request('textDocument/definition', {'textDocument': doc, 'position': call_pos})
            check(r and any((l.get('uri') or l.get('targetUri', '')).endswith(target) for l in r),
                  'definition: did not resolve to %s' % target)

        def references():
            r = client.request('textDocument/references', {'textDocument': doc, 'position': include_pos, 'context': {'includeDeclaration': True}})
            check(r and len(r) >= 2, 'references: expected at least 2 results')

        def document_symbol():
            r = client.request('textDocument/documentSymbol', {'textDocument': doc})
            check(r, 'documentSymbol: empty result')

        def semantic_tokens():
            r = client.request('textDocument/semanticTokens/full', {'textDocument': doc})
            check(r and r.get('data'), 'semanticTokens/full: empty result')

        def formatting():
            r = client.request('textDocument/formatting', {'textDocument': doc, 'options': {'tabSize': 4, 'insertSpaces': True}})
            check(r is not None, 'formatting: null result')

        def rename():
            r = client.request('textDocument/rename', {'textDocument': doc, 'position': include_pos, 'newName': 'FASTQ_QC_RENAMED'})
            check(r and (r.get('changes') or r.get('documentChanges')), 'rename: empty workspace edit')

        def workspace_symbol():
            r = client.request('workspace/symbol', {'query': 'QUANTIFY'})
            check(r, 'workspace/symbol: empty result')

        def call_hierarchy():
            items = client.request('textDocument/prepareCallHierarchy', {'textDocument': doc, 'position': call_pos})
            check(items, 'prepareCallHierarchy: empty result')
            r = client.request('callHierarchy/incomingCalls', {'item': items[0]})
            check(r, 'callHierarchy/incomingCalls: empty result')

        requests = [
            ('hover', hover),
            ('completion', completion),
            ('definition', definition),
            ('references', references),
            ('documentSymbol', document_symbol),
            ('semanticTokens/full', semantic_tokens),
            ('formatting', formatting),
            ('rename', rename),
            ('workspace/symbol', workspace_symbol),
            ('callHierarchy (prepare+incoming)', call_hierarchy),
        ]
        for name, fn in requests:
            record(name, [timed(fn)[0] for _ in range(n)])

        peak_rss = client.peak_rss_kb()
        log_errors = list(client.log_errors)
    except BenchError:
        if client.stderr:
            print('\nserver stderr (last 20 lines):\n  ' + '\n  '.join(client.stderr[-20:]), file=sys.stderr)
        raise
    finally:
        client.shutdown()

    return results, raw, peak_rss, log_errors


def print_table(results, peak_rss):
    print()
    print('%-34s %5s %9s %9s %9s %9s' % ('scenario (ms)', 'n', 'min', 'median', 'p95', 'max'))
    print('-' * 80)
    for name, s in results.items():
        print('%-34s %5d %9.1f %9.1f %9.1f %9.1f' % (name, s['n'], s['min'], s['median'], s['p95'], s['max']))
    if peak_rss is not None:
        print()
        print('peak RSS: %.0f MB' % (peak_rss / 1024))
    print()
    print('note: didOpen/didChange latencies include the server\'s 500 ms debounce of background builds')


def main():
    parser = argparse.ArgumentParser(description='Nextflow language server benchmark')
    parser.add_argument('--workspace', required=True, help='workspace directory (e.g. nf-core/rnaseq checkout)')
    parser.add_argument('--file', default='workflows/rnaseq/main.nf', help='script to open/edit, relative to the workspace')
    parser.add_argument('--server-cmd', default=os.environ.get('NXF_LSP_BENCH_SERVER', DEFAULT_SERVER_CMD),
                        help='command to launch the server (env: NXF_LSP_BENCH_SERVER)')
    parser.add_argument('--iterations', type=int, default=10, help='iterations per scenario')
    parser.add_argument('--cold-iterations', type=int, default=3, help='server launches for the startup scenario')
    parser.add_argument('--timeout', type=int, default=300, help='timeout in seconds for any single wait')
    parser.add_argument('--json', help='write results to this JSON file')
    args = parser.parse_args()
    if args.iterations < 1 or args.cold_iterations < 1:
        parser.error('iterations must be >= 1')

    print('server: %s' % args.server_cmd)
    print('workspace: %s' % args.workspace, flush=True)
    try:
        results, samples, peak_rss, log_errors = run(args)
    except BenchError as e:
        print('\nFAILED: %s' % e, file=sys.stderr)
        return 1

    print_table(results, peak_rss)

    if args.json:
        Path(args.json).write_text(json.dumps({
            'date': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'server_cmd': args.server_cmd,
            'workspace': args.workspace,
            'workspace_tag': workspace_tag(args.workspace),
            'file': args.file,
            'iterations': args.iterations,
            'cold_iterations': args.cold_iterations,
            'peak_rss_kb': peak_rss,
            'results': results,
            'samples': samples,
        }, indent=2) + '\n')
        print('results written to %s' % args.json)

    if log_errors:
        print('\nFAILED: server reported %d error(s):' % len(log_errors), file=sys.stderr)
        for message in log_errors[:10]:
            print('  ' + message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

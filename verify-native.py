#!/usr/bin/env python3
"""
verify-native.py - check that the native binary behaves like the JVM build.

The native image only contains the reflection metadata it was told about, and a
binary missing some of it does not crash: it answers `initialize`, then quietly
returns empty results, or -- worse -- returns plausible but WRONG results. One
real example: with the Nextflow DSL classes unregistered, the server reported
"Unrecognized process input qualifier `val`" on a perfectly valid script, while
every response was still well-formed and non-empty.

No self-contained assertion catches that class of bug, because the correct
answer is whatever the JVM says. So this runs the same LSP session against both
builds and diffs the responses.

Usage:
  ./verify-native.py <jar> <binary>

Exits non-zero on any divergence, on a missing response, or on an error logged
by either server.
"""

import json
import os
import subprocess
import sys
import tempfile

SIMULATOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'lsp-simulator.sh')

# ids sent by lsp-simulator.sh; a missing one means the server never answered
EXPECTED_IDS = set(range(1, 18)) | {99}


def run(cmd, workspace):
    """Drive one server through the simulated session, return (messages, stderr)."""
    env = dict(os.environ, LSP_SIM_WORKSPACE=workspace)
    sim = subprocess.Popen([SIMULATOR], stdout=subprocess.PIPE, env=env)
    server = subprocess.Popen(cmd, stdin=sim.stdout,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    sim.stdout.close()  # let the simulator see SIGPIPE if the server dies
    out, err = server.communicate()
    sim.wait()
    return parse(out), err.decode('utf8', 'replace')


def parse(raw):
    """Split an LSP stream into messages, ignoring the Content-Length framing."""
    messages = []
    i = 0
    while True:
        header = raw.find(b'Content-Length:', i)
        if header < 0:
            break
        end = raw.find(b'\r\n\r\n', header)
        if end < 0:
            break
        length = int(raw[header + len('Content-Length:'):end].split(b'\r\n')[0])
        body = raw[end + 4:end + 4 + length]
        try:
            messages.append(json.loads(body))
        except json.JSONDecodeError:
            pass
        i = end + 4 + length
    return messages


def canonical(value):
    """Sort lists so that responses differing only in order compare equal.

    textDocument/references and rename legitimately return the same edits in a
    different order between the two builds.
    """
    if isinstance(value, list):
        return sorted((canonical(v) for v in value),
                      key=lambda v: json.dumps(v, sort_keys=True))
    if isinstance(value, dict):
        return {k: canonical(v) for k, v in value.items()}
    return value


def key(message):
    return (str(message.get('id', '~')).rjust(4), message.get('method', ''))


def dump(messages):
    return [json.dumps(canonical(m), sort_keys=True) for m in sorted(messages, key=key)]


def server_errors(stderr):
    lines = stderr.splitlines()
    return [l for l in lines
            if 'was never registered' in l or 'SEVERE' in l or 'Exception' in l]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip())
    jar, binary = sys.argv[1], sys.argv[2]

    # both servers must see the same workspace path -- URIs appear in responses
    with tempfile.TemporaryDirectory() as workspace:
        print('[INFO] running simulated LSP session against the JVM build...')
        jvm_messages, jvm_stderr = run(
            ['java', '-cp', jar, 'nextflow.lsp.NextflowLanguageServer'], workspace)

        print('[INFO] running simulated LSP session against the native binary...')
        native_messages, native_stderr = run([binary], workspace)

    failures = []

    for label, stderr in (('JVM', jvm_stderr), ('native', native_stderr)):
        errors = server_errors(stderr)
        if errors:
            failures.append('%s build logged errors:\n  %s'
                            % (label, '\n  '.join(errors[:10])))

    for label, messages in (('JVM', jvm_messages), ('native', native_messages)):
        answered = {m['id'] for m in messages if isinstance(m.get('id'), int)}
        missing = EXPECTED_IDS - answered
        if missing:
            failures.append('%s build never answered request ids: %s'
                            % (label, sorted(missing)))

    expected, actual = dump(jvm_messages), dump(native_messages)
    if expected != actual:
        import difflib
        diff = difflib.unified_diff(expected, actual, 'jvm', 'native', lineterm='', n=0)
        failures.append('responses diverged:\n'
                        + '\n'.join(l[:400] for l in diff))

    if failures:
        print('[ERROR] native binary does not match the JVM build:\n')
        for failure in failures:
            print(failure + '\n')
        return 1

    print('[INFO] native binary matches the JVM build across %d messages'
          % len(jvm_messages))
    return 0


if __name__ == '__main__':
    sys.exit(main())

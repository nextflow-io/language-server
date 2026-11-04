#!/usr/bin/env python3
"""
Compare benchmark results of two language server builds.

Pools the raw samples of each scenario across one or more JSON files per side
(as written by `scripts/bench.py --json`) and reports the change in median
latency and peak RSS from base to head.

A change is only reported as faster/slower (or larger/smaller for RSS) when it
is both statistically significant and practically relevant:

  - a two-sided Mann-Whitney U test (normal approximation with tie and
    continuity correction) gives p < --alpha (default 0.05), i.e. the two
    sample sets are unlikely to come from the same distribution; and
  - the relative change in median is at least --threshold (default 5%).

Otherwise the change is reported as noise. The test is rank-based, so it is
robust to outliers (e.g. GC pauses) and makes no normality assumption. With
few samples it cannot reach significance, so everything is reported as noise.

Usage:
  scripts/bench_diff.py --base base-*.json --head head-*.json [--json out.json]
"""

import argparse
import json
import math
import statistics
import sys
from pathlib import Path


def mann_whitney_p(a, b):
    """Two-sided p-value of the Mann-Whitney U test (normal approximation)."""
    n1, n2 = len(a), len(b)
    if n1 == 0 or n2 == 0:
        return 1.0
    values = sorted([(v, 0) for v in a] + [(v, 1) for v in b])
    n = n1 + n2
    # assign average ranks to ties
    ranks = [0.0] * n
    tie_term = 0
    i = 0
    while i < n:
        j = i
        while j + 1 < n and values[j + 1][0] == values[i][0]:
            j += 1
        for k in range(i, j + 1):
            ranks[k] = (i + j) / 2 + 1
        t = j - i + 1
        tie_term += t ** 3 - t
        i = j + 1
    r1 = sum(r for r, (_, side) in zip(ranks, values) if side == 0)
    u = r1 - n1 * (n1 + 1) / 2
    mu = n1 * n2 / 2
    var = n1 * n2 / 12 * ((n + 1) - tie_term / (n * (n - 1)))
    if var <= 0:
        return 1.0
    z = max(0.0, abs(u - mu) - 0.5) / math.sqrt(var)
    return math.erfc(z / math.sqrt(2))


def load(paths):
    """Pool samples per scenario (in file order) and collect peak RSS per file."""
    samples = {}
    rss = []
    meta = []
    for path in paths:
        data = json.loads(Path(path).read_text())
        if 'samples' not in data:
            raise SystemExit('%s: no raw samples (re-run with a newer scripts/bench.py)' % path)
        for name, values in data['samples'].items():
            samples.setdefault(name, []).extend(values)
        if data.get('peak_rss_kb') is not None:
            rss.append(data['peak_rss_kb'] / 1024)
        meta.append({k: data.get(k) for k in ('date', 'server_cmd', 'workspace_tag', 'iterations')})
    return samples, rss, meta


def compare(name, base, head, alpha, threshold, labels=('faster', 'slower')):
    base_median = statistics.median(base)
    head_median = statistics.median(head)
    delta = head_median - base_median
    pct = delta / base_median * 100 if base_median else 0.0
    p = mann_whitney_p(base, head)
    if p < alpha and abs(pct) >= threshold:
        verdict = labels[0] if delta < 0 else labels[1]
    else:
        verdict = 'noise'
    return {
        'scenario': name,
        'base_n': len(base),
        'head_n': len(head),
        'base_median': base_median,
        'head_median': head_median,
        'delta': delta,
        'delta_pct': pct,
        'p': p,
        'verdict': verdict,
    }


def main():
    parser = argparse.ArgumentParser(description='Compare Nextflow language server benchmark results')
    parser.add_argument('--base', nargs='+', required=True, metavar='JSON', help='result files of the base build')
    parser.add_argument('--head', nargs='+', required=True, metavar='JSON', help='result files of the head build')
    parser.add_argument('--alpha', type=float, default=0.05, help='significance level of the Mann-Whitney U test')
    parser.add_argument('--threshold', type=float, default=5.0, help='minimum relative change (%%) to report')
    parser.add_argument('--json', help='write the comparison to this JSON file')
    args = parser.parse_args()

    base, base_rss, base_meta = load(args.base)
    head, head_rss, head_meta = load(args.head)

    rows = []
    for name in base:
        if name in head:
            rows.append(compare(name, base[name], head[name], args.alpha, args.threshold))
    missing = sorted(set(base) ^ set(head))
    rss = None
    if base_rss and head_rss:
        rss = compare('peak RSS (MB)', base_rss, head_rss, args.alpha, args.threshold, labels=('smaller', 'larger'))

    print('base: %s (%d file(s))' % (base_meta[0]['server_cmd'], len(args.base)))
    print('head: %s (%d file(s))' % (head_meta[0]['server_cmd'], len(args.head)))
    print()
    print('%-34s %9s %9s %9s %8s %7s  %s' % ('scenario (ms)', 'base', 'head', 'delta', 'delta%', 'p', 'verdict'))
    print('-' * 90)
    for r in rows + ([rss] if rss else []):
        if r is rss:
            print()
        print('%-34s %9.1f %9.1f %+9.1f %+7.1f%% %7.3f  %s' % (
            r['scenario'], r['base_median'], r['head_median'], r['delta'], r['delta_pct'], r['p'], r['verdict']))
    print()
    print('medians of pooled samples; verdict requires p < %g (Mann-Whitney U) and |delta%%| >= %g%%'
          % (args.alpha, args.threshold))
    if missing:
        print('scenarios only on one side (skipped): %s' % ', '.join(missing))

    if args.json:
        Path(args.json).write_text(json.dumps({
            'base': {'files': args.base, 'runs': base_meta},
            'head': {'files': args.head, 'runs': head_meta},
            'alpha': args.alpha,
            'threshold_pct': args.threshold,
            'scenarios': rows,
            'peak_rss': rss,
        }, indent=2) + '\n')
        print('comparison written to %s' % args.json)
    return 0


if __name__ == '__main__':
    sys.exit(main())

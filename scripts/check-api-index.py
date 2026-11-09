#!/usr/bin/env python3
"""
Checks the links of the API index against the live docs, and writes the
checked index, which has only links that work.

- A symbol whose anchor is not on its page links to the page instead.
- A symbol whose page is not found (HTTP 404) is dropped.
- Each fix is listed as a warning. They are usually names that the docs do
  not describe yet, or stale links in nf-lang.

The check fails if a page cannot be fetched for another reason, if a name is
defined twice with the same kind in the same context, or if more than 10% of
the symbols need a fix, which means that the docs moved and this repository
needs a change.

Usage:
  scripts/check-api-index.py build/generated/api-index.json build/api-index.json
"""

import collections
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_FIXED = 0.1


def fetch_ids(url):
    """Returns the element ids of a page, or None if the page is not found."""
    request = urllib.request.Request(url, headers={'User-Agent': 'nextflow-language-server api-index check'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                html = response.read().decode('utf-8', 'replace')
            # the pages are minified, so an id can be unquoted
            return set(re.findall(r'\sid="?([^"\s>]+)', html))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == 2:
                raise
        except Exception:
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)


def main(source, target):
    with open(source) as f:
        index = json.load(f)
    symbols = index['symbols']
    errors = []
    warnings = []

    seen = collections.Counter((s['name'], s.get('context'), s['kind']) for s in symbols)
    errors += [f'{name} ({kind}): defined {n} times' for (name, _, kind), n in seen.items() if n > 1]

    pages = {}
    for s in symbols:
        page = urllib.parse.urljoin(index['base'], s['href']).partition('#')[0]
        pages[page] = None
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futures = {page: pool.submit(fetch_ids, page) for page in pages}
    failed = set()
    for page, future in futures.items():
        try:
            pages[page] = future.result()
        except Exception as e:
            failed.add(page)
            errors.append(f'{page}: {e}')

    checked = []
    for s in symbols:
        page, _, anchor = urllib.parse.urljoin(index['base'], s['href']).partition('#')
        ids = pages.get(page)
        if ids is None:
            if page not in failed:
                warnings.append(f'{s["name"]}: dropped, {page} not found')
            continue
        if anchor and anchor not in ids:
            warnings.append(f'{s["name"]}: no #{anchor} on {page}, links to the page')
            s = {**s, 'href': s['href'].partition('#')[0]}
        checked.append(s)

    if len(warnings) > MAX_FIXED * len(symbols):
        errors.append(f'{len(warnings)} of {len(symbols)} symbols need a fix: did the docs move?')

    for warning in warnings:
        print(f'warning: {warning}')
    for error in errors:
        print(f'error: {error}')
    summary = f'{len(checked)} of {len(symbols)} symbols, {len(pages)} pages, {len(warnings)} fixed links'
    print(summary)
    if os.environ.get('GITHUB_ACTIONS') and warnings:
        print(f'::warning title=API index::{summary}. See the log of this step.')
    if errors:
        return 1

    with open(target, 'w') as f:
        json.dump({**index, 'symbols': checked}, f, indent=2, ensure_ascii=False)
        f.write('\n')
    return 0


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))

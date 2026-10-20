#!/usr/bin/env bash
#
# Clone a pipeline at a given tag for use as a benchmark workspace.
#
# Usage: scripts/bench-workspace.sh [--repo URL] [--tag TAG] [--dir DIR]
#
set -euo pipefail

repo=https://github.com/nf-core/rnaseq
tag=3.27.0
dir=build/bench/rnaseq

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo) repo="$2"; shift 2 ;;
        --tag)  tag="$2"; shift 2 ;;
        --dir)  dir="$2"; shift 2 ;;
        -h|--help) sed -n '3,5p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

if [[ "$(git -C "$dir" tag --points-at HEAD 2>/dev/null)" == "$tag" ]]; then
    exit 0
fi

rm -rf "$dir"
git clone --quiet --depth 1 --branch "$tag" -c advice.detachedHead=false "$repo" "$dir"

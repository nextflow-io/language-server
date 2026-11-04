#!/usr/bin/env bash
#
# Compare a base version of the language server against the current checkout.
#
# The base ref is built in a git worktree. The benchmark is run alternately for
# base and head in each round, and the pooled samples are compared with
# scripts/bench_diff.py: a change is reported only if it is significant
# (Mann-Whitney U test, p < 0.05) and at least 5%.
#
# Usage: scripts/bench-compare.sh [options] [-- bench.py args]
#
#   --base REF             git ref to build as the base (default: main)
#   --base-cmd CMD         base server command (skips building the base ref)
#   --head-cmd CMD         head server command (skips building the current checkout)
#   --workspace DIR        benchmark workspace (default: build/bench/rnaseq)
#   --rounds N             number of base/head rounds (default: 3)
#   --iterations N         iterations per scenario (default: 20)
#   --cold-iterations N    server launches per startup scenario (default: 3)
#   --out DIR              output directory (default: build/bench/compare)
#
set -euo pipefail

base=main
base_cmd=
head_cmd=
workspace=build/bench/rnaseq
rounds=3
iterations=20
cold_iterations=3
out=build/bench/compare
base_dir=build/bench/base
extra=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --base)            base="$2"; shift 2 ;;
        --base-cmd)        base_cmd="$2"; shift 2 ;;
        --head-cmd)        head_cmd="$2"; shift 2 ;;
        --workspace)       workspace="$2"; shift 2 ;;
        --rounds)          rounds="$2"; shift 2 ;;
        --iterations)      iterations="$2"; shift 2 ;;
        --cold-iterations) cold_iterations="$2"; shift 2 ;;
        --out)             out="$2"; shift 2 ;;
        --) shift; extra=("$@"); break ;;
        -h|--help) sed -n '3,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "$base_cmd" ]]; then
    git worktree prune
    if [[ -e "$base_dir/.git" ]]; then
        git -C "$base_dir" checkout --quiet --detach "$(git rev-parse --verify "$base^{commit}")"
    else
        git worktree add --detach "$base_dir" "$base"
    fi
    (cd "$base_dir" && ./gradlew shadowJar)
    base_cmd="java -jar $base_dir/build/libs/language-server-all.jar"
fi

if [[ -z "$head_cmd" ]]; then
    ./gradlew shadowJar
    head_cmd="java -jar build/libs/language-server-all.jar"
fi

rm -rf "$out" && mkdir -p "$out"

# interleave base and head runs so that drift affects both equally
for i in $(seq "$rounds"); do
    for side in base head; do
        if [[ $side == base ]]; then cmd="$base_cmd"; else cmd="$head_cmd"; fi
        python3 scripts/bench.py \
            --workspace "$workspace" \
            --iterations "$iterations" \
            --cold-iterations "$cold_iterations" \
            --server-cmd "$cmd" \
            --json "$out/$side-$i.json" \
            "${extra[@]}"
    done
done

python3 scripts/bench_diff.py \
    --base "$out"/base-*.json \
    --head "$out"/head-*.json \
    --json "$out/compare.json"

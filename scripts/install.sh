#!/usr/bin/env bash
#
# Install the bundle for the current platform into the language server
# directory of the VS Code extension, for testing a local build.
#
# Usage: scripts/install.sh --version VERSION [--dest DIR]
#
#   --version VERSION   bundle version, e.g. 27.04.0 (must match a built bundle)
#   --dest DIR          installation directory (default: ~/.nextflow/lsp)
#
set -euo pipefail

version=
dest=~/.nextflow/lsp

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) version="${2#v}"; shift 2 ;;
        --dest)    dest="$2"; shift 2 ;;
        -h|--help) sed -n '3,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "$version" ]]; then
    echo "--version is required" >&2
    exit 1
fi

case "$(uname -s)" in
    Linux)  os=linux ;;
    Darwin) os=macos ;;
    *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  arch=x64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

name=nextflow-language-server-$version-$os-$arch
tarball=build/bundle/$name.tar.gz
if [[ ! -f "$tarball" ]]; then
    echo "bundle not found: $tarball" >&2
    exit 1
fi

# the extension looks for language server versions under vX.Y
line_dir=$dest/v$(echo "$version" | cut -d. -f1-2)
mkdir -p "$line_dir"
rm -rf "${line_dir:?}/$name"
# extract with tar to preserve the jar mtime, which the AOT cache depends on
tar -xzf "$tarball" -C "$line_dir"

# the current extension uses an executable named nextflow-lsp in the version
# directory if present, so link it to the launcher of the bundle
ln -sfn "$name/bin/nextflow-lsp" "$line_dir/nextflow-lsp"

echo "installed at: $line_dir/$name"

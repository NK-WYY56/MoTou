#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
"${SWIFTC:-swiftc}" -swift-version 5 "$root/App/SiqiKoveServer.swift" "$root/Tools/SiqiKoveSmoke.swift" -o "$stage/siqi-kove-test"
"$stage/siqi-kove-test"

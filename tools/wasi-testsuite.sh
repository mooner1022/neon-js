#!/usr/bin/env bash
# The WASI testsuite for NeonWasm's WASI (neonwasm's WasiTestsuiteTest, docs/NEONWASM.md): the prebuilt preview 1
# tests of WebAssembly/wasi-testsuite (its prod/testsuite-base branch at CI's revision, WASI_TESTSUITE_COMMIT in
# .github/workflows/ci.yml) in third_party/wasi-testsuite. Their data files are checked out as they are (no CRLF:
# the tests read them byte for byte).
set -euo pipefail

cd "$(dirname "$0")/.."
DIR=third_party/wasi-testsuite
commit=${WASI_TESTSUITE_COMMIT:-$(sed -n 's/^ *WASI_TESTSUITE_COMMIT: *//p' .github/workflows/ci.yml)}

if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]; then
  echo "WASI testsuite $commit into $DIR"
  rm -rf "$DIR"
  git init -q "$DIR"
  # (patterns on stdin: an argument starting with / would be rewritten into a Windows path by Git Bash)
  printf '%s\n' 'tests/*/testsuite/wasm32-wasip1/' | git -C "$DIR" sparse-checkout set --no-cone --stdin
  git -C "$DIR" fetch -q --depth 1 --filter=blob:none https://github.com/WebAssembly/wasi-testsuite.git "$commit"
  git -C "$DIR" -c core.autocrlf=false checkout -q FETCH_HEAD
fi

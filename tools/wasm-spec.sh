#!/usr/bin/env bash
# The WebAssembly spec tests for NeonWasm (neonwasm's SpecTest, docs/NEONWASM.md): the core tests of WebAssembly/spec
# at CI's revision (WASM_SPEC_COMMIT in .github/workflows/ci.yml) in third_party/wasm-spec, converted by
# `wasm-tools json-from-wast` into build/wasm-spec/<name>/ (<name>.json and its modules). Converted again when the
# revision or wasm-tools' version changes. Without wasm-tools, says so and converts nothing (SpecTest then skips).
#
# Environment: WASM_TOOLS (default: wasm-tools on the PATH; `cargo install wasm-tools --locked`, or a release binary
# as CI takes, at WASM_TOOLS_VERSION in ci.yml)
set -euo pipefail

cd "$(dirname "$0")/.."
SPEC=third_party/wasm-spec
OUT=build/wasm-spec
WASM_TOOLS=${WASM_TOOLS:-wasm-tools}
commit=${WASM_SPEC_COMMIT:-$(sed -n 's/^ *WASM_SPEC_COMMIT: *//p' .github/workflows/ci.yml)}

if [ "$(git -C "$SPEC" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]; then
  echo "WebAssembly spec tests $commit into $SPEC"
  rm -rf "$SPEC"
  git init -q "$SPEC"
  git -C "$SPEC" sparse-checkout set test/core
  git -C "$SPEC" fetch -q --depth 1 --filter=blob:none https://github.com/WebAssembly/spec.git "$commit"
  git -C "$SPEC" checkout -q FETCH_HEAD
fi

if ! command -v "$WASM_TOOLS" >/dev/null 2>&1; then
  echo "(no $WASM_TOOLS: the WebAssembly spec tests are not converted; neonwasm's SpecTest skips them)"
  exit 0
fi
stamp="$commit $("$WASM_TOOLS" --version)"
if [ -f "$OUT/.stamp" ] && [ "$(cat "$OUT/.stamp")" = "$stamp" ]; then exit 0; fi

rm -rf "$OUT"
# (test/core's own files: its simd/ directory, SIMD, is not supported)
for f in "$SPEC"/test/core/*.wast; do
  n=$(basename "$f" .wast)
  mkdir -p "$OUT/$n"
  "$WASM_TOOLS" json-from-wast "$f" -o "$OUT/$n/$n.json" --wasm-dir "$OUT/$n"
done
echo "$stamp" > "$OUT/.stamp"
echo "WebAssembly spec tests converted into $OUT ($(ls "$OUT" | wc -l) files)"

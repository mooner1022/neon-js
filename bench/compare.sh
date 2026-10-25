#!/bin/bash
# usage: bench/compare.sh OUT_DIR RHINO_JAR
# Runs bench/compare.js on V8 (node), NeonJS (each execution mode, and adaptive with the optimizing tier) and Rhino
# (interpreted and compiled), one engine at a time, each in its own process. One file per configuration in OUT_DIR;
# docs/PERFORMANCE.md describes the results.
set -u
OUT=$1; RHINO=$2
cd "$(dirname "$0")/.."
NEONJS=neonjs-cli/build/install/neonjs/bin/neonjs
mkdir -p "$OUT"
run() {
  local name=$1; shift
  echo "== $name"
  local start=$(date +%s)
  "$@" bench/compare.js > "$OUT/$name.txt" 2>&1
  echo "wall $(( $(date +%s) - start ))" >> "$OUT/$name.txt"
  cat "$OUT/$name.txt"
}
run v8 node
run neonjs-interpreter "$NEONJS" --interpreter
run neonjs-compiled "$NEONJS" --compiled
run neonjs-adaptive "$NEONJS" --adaptive
JAVA_OPTS="-Dneonjs.jit.tier2=true" run neonjs-adaptive-t2 "$NEONJS" --adaptive
run rhino-compiled java -Xss16m -jar "$RHINO" -version 200 -opt 9
run rhino-interpreted java -Xss16m -jar "$RHINO" -version 200 -opt -1

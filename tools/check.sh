#!/usr/bin/env bash
# Development checks (docs/TESTING.md).
#
#   tools/check.sh quick            unit tests, then a fixed 1/20 of Test262 in the interpreter and in the JIT's stress modes
#   tools/check.sh codegen [REF]    the classes the JIT generates for that part of Test262, against those of REF (default: main)
#   tools/check.sh full             what CI runs, and all of Test262 in the stress modes: before a large pull request
#
# Environment: TEST262 (default third_party/test262, checked out at CI's revision when missing), GRADLE_ARGS (extra
# arguments for every ./gradlew run), SAMPLE (default 20: one test in SAMPLE), THREADS (default: every processor).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
TEST262=${TEST262:-third_party/test262}
SAMPLE=${SAMPLE:-20}
THREADS=${THREADS:-$(nproc 2>/dev/null || echo 4)}
GRADLE_ARGS=${GRADLE_ARGS:-}
RUNNER=neonjs-test262/build/install/neonjs-test262/bin/neonjs-test262
LIB="neonjs-test262/build/install/neonjs-test262/lib/*"

step() { printf '\n== %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }
gradle() { # shellcheck disable=SC2086
  ./gradlew -q $GRADLE_ARGS "$@"; }

test262_checkout() {
  [ -d "$TEST262/test" ] && return
  local commit
  commit=$(sed -n 's/^ *TEST262_COMMIT: *//p' .github/workflows/ci.yml)
  step "Test262 $commit into $TEST262"
  git init -q "$TEST262"
  git -C "$TEST262" fetch -q --depth 1 https://github.com/tc39/test262.git "$commit"
  git -C "$TEST262" checkout -q FETCH_HEAD
}

# t262 NAME JAVA_OPTS RUNNER_ARGS...: one run of the Test262 runner, its summary printed
t262() {
  local name=$1 opts=$2; shift 2
  local out start
  start=$(date +%s)
  out=$(JAVA_OPTS="$opts" "$RUNNER" --root "$TEST262" --threads "$THREADS" "$@" 2>&1) || {
    printf '%s\n' "$out" | grep -E '^(TOTAL|FAIL|UNEXPECTED)' | head -30
    fail "Test262, $name"
  }
  printf '%-44s %s  (%ss)\n' "$name" "$(printf '%s\n' "$out" | grep -E '^TOTAL' | sed 's/  (.*//')" "$(( $(date +%s) - start ))"
  printf '%s\n' "$out" | grep -E '^(T2 compiled|JIT deoptimizations)' | sed 's/^/    /' || true
}

# the JIT modes every check runs: the stress modes force each tier's code, exits and on-stack replacement entries
stress_modes() {
  local scope=("$@")
  t262 "interpreter" "" --mode interpreter "${scope[@]}"
  t262 "compiled, t2Stress" "-Dneonjs.jit.t2Stress" --mode compiled "${scope[@]}"
  t262 "compiled, t2Stress + deoptStress" "-Dneonjs.jit.t2Stress -Dneonjs.jit.deoptStress" --mode compiled "${scope[@]}"
  t262 "adaptive, t2Stress + osrStress" "-Dneonjs.jit.t2Stress -Dneonjs.jit.osrStress" --mode adaptive "${scope[@]}"
  t262 "adaptive, t2Stress + deoptStress + osrStress" "-Dneonjs.jit.t2Stress -Dneonjs.jit.deoptStress -Dneonjs.jit.osrStress" --mode adaptive "${scope[@]}"
}

quick() {
  test262_checkout
  step "unit tests (neonjs-core), Test262 runner"
  gradle :neonjs-core:test :neonjs-test262:installDist
  step "Test262, 1 test in $SAMPLE"
  # (the generated Unicode property tests check RegExp's tables, slowly in the interpreter: full runs them)
  stress_modes --sample "$SAMPLE" --timeout 60000 --exclude built-ins/RegExp/property-escapes/generated
}

# dumps DIR RUNNER VARIANT: the classes generated for the sample, compiled on the calling thread (deterministic)
dumps() {
  local dir=$1 runner=$2 variant=$3 opts mode=compiled
  case $variant in
    t2) opts="-Dneonjs.jit.t2Stress" ;;
    stress) opts="-Dneonjs.jit.t2Stress -Dneonjs.jit.deoptStress" ;;
    # (profiles and caches filled by the interpreter first: the speculations, LICM, the facts after guards)
    adaptive) opts="-Dneonjs.jit.t2Stress -Dneonjs.jit.osrStress"; mode=adaptive ;;
  esac
  rm -rf "$dir"
  mkdir -p "$dir"
  JAVA_OPTS="$opts -Dneonjs.jit.dump=$ROOT/$dir" "$runner" --root "$TEST262" --threads "$THREADS" --mode $mode \
    --sync-jit --sample "$SAMPLE" --timeout 60000 --exclude built-ins/RegExp/property-escapes/generated > "$dir.log" 2>&1 || { tail -20 "$dir.log"; fail "dumping classes ($variant) with $runner"; }
}

codegen() {
  local ref=${1:-main} sha base
  test262_checkout
  sha=$(git rev-parse --verify "$ref^{commit}")
  base=build/check/base
  step "the JIT's classes against $ref (${sha:0:12})"
  gradle :neonjs-test262:installDist
  local variants="t2 stress adaptive" v missing=
  for v in $variants; do [ -d build/check/dumps/$sha-$v ] || missing=1; done
  if [ -n "$missing" ]; then
    if [ -d $base ]; then git -C $base checkout -q -f --detach "$sha"; else git worktree add -q --detach $base "$sha"; fi
    # REF's engine with this runner (whose options the dumps need: --sync-jit, --sample, --exclude)
    rm -rf $base/neonjs-test262/src
    cp -R neonjs-test262/src neonjs-test262/build.gradle.kts $base/neonjs-test262/
    (cd $base && ./gradlew -q $GRADLE_ARGS :neonjs-test262:installDist) || fail "building $ref"
    for v in $variants; do dumps build/check/dumps/$sha-$v $base/$RUNNER $v; done
  fi
  local status=0
  for v in $variants; do
    dumps build/check/dumps/current-$v "$RUNNER" $v
    printf '%-8s ' "$v:"
    java -cp "$LIB" dev.mooner.neonjs.test262.ClassDiffKt build/check/dumps/$sha-$v build/check/dumps/current-$v --show 10 || status=1
  done
  [ $status -eq 0 ] || fail "generated code differs from $ref"
}

full() {
  test262_checkout
  step "build, all unit tests, Test262 runner"
  gradle build :neonjs-test262:installDist
  step "Test262 (CI's runs)"
  t262 "interpreter" "" --mode interpreter
  t262 "compiled" "" --mode compiled
  t262 "adaptive" "" --mode adaptive
  t262 "compiled, classes translated by dx" "-Dneonjs.codeDefiner=dev.mooner.neonjs.android.DexCheckingDefiner" --mode compiled
  t262 "intl402" "" --intl --known neonjs-test262/known-failures.txt test/intl402
  step "Test262, stress modes"
  t262 "compiled, t2Stress" "-Dneonjs.jit.t2Stress" --mode compiled
  t262 "compiled, t2Stress + deoptStress" "-Dneonjs.jit.t2Stress -Dneonjs.jit.deoptStress" --mode compiled
  t262 "adaptive, t2Stress + osrStress" "-Dneonjs.jit.t2Stress -Dneonjs.jit.osrStress" --mode adaptive
  t262 "adaptive, t2Stress + deoptStress + osrStress" "-Dneonjs.jit.t2Stress -Dneonjs.jit.deoptStress -Dneonjs.jit.osrStress" --mode adaptive
  if [ -d third_party/wpt ]; then
    step "web-platform-tests"
    java -Xss16m -cp "$LIB" dev.mooner.neonjs.test262.WptKt --root third_party/wpt --known neonjs-test262/wpt-known-failures.txt \
      --include-window url/IdnaTestV2.any.js --include-window url/IdnaTestV2-removed.any.js \
      encoding dom/events dom/abort dom/idlharness.any.js hr-time WebCryptoAPI FileAPI xhr/formdata fetch/api/headers url || fail "web-platform-tests"
  else echo "(no third_party/wpt: web-platform-tests skipped; CI runs them)"; fi
  if [ -d third_party/node ]; then
    step "Node.js tests"
    java -Xss16m -cp "$LIB" dev.mooner.neonjs.test262.NodeTestsKt --root third_party/node \
      --known neonjs-test262/node-known-failures.txt --retries 1 || fail "Node.js tests"
  else echo "(no third_party/node: Node.js tests skipped; CI runs them)"; fi
}

case ${1:-quick} in
  quick) quick ;;
  codegen) codegen "${2:-main}" ;;
  full) full ;;
  *) echo "usage: tools/check.sh quick | codegen [REF] | full" >&2; exit 2 ;;
esac
step "passed"

#!/bin/sh
# Runs on a booted emulator or device (the Android workflow; locally: set ANDROID_SERIAL to pick a device):
# the Android checks and part of Test262 in all three modes with dx, then the checks and adaptive mode (Android's
# default, compiling in background batches) with D8, whose conversions are slow enough to make compiled mode long.
# Expects the bundles of bundle.py in build/android and Test262 in third_party/test262.
set -e
cd "$(dirname "$0")/../.."
PY=$(command -v python3 || command -v python)
D="$PY tools/android/device.py"
TESTS="language/statements built-ins/Array built-ins/TypedArray built-ins/TypedArrayConstructors built-ins/DataView built-ins/Atomics built-ins/ArrayBuffer"

$D install build/android/neonjs-test262.jar
$D run neonjs-test262 io.neonjs.test262.AndroidCheck
$D push-test262 third_party/test262 $TESTS
for mode in interpreter compiled adaptive; do
  echo "== Test262 ($mode, dx)"
  $D test262 neonjs-test262 --mode $mode --timeout 60000 $TESTS
done

$D install build/android/neonjs-test262-d8.jar
$D run neonjs-test262-d8 io.neonjs.test262.AndroidCheck
echo "== Test262 (adaptive, D8)"
$D test262 neonjs-test262-d8 --mode adaptive --timeout 60000 $TESTS

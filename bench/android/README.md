# compare.js on Android

Runs [`../compare.js`](../compare.js) on NeonJS and Rhino under ART, the way an app runs them: each engine is a
dex bundle started with `app_process` and compiled ahead of time by `tools/android/device.py install`. Results are in
[`docs/PERFORMANCE.md`](../../docs/PERFORMANCE.md#android).

Which Rhino:
- Rhino 1.8 and later generate classes that link against `jdk.dynalink`, which Android lacks, so their compiled mode
  fails on Android (`ClassNotFoundException: jdk.dynalink.DynamicLinkerFactory`). Rhino 1.9.1 runs interpreted only.
- Compiled Rhino on Android is Rhino 1.7.15.1 with [rhino-android](https://github.com/F43nd1r/rhino-android) 1.6.0
  (`com.faendir.rhino:rhino-android`), whose `AndroidContextFactory` translates each generated class to dex with dx
  at run time. Rhino 1.7.15.1 runs interpreted too.

[`RhinoBench.java`](RhinoBench.java) is the Rhino launcher: `RhinoBench interpreted|compiled FILE`, with `print`
defined. [`run.sh`](run.sh) runs every configuration round after round on the device.

## Setup

From the repository root, in Git Bash (`MSYS_NO_PATHCONV=1` keeps `/data/...` paths as they are):

```bash
export MSYS_NO_PATHCONV=1
./gradlew :neonjs-cli:installDist
python tools/android/bundle.py --out build/android/neonjs-cli.jar neonjs-cli/build/install/neonjs/lib

# one directory per Rhino: its jar, rhino-android's classes.jar (from the .aar), dx and RhinoBench
#   rlib19: rhino-all-1.9.1.jar  rhino-android-1.6.0.jar  dalvik-dx-16.0.1.jar  rhinobench.jar
#   rlib17: rhino-1.7.15.1.jar   rhino-android-1.6.0.jar  dalvik-dx-16.0.1.jar  rhinobench.jar
# dalvik-dx-16.0.1.jar is in neonjs-cli/build/install/neonjs/lib. rhinobench.jar for each, from:
#   javac --release 11 -cp "RHINO_JAR;rhino-android-1.6.0.jar" -d classes bench/android/RhinoBench.java
#   jar cf rhinobench.jar -C classes .
python tools/android/bundle.py --out build/android/rhino19-bench.jar rlib19
python tools/android/bundle.py --out build/android/rhino17-bench.jar rlib17

for j in neonjs-cli rhino19-bench rhino17-bench; do python tools/android/device.py install build/android/$j.jar; done
sed 's/^var SCALE = .*/var SCALE = 0.25;/' bench/compare.js > build/android/compare-q.js
adb shell mkdir -p /data/local/tmp/bench
adb push bench/compare.js build/android/compare-q.js bench/android/run.sh /data/local/tmp/bench/
```

The NeonJS CLI passes no arguments to a script, so the quarter-size file is a copy with the scale written in.

## Running

```bash
adb shell 'cd /data/local/tmp && sed -i "s/\r$//" bench/run.sh && rm -rf bench/out && (setsid nohup sh bench/run.sh 3 > bench/out.log 2>&1 &)'
# later, when /data/local/tmp/bench/out/DONE exists:
adb pull /data/local/tmp/bench/out
```

The run goes on without adb, so a phone can sleep or disconnect. On a phone, `CORES=c0 sh bench/run.sh 3` pins every
run to the cores in that mask (e.g. the prime cores), and `CPU=6` logs that core's clock.

Each `out/ROUND/CONFIG.txt` has a `workload median-ms warm-up-ms checksum` line per workload. At full size the
checksum column says `ok` or `MISMATCH`. At other sizes it prints `sum=N`; compare that with `node bench/compare.js 0.25`.

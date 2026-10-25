#!/system/bin/sh
# Runs on the device (adb shell sh /data/local/tmp/bench/run.sh ROUNDS), after the setup in bench/android/README.md:
# compare.js on NeonJS (compiled, adaptive, adaptive with the optimizing tier) and on compiled Rhino 1.7 at full size,
# and compare-q.js (a quarter of the size) on the interpreters, round after round. Results in
# /data/local/tmp/bench/out/ROUND/CONFIG.txt, with a core's clock and the battery temperature before and after each.
#   CORES  taskset mask for every run (e.g. c0 for a phone's prime cores); unset runs unpinned
#   CPU    the core whose clock is logged (default 0)
cd /data/local/tmp
ROUNDS=${1:-3}
CPU=${CPU:-0}
state() {
  freq=/sys/devices/system/cpu/cpu$CPU/cpufreq
  echo "$1 clock $(cat $freq/scaling_cur_freq 2>/dev/null)/$(cat $freq/scaling_max_freq 2>/dev/null)" \
    "battery $(dumpsys battery | grep -m1 ' temperature:' | tr -dc 0-9)"
}
run() {
  name=$1; jar=$2; file=$3; shift 3
  f=bench/out/$r/$name.txt
  state start > $f
  s=$(date +%s)
  CLASSPATH=/data/local/tmp/$jar.jar ${CORES:+taskset $CORES} app_process "$@" bench/$file >> $f 2>&1
  echo "exit $? wall $(( $(date +%s) - s ))" >> $f
  state end >> $f
}
NEON="/data/local/tmp dev.mooner.neonjs.cli.MainKt"
mkdir -p rhino-cache
for r in $(seq 1 $ROUNDS); do
  mkdir -p bench/out/$r
  run neonjs-adaptive neonjs-cli compare.js $NEON --adaptive
  run rhino17-compiled rhino17-bench compare.js /data/local/tmp RhinoBench compiled
  run neonjs-compiled neonjs-cli compare.js $NEON --compiled
  run neonjs-adaptive-t2 neonjs-cli compare.js -Dneonjs.jit.tier2=true $NEON --adaptive
  run neonjs-interpreter neonjs-cli compare-q.js $NEON --interpreter
  run rhino19-interpreted rhino19-bench compare-q.js /data/local/tmp RhinoBench interpreted
  run rhino17-interpreted rhino17-bench compare-q.js /data/local/tmp RhinoBench interpreted
done
echo done > bench/out/DONE

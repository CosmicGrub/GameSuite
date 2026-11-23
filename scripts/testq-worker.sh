#!/usr/bin/env bash
# Detached test-queue worker: runs queued Gradle test jobs one at a time, independent of any Claude session
# (survives usage limits). Jobs come from testq-submit.sh. Results land in $Q/done/<job>.result and logs in $Q/logs/.
# Single instance (pid-checked). Uses the machine-wide gradle lock so it never overlaps other builds.
set -u
Q="${TESTQ_DIR:-/z/GameSuite-testq}"
export JAVA_HOME="${JAVA_HOME:-/c/Users/Obliv/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot}"
export PATH="$JAVA_HOME/bin:$PATH:/c/Users/Obliv/.cargo/bin"
mkdir -p "$Q/pending" "$Q/running" "$Q/done" "$Q/logs"
PIDF="$Q/worker.pid"
if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then exit 0; fi
echo $$ > "$PIDF"
trap 'rm -f "$PIDF"' EXIT
while true; do
  job=$(ls -1 "$Q/pending"/*.job 2>/dev/null | sort | head -1)
  if [ -z "$job" ]; then sleep 20; continue; fi
  name=$(basename "$job" .job)
  mv "$job" "$Q/running/$name.job" 2>/dev/null || continue
  # job file format: line1 = worktree path, line2.. = gradle args
  first=$(sed -n 1p "$Q/running/$name.job" | tr -d '\r')
  if [ "${first#DEVICE:}" != "$first" ]; then
    # Device jobs: line1 = "DEVICE:<worktree>", line2.. = ONE shell command that drives the device (e.g. via adb).
    dwt="${first#DEVICE:}"; dlog="$Q/logs/$name.log"
    # HARD GATE (owner rule 2026-11-07): nothing touches a device unless the owner created this file on purpose.
    if [ ! -f "$Q/DEVICES_AUTHORIZED" ]; then
      mkdir -p "$Q/held"; mv "$Q/running/$name.job" "$Q/held/$name.job"
      echo "held=awaiting owner authorisation (create $Q/DEVICES_AUTHORIZED)" > "$Q/done/$name.result"; continue
    fi
    dcmd=$(sed -n '2,$p' "$Q/running/$name.job" | tr -d '\r')
    until mkdir /tmp/gs-device.lock 2>/dev/null; do sleep 20; done   # one device job at a time (shared devices)
    dstart=$(date +%s)
    ( cd "$dwt" && eval "$dcmd" ) > "$dlog" 2>&1; drc=$?
    rmdir /tmp/gs-device.lock 2>/dev/null
    { echo "job=$name"; echo "type=device"; echo "worktree=$dwt"; echo "verdict=$([ $drc -eq 0 ] && echo GREEN || echo RED)"; echo "rc=$drc"; echo "seconds=$(( $(date +%s) - dstart ))"; echo "finished=$(date '+%F %T')"; echo "log=$dlog"; } > "$Q/done/$name.result"
    rm -f "$Q/running/$name.job"; continue
  fi
  wt="$first"
  args=$(sed -n '2,$p' "$Q/running/$name.job" | tr -d '\r' | tr '\n' ' ')
  log="$Q/logs/$name.log"
  until mkdir /tmp/gs-gradle.lock 2>/dev/null; do sleep 15; done
  start=$(date +%s)
  ( cd "$wt" && eval "./gradlew :app:testDebugUnitTest $args --build-cache --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g -Pkotlin.compiler.execution.strategy=in-process" ) > "$log" 2>&1
  rc=$?
  rmdir /tmp/gs-gradle.lock 2>/dev/null
  counts=$(cd "$wt" && python - <<'PY'
import glob,re
c=t=f=e=0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"',open(p,encoding='utf8').read(800))
    if m: c+=1;t+=int(m[1]);f+=int(m[3]);e+=int(m[4])
print(f"classes={c} tests={t} failures={f} errors={e}")
PY
)
  verdict=GREEN
  [ "$rc" -ne 0 ] && verdict=RED
  { echo "job=$name"; echo "worktree=$wt"; echo "args=$args"; echo "verdict=$verdict"; echo "gradle_rc=$rc"; echo "$counts"; echo "seconds=$(( $(date +%s) - start ))"; echo "finished=$(date '+%F %T')"; echo "log=$log"; } > "$Q/done/$name.result"
  rm -f "$Q/running/$name.job"
  ls -1t "$Q/logs"/*.log 2>/dev/null | tail -n +40 | xargs -r rm -f
done

#!/usr/bin/env bash
# Detached DEVICE installer (owner authorised installs in the detached runner, 2026-11-22; supersedes the 2026-11-07 no-install rule for GameSuite).
# Installs the newest GREEN-gated debug build of GameSuite onto every attached, authorised device, launches it and records a crash smoke result.
# Safety rules (hard): only HEAD builds with a green-gate marker; `adb install -r -t` only (keeps app data; NEVER uninstall, NEVER -d downgrade, NEVER pm clear / wipe);
# only package com.gamesuite; skip a device whose screen is on with GameSuite in the foreground (do not kill a live session); one device job at a time
# (/tmp/gs-device.lock, devices are shared); on a signature mismatch it logs and leaves the install alone. Gate file: /z/GameSuite-testq/DEVICES_AUTHORIZED.
# Usage: DRY_RUN=1 bash scripts/device-install.sh
set -u
export JAVA_HOME="${JAVA_HOME:-/c/Users/Obliv/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot}"; export PATH="$JAVA_HOME/bin:$PATH:/c/Users/Obliv/.cargo/bin"
R=/z/GameSuite-wt-game-kit; Q=/z/GameSuite-testq; D="$Q/device"; mkdir -p "$D"
[ -f "$Q/DEVICES_AUTHORIZED" ] || { echo "devices not authorised"; exit 0; }
ADB=""; for c in /c/Users/Obliv/android-sdk/platform-tools/adb.exe /z/Android/Sdk/platform-tools/adb.exe; do [ -x "$c" ] && ADB="$c" && break; done
[ -n "$ADB" ] || { echo "adb not found"; exit 0; }
mkdir /tmp/gs-devinstall.lock 2>/dev/null || exit 0; trap 'rmdir /tmp/gs-devinstall.lock 2>/dev/null' EXIT
cd "$R" || exit 1
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "primary dirty; skip"; exit 0; }
head=$(git rev-parse HEAD); sh=$(git rev-parse --short HEAD)
green=""; for m in $(ls -1t "$Q"/green-* 2>/dev/null); do s=${m##*green-}; if git merge-base --is-ancestor "$s" HEAD 2>/dev/null; then green=$s; break; fi; done
[ -n "$green" ] || { echo "no green marker; skip"; exit 0; }
if [ "$green" != "$head" ] && git diff --name-only "$green..HEAD" | grep -qv '^docs/handoff/'; then echo "code after last green gate; skip"; exit 0; fi
serials=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
[ -n "$serials" ] || { echo "no devices attached"; exit 0; }
todo=""; for s in $serials; do [ "$(cat "$D/$s.last" 2>/dev/null)" = "$green" ] || todo="$todo $s"; done
[ -n "$todo" ] || { echo "all devices already on $green"; exit 0; }
[ -n "${DRY_RUN:-}" ] && { echo "would install $green on:$todo"; exit 0; }
mkdir /tmp/gs-device.lock 2>/dev/null || { echo "device lock busy; skip"; exit 0; }; trap 'rmdir /tmp/gs-device.lock /tmp/gs-devinstall.lock 2>/dev/null' EXIT
# build once, under the machine-wide gradle lock and the integrator lock (so the tree cannot change underneath the build)
until mkdir /tmp/gs-integrate.lock 2>/dev/null; do sleep 20; done
until mkdir /tmp/gs-gradle.lock 2>/dev/null; do sleep 15; done
./gradlew :app:assembleDebug --build-cache --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g -Pkotlin.compiler.execution.strategy=in-process > "$D/build-$green.log" 2>&1; brc=$?
rmdir /tmp/gs-gradle.lock /tmp/gs-integrate.lock 2>/dev/null
apk=$(ls -1t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
[ "$brc" -eq 0 ] && [ -n "$apk" ] || { echo "build=RED rc=$brc" > "$D/$green.result"; exit 0; }
for s in $todo; do
  fg=$("$ADB" -s "$s" shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 mCurrentFocus)
  awake=$("$ADB" -s "$s" shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m1 -oE 'mWakefulness=[A-Za-z]+')
  if echo "$fg" | grep -q "com.gamesuite" && [ "$awake" = "mWakefulness=Awake" ]; then echo "$s: GameSuite in use; deferred" >> "$D/$green.result"; continue; fi
  out=$("$ADB" -s "$s" install -r -t "$apk" 2>&1 | tr -d '\r' | tail -3)
  case "$out" in
    *Success*)
      "$ADB" -s "$s" logcat -c 2>/dev/null
      "$ADB" -s "$s" shell monkey -p com.gamesuite -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 25
      crash=$("$ADB" -s "$s" logcat -d -b crash 2>/dev/null | tr -d '\r' | grep -c "FATAL EXCEPTION")
      "$ADB" -s "$s" exec-out screencap -p > "$D/$s-$green.png" 2>/dev/null
      v=GREEN; [ "$crash" -gt 0 ] && v=RED
      echo "$s: installed $green; launch smoke=$v crashes=$crash screenshot=$D/$s-$green.png" >> "$D/$green.result"; echo "$green" > "$D/$s.last" ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_FAILED_VERSION_DOWNGRADE*|*INSTALL_FAILED*)
      echo "$s: install refused (${out//$'\n'/ }); left untouched, NOT uninstalling" >> "$D/$green.result" ;;
    *) echo "$s: install failed: ${out//$'\n'/ }" >> "$D/$green.result" ;;
  esac
done

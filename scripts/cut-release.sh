#!/usr/bin/env bash
# Detached release cutter (owner authorised 2026-11-22: cutting, pushing and bumping releases must continue past usage limits).
# Cuts a SIDELOAD prerelease: bumps versionCode/versionName in app/build.gradle.kts, commits, tags wave3-<versionCode>-<shortsha>, plain-pushes.
# The GitHub workflow .github/workflows/sideload.yml builds and publishes the APK from that tag. Never touches Play, never uses `main`.
# Preconditions (all must hold): primary worktree clean on session/wave-3; HEAD == origin/session/wave-3 (nothing unpushed);
# the newest green-gate marker (/z/GameSuite-testq/green-<sha>, written by integrate-green.sh) is HEAD or an ancestor with only docs/handoff changes since;
# code actually changed since the last wave3-* tag; at least MIN_HOURS (default 6) since the last cut. Never force-push, never delete or move tags.
# Usage: DRY_RUN=1 bash scripts/cut-release.sh
set -u
PRIMARY=/z/GameSuite-wt-game-kit; BASE=session/wave-3; MIN_HOURS="${MIN_HOURS:-6}"
LOG="$PRIMARY/docs/handoff/RELEASE-LOG.md"; GRADLE_FILE=app/build.gradle.kts
lock=/tmp/gs-cut.lock; mkdir "$lock" 2>/dev/null || exit 0; trap 'rmdir "$lock" 2>/dev/null' EXIT
mkdir /tmp/gs-integrate.lock 2>/dev/null || { echo "integrator running; skip"; exit 0; }; trap 'rmdir /tmp/gs-integrate.lock "$lock" 2>/dev/null' EXIT
cd "$PRIMARY" || exit 1
say(){ echo "[$(date '+%F %T')] $*"; }
note(){ [ -f "$LOG" ] || printf '# Release log (scripts/cut-release.sh)\n\n' > "$LOG"; printf -- '- %s: %s\n' "$(date '+%F %H:%M')" "$*" >> "$LOG"; }
git fetch -q origin --tags 2>/dev/null || true
[ "$(git rev-parse --abbrev-ref HEAD)" = "$BASE" ] || { say "not on $BASE"; exit 0; }
[ -z "$(git status --porcelain --untracked-files=no)" ] || { say "dirty tree; skip"; exit 0; }
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/$BASE)" ] || { say "HEAD differs from origin/$BASE; skip"; exit 0; }
head=$(git rev-parse HEAD)
# newest green marker that is HEAD or an ancestor of HEAD
green=""; for m in $(ls -1t /z/GameSuite-testq/green-* 2>/dev/null); do s=${m##*green-}; if git merge-base --is-ancestor "$s" HEAD 2>/dev/null; then green=$s; break; fi; done
[ -n "$green" ] || { say "no green gate marker on this history; skip"; exit 0; }
if [ "$green" != "$head" ] && git diff --name-only "$green..HEAD" | grep -qv '^docs/handoff/'; then say "commits after the last green gate touch code; skip"; exit 0; fi
last=$(git tag -l 'wave3-*' --sort=-creatordate | head -1)
if [ -n "$last" ]; then
  git diff --quiet "$last..HEAD" -- app shared rust gradle build.gradle.kts settings.gradle.kts gradle.properties && { say "no code change since $last; skip"; exit 0; }
  age=$(( $(date +%s) - $(git log -1 --format=%ct "$last") ))
  [ "$age" -ge $(( MIN_HOURS*3600 )) ] || { say "last release $last is under ${MIN_HOURS}h old; skip"; exit 0; }
fi
vc=$(grep -E '^\s*versionCode\s*=' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+'); [ -n "$vc" ] || { note "cannot read versionCode"; exit 0; }
vn=$(grep -E '^\s*versionName\s*=' "$GRADLE_FILE" | head -1 | grep -oE '"[^"]+"' | tr -d '"')
new=$((vc+1)); maj=$(echo "$vn" | cut -d. -f1); min=$(echo "$vn" | cut -d. -f2); pat=$(echo "$vn" | cut -d. -f3); pat=$(( ${pat:-0}+1 )); newvn="$maj.$min.$pat"
say "cut: versionCode $vc -> $new, versionName $vn -> $newvn"
[ -n "${DRY_RUN:-}" ] && exit 0
# bump (preserve CRLF): replace only the numeric/string, in place via sed on a copy then move
sed -E -i "0,/versionCode\s*=\s*[0-9]+/s//versionCode = $new/; 0,/versionName\s*=\s*\"[^\"]+\"/s//versionName = \"$newvn\"/" "$GRADLE_FILE"
git diff --stat -- "$GRADLE_FILE" | tail -1
git commit -q -m "Release: bump to versionCode $new / $newvn (auto, after green gate)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -- "$GRADLE_FILE" || { git checkout -q -- "$GRADLE_FILE"; note "commit failed"; exit 0; }
sha=$(git rev-parse --short HEAD); tag="wave3-$new-$sha"
git tag -a "$tag" -m "Sideload prerelease $newvn (versionCode $new), auto-cut after green gate" || { note "tag failed"; exit 0; }
if git push origin "$BASE" >/tmp/gs-cut-push.out 2>&1 && git push origin "$tag" >>/tmp/gs-cut-push.out 2>&1; then
  note "CUT $tag ($newvn, versionCode $new) from green $green; pushed commit and tag; GitHub builds the APK"
  git add "$LOG" && git commit -q -m "Release log: $tag" -- "$LOG" 2>/dev/null && git push origin "$BASE" >/dev/null 2>&1
else
  note "push failed for $tag (see /tmp/gs-cut-push.out); local commit and tag kept, not retried blindly"
fi

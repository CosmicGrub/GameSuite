#!/usr/bin/env bash
# Limit-proof integrator: needs NO Claude session. Merges finished session/* branches into
# session/wave-3 when (and only when) the full unit-test gate is green, then plain-pushes.
# Rule (owner, 2026-11-22): green work merges and ships regardless of usage limits.
#
# Safety: never force-pushes, never touches main, never rewrites pushed history, aborts any
# conflicting merge, refuses to run in a dirty primary worktree, uses the machine-wide gradle lock.
#
# Usage: bash scripts/integrate-green.sh            (real run)
#        DRY_RUN=1 bash scripts/integrate-green.sh  (report only)
set -u
# Gradle needs JDK 17 (CLAUDE.md); a scheduled task has no interactive profile.
export JAVA_HOME="${JAVA_HOME:-/c/Users/Obliv/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot}"
export PATH="$JAVA_HOME/bin:$PATH:/c/Users/Obliv/.cargo/bin"
PRIMARY="/z/GameSuite-wt-game-kit"
BASE="session/wave-3"
LOG_DIR="$PRIMARY/docs/handoff"
LOGFILE="$LOG_DIR/INTEGRATOR-LOG.md"
RUNLOCK="/tmp/gs-integrate.lock"
GRADLE_LOCK="/tmp/gs-gradle.lock"
# Branches this script may integrate (add new ones to docs/handoff/INTEGRATE-BRANCHES.txt, one per line).
LIST="$PRIMARY/docs/handoff/INTEGRATE-BRANCHES.txt"

say() { echo "[$(date '+%F %T')] $*"; }
note() { printf -- '- %s: %s\n' "$(date '+%F %H:%M')" "$*" >> "$LOGFILE"; }

mkdir "$RUNLOCK" 2>/dev/null || { say "another integrator run is active; exiting"; exit 0; }
trap 'rmdir "$RUNLOCK" 2>/dev/null; [ -n "${EXPANDED:-}" ] && rm -f "$EXPANDED"' EXIT

cd "$PRIMARY" || exit 1
[ -f "$LOGFILE" ] || printf '# Integrator log (scripts/integrate-green.sh)\n\n' > "$LOGFILE"

free_gb=$(df -BG /z | awk 'NR==2{gsub("G","",$4);print $4}')
if [ "${free_gb:-0}" -lt 4 ]; then note "stopped: only ${free_gb}G free on Z:"; exit 0; fi
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then note "skipped: primary worktree has uncommitted changes"; exit 0; fi
[ "$(git rev-parse --abbrev-ref HEAD)" = "$BASE" ] || { note "skipped: primary worktree not on $BASE"; exit 0; }
[ -f "$LIST" ] || { note "no branch list at $LIST"; exit 0; }

git fetch -q origin 2>/dev/null || true

# ---- expand patterns (e.g. session/cloud-*) and sync local branches from origin (cloud agents push there) ----
EXPANDED=$(mktemp)
while IFS= read -r ent; do
  ent="${ent%%#*}"; ent="$(echo "$ent" | tr -d '[:space:]')"
  [ -z "$ent" ] && continue
  case "$ent" in
    *\**) git for-each-ref --format='%(refname:short)' "refs/remotes/origin/${ent}" | sed 's#^origin/##' >> "$EXPANDED" ;;
    *) echo "$ent" >> "$EXPANDED" ;;
  esac
done < "$LIST"
while IFS= read -r br; do
  git rev-parse --verify -q "refs/remotes/origin/$br" >/dev/null || continue
  if ! git rev-parse --verify -q "refs/heads/$br" >/dev/null; then git branch -q "$br" "origin/$br"; continue; fi
  # fast-forward the local branch to origin if it is strictly behind (never overwrite local-only commits)
  if git merge-base --is-ancestor "refs/heads/$br" "refs/remotes/origin/$br" 2>/dev/null; then
    wt=$(git worktree list --porcelain | awk -v b="refs/heads/$br" '$1=="worktree"{p=$2} $1=="branch"&&$2==b{print p}')
    if [ -n "$wt" ]; then git -C "$wt" merge -q --ff-only "origin/$br" 2>/dev/null || true
    else git branch -q -f "$br" "origin/$br"; fi
  fi
done < "$EXPANDED"
LIST="$EXPANDED"
start_sha=$(git rev-parse HEAD)
merged=()

while IFS= read -r br; do
  br="${br%%#*}"; br="$(echo "$br" | tr -d '[:space:]')"
  [ -z "$br" ] && continue
  git rev-parse --verify -q "refs/heads/$br" >/dev/null || { say "$br: no such branch"; continue; }
  ahead=$(git rev-list --count "$BASE..$br")
  [ "$ahead" -eq 0 ] && { say "$br: nothing new"; continue; }
  # skip branches whose only extra commit is the paused WIP snapshot (unverified)
  if [ "$ahead" -eq 1 ] && git log -1 --format=%s "$br" | grep -q "NOT compiled or verified together"; then
    say "$br: only a paused WIP snapshot; skipping"; continue
  fi
  # skip if the branch tip itself says WIP-unverified, or its worktree is dirty (agent mid-edit)
  wt=$(git worktree list --porcelain | awk -v b="refs/heads/$br" '$1=="worktree"{p=$2} $1=="branch"&&$2==b{print p}')
  if [ -n "$wt" ] && [ -n "$(git -C "$wt" status --porcelain --untracked-files=no)" ]; then
    say "$br: worktree dirty (work in progress); skipping this round"; continue
  fi
  if [ -n "${DRY_RUN:-}" ]; then say "$br: $ahead commit(s) ahead; would merge (dry run)"; continue; fi
  say "merging $br ($ahead commits)"
  if ! git merge --no-ff -m "Merge $br (auto-integrated after green gate)" "$br" >/tmp/gs-merge.out 2>&1; then
    git merge --abort 2>/dev/null; note "$br: merge conflict, deferred (needs a Claude integrator or a human)"; continue
  fi
  merged+=("$br")
done < "$LIST"

if [ "${#merged[@]}" -eq 0 ]; then say "nothing merged"; exit 0; fi

# ---- full gate ----
say "running full gate"
until mkdir "$GRADLE_LOCK" 2>/dev/null; do sleep 15; done
./gradlew :app:testDebugUnitTest --build-cache --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx5g -Pkotlin.compiler.execution.strategy=in-process \
  > /tmp/gs-integrate-gate.log 2>&1
gate_rc=$?
rmdir "$GRADLE_LOCK" 2>/dev/null

read -r classes tests fails errs < <(python - <<'PY'
import glob,re
c=t=f=e=0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"',open(p,encoding='utf8').read(800))
    if m: c+=1;t+=int(m[1]);f+=int(m[3]);e+=int(m[4])
print(c,t,f,e)
PY
)
# a truncated run (memory crash) shows far fewer tests than the last known-good baseline
BASELINE_TESTS=${BASELINE_TESTS:-7200}
if [ "$gate_rc" -ne 0 ] || [ "${fails:-1}" -ne 0 ] || [ "${errs:-1}" -ne 0 ] || [ "${tests:-0}" -lt "$BASELINE_TESTS" ]; then
  git reset -q --hard "$start_sha"
  note "gate NOT green for [${merged[*]}] (rc=$gate_rc classes=$classes tests=$tests failures=$fails errors=$errs); merges rolled back locally, nothing pushed"
  exit 0
fi

if git push origin "$BASE" >/tmp/gs-push.out 2>&1; then
  for br in "${merged[@]}"; do git push origin "$br" >/dev/null 2>&1 || true; done
  note "GREEN and pushed: merged [${merged[*]}]; classes=$classes tests=$tests failures=0 errors=0"
  git add "$LOGFILE" && git commit -q -m "Integrator log: merged ${merged[*]} after green gate" -- "$LOGFILE" 2>/dev/null && git push origin "$BASE" >/dev/null 2>&1
else
  note "gate green but push failed (see /tmp/gs-push.out); merges kept locally"
fi

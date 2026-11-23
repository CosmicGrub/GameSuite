#!/usr/bin/env bash
# Detached AGENT runner: after usage limits reset (or whenever a job's worktree has gone quiet) it resumes unfinished jobs by launching
# headless Claude (`claude -p`) in the job's worktree. Needs no desktop app and no open Claude session; run by a Windows scheduled task.
# While a usage limit is active the launched run just fails fast and is retried next cycle.
# Guards: skips finished jobs (done marker), jobs whose worktree changed in the last STALE_MIN minutes (a live agent is probably working),
# jobs with queued/running test-queue work, jobs already launched by this runner (pid file), and caps concurrent runner agents.
# Usage: DRY_RUN=1 bash scripts/agent-runner.sh
set -u
export PATH="$PATH:/c/Users/Obliv/.local/bin"
PRIMARY=/z/GameSuite-wt-game-kit
LIST="$PRIMARY/docs/handoff/AGENT-JOBS.txt"
Q=/z/GameSuite-testq; RUN=/z/GameSuite-agentrun
STALE_MIN="${STALE_MIN:-120}"; MAX_LIVE="${MAX_LIVE:-3}"
mkdir -p "$RUN/pids" "$RUN/logs" "$Q/pending" "$Q/running"
mkdir "$RUN/launch.lock" 2>/dev/null || exit 0; trap 'rmdir "$RUN/launch.lock" 2>/dev/null' EXIT
command -v claude >/dev/null || { echo "claude CLI not found"; exit 0; }
live=0
for f in "$RUN"/pids/*.pid; do [ -f "$f" ] || continue; if kill -0 "$(cat "$f")" 2>/dev/null; then live=$((live+1)); else rm -f "$f"; fi; done
# pass 1: which groups are busy (any worktree in the group recently active or with a test job in flight)?
busy=" "
while IFS='|' read -r name wt br scope grp needs; do
  case "$name" in ''|\#*) continue ;; esac
  [ -d "$wt" ] || continue
  if [ -n "$(find "$wt" -path "$wt/.git" -prune -o -type f -mmin -"$STALE_MIN" -print -quit 2>/dev/null)" ] || grep -lq "$wt" "$Q"/pending/*.job "$Q"/running/*.job 2>/dev/null || [ -f "$RUN/pids/$name.pid" ]; then busy="$busy$grp "; fi
done < "$LIST"
while IFS='|' read -r name wt br scope grp needs; do
  case "$name" in ''|\#*) continue ;; esac
  case "$busy" in *" $grp "*) echo "$name: group $grp busy; skip"; continue ;; esac
  ok=1; for n in $(echo "${needs:-}" | tr ',' ' '); do [ -f "$PRIMARY/docs/handoff/done/$n.done" ] || ok=0; done
  [ "$ok" -eq 1 ] || { echo "$name: waiting on ${needs}; skip"; continue; }
  if [ ! -d "$wt" ]; then
    [ -n "${DRY_RUN:-}" ] && { echo "$name: would create worktree $wt"; continue; }
    free=$(df -BG /z | awk 'NR==2{gsub("G","",$4);print $4}'); [ "${free:-0}" -ge 8 ] || { echo "$name: low disk; skip"; continue; }
    git -C "$PRIMARY" worktree add -q -b "$br" "$wt" session/wave-3 2>/dev/null || git -C "$PRIMARY" worktree add -q "$wt" "$br" || continue
    cp "$PRIMARY/local.properties" "$wt/" 2>/dev/null; [ -d "$wt/rust/target" ] || cp -r "$PRIMARY/rust/target" "$wt/rust/target" 2>/dev/null
  fi
  [ -f "$RUN/pids/$name.pid" ] && continue
  [ -f "$wt/docs/handoff/done/$name.done" ] && continue
  [ "$live" -ge "$MAX_LIVE" ] && break
  if [ -n "$(find "$wt" -path "$wt/.git" -prune -o -type f -mmin -"$STALE_MIN" -print -quit 2>/dev/null)" ]; then echo "$name: recently active; skip"; continue; fi
  if grep -lq "$wt" "$Q"/pending/*.job "$Q"/running/*.job 2>/dev/null; then echo "$name: test job in flight; skip"; continue; fi
  echo "$name: resuming headless"
  [ -n "${DRY_RUN:-}" ] && continue
  prompt="$RUN/logs/$name.prompt.txt"
  cat > "$prompt" <<P
You are resuming the GameSuite job '$name' (branch $br) in THIS worktree ($wt), headless and unattended, after the previous agent was cut off.
Scope: $scope. First read docs/handoff/AGENT-BRIEFS.md (COMMON RULES apply in full, including: run tests ONLY through scripts/testq-submit.sh and read results from /z/GameSuite-testq/done/, no device access of any kind, plain push of your own branch only, never force, never main, never merge into session/wave-3, original art/names only) and docs/handoff/RESUME-2026-11-22.md and docs/handoff/QUEUE.md.
Then: git merge session/wave-3 here, look at git log session/wave-3..HEAD, git status, and the branch's integration-notes.md / docs to see what is done vs open. Do NOT redo finished work, and do NOT trust any note claiming tests are green unless a testq .result file confirms it. Continue the job, commit small and often (path-scoped, trailer 'Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>'), push with a plain 'git push origin $br' when targeted tests are green. When the whole scope is complete and verified, create docs/handoff/done/$name.done (one line summary), commit and push it. End with a short plain report.
P
  ( cd "$wt" && nohup claude -p --allowedTools "Bash,Read,Write,Edit,Glob,Grep" \
      --disallowedTools "Bash(git push --force*)" "Bash(git push -f*)" "Bash(git push origin main*)" "Bash(git push origin session/wave-3*)" "Bash(adb*)" "Bash(*connectedAndroidTest*)" "Bash(*installDebug*)" \
      --max-turns 250 < "$prompt" > "$RUN/logs/$name.log" 2>&1 & echo $! > "$RUN/pids/$name.pid" )
  live=$((live+1))
done < "$LIST"

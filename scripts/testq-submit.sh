#!/usr/bin/env bash
# Usage: bash scripts/testq-submit.sh <worktree-path> <job-name> [gradle test args, e.g. --tests 'com.gamesuite.games.foo.*']
# Queues a test run in the detached worker (survives usage limits). Prints the result path to poll.
set -u
Q="${TESTQ_DIR:-/z/GameSuite-testq}"
if [ "${1:-}" = "--device" ]; then
  shift; wt="$1"; name="$2"; cmd="$3"
  mkdir -p "$Q/pending" "$Q/done"; id="$(date +%Y%m%d-%H%M%S)-$name"
  printf 'DEVICE:%s\n%s\n' "$wt" "$cmd" > "$Q/pending/$id.job"
  echo "queued device job: $id (held until $Q/DEVICES_AUTHORIZED exists)   result: $Q/done/$id.result"; exit 0
fi
wt="$1"; name="$2"; shift 2
mkdir -p "$Q/pending" "$Q/done"
id="$(date +%Y%m%d-%H%M%S)-$name"
{ echo "$wt"; for a in "$@"; do printf "%q\n" "$a"; done; } > "$Q/pending/$id.job"
echo "queued: $id   result: $Q/done/$id.result   log: $Q/logs/$id.log"

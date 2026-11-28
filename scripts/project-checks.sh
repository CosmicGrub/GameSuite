#!/usr/bin/env bash
# Whole-project checks beyond the Kotlin unit gate: Rust workspace tests and the relay server tests. Detached, no Claude needed.
# Runs under the machine-wide gradle lock (so it never overlaps a build or the gate) in a throwaway copy-free way: it only reads the repo.
# Result summary: /z/GameSuite-testq/project-checks.result (read by the planner and humans); full log alongside.
set -u
export PATH="$PATH:/c/Users/Obliv/.cargo/bin:/c/Program Files/nodejs"
R=/z/GameSuite-wt-game-kit; Q=/z/GameSuite-testq; mkdir -p "$Q"
mkdir /tmp/gs-checks.lock 2>/dev/null || exit 0; trap 'rmdir /tmp/gs-checks.lock 2>/dev/null' EXIT
until mkdir /tmp/gs-gradle.lock 2>/dev/null; do sleep 20; done; trap 'rmdir /tmp/gs-gradle.lock /tmp/gs-checks.lock 2>/dev/null' EXIT
LOG="$Q/project-checks.log"; : > "$LOG"
sha=$(git -C "$R" rev-parse --short HEAD)
( cd "$R/rust" && cargo test --workspace --locked ) >> "$LOG" 2>&1; rust_rc=$?
if [ -d "$R/server" ]; then ( cd "$R/server" && npm ci --no-audit --no-fund && npm test ) >> "$LOG" 2>&1; srv_rc=$?; else srv_rc=0; fi
v=GREEN; [ "$rust_rc" -ne 0 ] || [ "$srv_rc" -ne 0 ] && v=RED
{ echo "checked=$(date '+%F %T')"; echo "commit=$sha"; echo "verdict=$v"; echo "rust_rc=$rust_rc"; echo "server_rc=$srv_rc"; echo "log=$LOG"; } > "$Q/project-checks.result"

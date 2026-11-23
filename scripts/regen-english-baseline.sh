#!/usr/bin/env bash
# Regenerate app/src/test/resources/english-literal-baseline.txt (the hard-coded-English ratchet).
# Run after moving screen strings to resources; the counts may only go down.
set -euo pipefail
cd "$(dirname "$0")/.."
ENGLISH_BASELINE_REGEN=1 ./gradlew :app:testDebugUnitTest --tests '*EnglishLiteralRatchetTest*' --build-cache "$@"

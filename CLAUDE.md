# GameSuite: notes for Claude Code sessions

## Before you touch anything

Several sessions often work on this repo at once, and a shared checkout has bitten us (see
`docs/PARALLEL_SESSIONS.md`). Run `bash scripts/session-worktree.sh list` first. If a checkout has
uncommitted changes you didn't make, don't build, commit, or stash in it. Make your own with
`bash scripts/session-worktree.sh new <name>` and work from that directory.

- Commit and push only when the user asks.
- Path-scope commits (`git commit <paths>`); never a bare `git commit` or `git add -A` in a
  checkout another session might have staged files in.
- Never use bare `git stash`; the stash is shared across worktrees. Use a WIP commit.

## Commands

| What | Command |
| --- | --- |
| Kotlin unit tests | `./gradlew :app:testDebugUnitTest :shared:allTests` |
| Debug APK | `./gradlew :app:assembleDebug` |
| Rust tests | `cd rust && cargo test --workspace --locked` |
| Relay server tests | `cd server && npm install && npm test` |

CI (`.github/workflows/android.yml`) runs the first three on pull requests and pushes to `main`.

## Environment

- JDK 17. compileSdk/targetSdk 34, minSdk 26. `local.properties` (gitignored) supplies
  `sdk.dir`; `session-worktree.sh new` copies it into new worktrees.
- Rust libraries (`rust/gamesuite-sim`, `rust/gamesuite-audio`) are built by Gradle `Exec`
  tasks in `app/build.gradle.kts`: cross-compiled with **cargo-ndk 4.1.2** against **NDK
  28.2.13676358** for `arm64-v8a`, `armeabi-v7a`, `x86_64`. Point at the NDK with
  `ANDROID_NDK_HOME` or `-PandroidNdkHome=<path>`; the fallback is a path on the maintainer's
  Windows machine. Needs the rustup targets `aarch64-linux-android`,
  `armv7-linux-androideabi`, `x86_64-linux-android`.
- The JVM tests load the host build of the Rust libs from `rust/target/release/`. Don't point
  `CARGO_TARGET_DIR` elsewhere.

## Gotchas

- One Gradle build per checkout at a time. `Unable to delete directory ... binary` or odd Kotlin
  incremental-compile errors mean a concurrent build or a corrupt cache; deleting
  `app/build/kotlin` and `shared/build/kotlin` (or running `./gradlew clean` in your own
  checkout) clears the latter.
- CI runs on Linux, where file names are case-sensitive and the tree checks out with LF
  endings. A change that only "works" on Windows can fail there.

# Parallel sessions: one session, one worktree, one branch

This repo is regularly worked on by more than one Claude Code session at the same time (plus
the human). Sharing a single checkout between them has already gone wrong in three distinct
ways, all from the same root cause: **one checkout is one working tree, one git index, and one
set of build directories.**

| What is shared | What went wrong |
| --- | --- |
| Working tree | One session's half-finished edit broke the other's build. A single missing `}` produced ~40 misleading compiler errors, including phantom cross-file type errors. |
| Git index | A bare `git commit` swept in files the other session had staged: 1,472 lines of unrelated work committed under the wrong message. It was undone with `git reset --soft HEAD~1`, but only because it was noticed. |
| Build directories | Concurrent Gradle runs collided: `Unable to delete directory ... test-results ... binary`, `Gradle build daemon has been stopped`, and corrupted Kotlin incremental caches. |

A [git worktree](https://git-scm.com/docs/git-worktree) fixes all three. Each worktree has its
own working tree, its own index, and its own `build/` and `rust/target/`, while sharing the
repository's history and objects, so it costs a checkout rather than a clone.

## Workflow

```bash
bash scripts/session-worktree.sh new <name>     # creates ../GameSuite-wt-<name> on branch session/<name>
cd ../GameSuite-wt-<name>                        # start your Claude Code / editor session HERE
# ...work and commit on session/<name>...
git push -u origin session/<name>
gh pr create                                     # opening the PR is what starts CI (see "CI" below)
```

After the PR is merged, clean up **from outside the worktree** (for example from the primary
checkout). Windows cannot delete a directory that a shell is sitting in, so the script refuses
to run `done` from inside the worktree it would remove:

```bash
cd ../GameSuite
bash scripts/session-worktree.sh done <name>
```

`new` branches from local `main` (pass a second argument to branch from something else) and
copies the gitignored `local.properties`, which the Android build needs and a fresh worktree
otherwise lacks. Uncommitted changes in the primary checkout are deliberately **not** carried
over.

`bash scripts/session-worktree.sh list` shows every worktree with its uncommitted-file count
and how many commits it is ahead of `main`. Run it before starting work: a non-zero `DIRTY`
on a checkout you didn't touch means another session is mid-edit there. (It reads other
worktrees with `--no-optional-locks`, so looking never blocks their `git add` or `commit`.)

### What `done` refuses, and the flags that override it

| Situation | Result |
| --- | --- |
| Uncommitted changes | Refused. Commit them (a WIP commit is fine). |
| Ignored files other than build outputs (`build/`, `.gradle/`, `.kotlin/`, `target/`, `node_modules/`, `jniLibs/`, `local.properties`) | Refused, and the files are listed, because removing the worktree would delete them. |
| Branch has commits not in `main` or `origin/main` | Refused. `--unmerged` overrides this one check, for a **squash-merged** PR (whose commits never appear in `main`) or an abandoned branch. A dirty tree still blocks it. |
| The worktree at that path is on another branch, or a detached HEAD | Always refused, even with `--force`. Use `git worktree remove` yourself if you really mean it. |
| Directory already deleted behind git's back | Cleaned up: the stale registration is pruned and the branch removed under the same merged check. |
| Anything, with `--force` | Discards uncommitted changes, ignored files, and unmerged commits. |

## Landing your work

Work reaches `main` through the pull request, merged on GitHub. Nothing in the worktree flow
merges locally. Afterwards, `git pull --ff-only` in the primary checkout brings `main` up to
date, but only if that checkout is clean and yours to touch (Rule 1). If it isn't, skip the
pull: `done` also accepts a branch that is contained in `origin/main`, once a `git fetch` has
updated it. A squash-merge never satisfies either check; use `done <name> --unmerged` for that.

To bring a branch up to date with `main` while you work: `git fetch origin && git rebase origin/main`.

## Rules

1. **Never commit, stash, or build in a checkout you don't exclusively own.** If `list` shows
   the primary checkout dirty and the changes aren't yours, leave it alone and use a worktree.
2. **Path-scope commits** (`git commit <paths>`) if you ever must commit in a shared checkout;
   never a bare `git commit` or `git add -A`.
3. **No bare `git stash`.** The stash is shared by every worktree, so a `pop` can apply another
   session's work. Make a WIP commit instead.
4. **One Gradle build per checkout at a time.** Separate worktrees can build concurrently.
5. **Don't share `CARGO_TARGET_DIR` across worktrees.** The JVM tests load the host Rust library
   from `rust/target/release/` (hardcoded in `app/build.gradle.kts`).
6. Git allows a branch to be checked out in only one worktree, so two sessions can't both sit
   on `main`. That is the point: branch off it.

## Cost

Every worktree builds from cold: Gradle outputs, the Kotlin incremental caches, and a full
Rust `target/` for the host plus three Android ABIs. Measured on a first cold build of the
exact command CI runs (`:app:testDebugUnitTest :shared:allTests :app:assembleDebug`, about 7.5
minutes): roughly **9 MB** of checkout plus **1.4 GB** of build output (`rust/target` about
1.2 GB, `app/build` about 160 MB). Release, lint, or instrumented builds add more. Gradle's
dependency cache and the cargo registry live in your home directory and are shared between
worktrees, not duplicated. Delete finished worktrees with `done`; `new` warns when the target
drive has under 4 GB free.

## Windows notes

- `done` can fail because a Gradle daemon or an IDE holds files open in that worktree. Check
  `./gradlew --status` first. `./gradlew --stop` stops **every** Gradle 8.13 daemon for your
  user, busy ones included, so it will kill another session's build in progress. Only run it
  when nothing else is `BUSY`; otherwise close the IDE or shell that has the worktree open.
- Worktrees are siblings of the primary checkout (`Z:\GameSuite-wt-<name>`) to keep paths
  short. Set `GAMESUITE_WORKTREE_ROOT` to place them elsewhere.
- `.claude/worktrees/` (gitignored) is used by Claude Code's own agent worktrees and is
  unrelated to these.

## CI

`.github/workflows/android.yml` runs on pushes to `main` and on pull requests that touch the
app or the Rust cores. A plain push of `session/<name>` does **not** start it; opening a PR does.
Runs can also be started by hand from the Actions tab, but only once the workflow file exists on
`main`. It runs `cargo test` for `rust/`, then the JVM unit tests for `:app` and `:shared` and
assembles the debug APK. The header comment in the workflow lists what it intentionally does not
run yet and why.

Each successful run uploads its debug APK as an artifact. CI signs it with a throwaway debug
key, so it **cannot be installed over a build made on your machine** (or over another CI
build) without uninstalling first, which erases the app's data. Only sideload APKs from your own
branches.

`server/` has its own workflow, `relay-server.yml`.

Note for later: both workflows use `paths:` filters. If you ever make one a *required* status
check in branch protection, a PR that changes none of those paths will never report the check
and will be unmergeable. Drop the filters at that point.

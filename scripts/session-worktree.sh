#!/usr/bin/env bash
# Per-session git worktree helper. See docs/PARALLEL_SESSIONS.md for the why.
#
#   bash scripts/session-worktree.sh new <name> [base]   create ../<repo>-wt-<name> on branch session/<name>
#   bash scripts/session-worktree.sh list                every worktree, with dirty/ahead state
#   bash scripts/session-worktree.sh done <name> [--unmerged | --force]
#                                                        remove the worktree and its branch
#
# Run `done` from OUTSIDE the worktree being removed (Windows cannot delete a directory that a
# shell is sitting in). Works from the primary checkout or any other linked worktree.
# Requires git + bash (Git Bash on Windows).
set -euo pipefail

BRANCH_PREFIX="session/"
MIN_FREE_GB=4            # a cold build adds ~1.4 GB (Rust target/ + Gradle outputs); leave headroom for temp files
DEFAULT_BASE="main"
# Gitignored, per-machine files a new worktree needs before it can build.
BOOTSTRAP_FILES=(local.properties)
# Ignored paths a worktree is expected to accumulate. Any OTHER ignored file makes `done` stop and
# ask for --force, because removing the worktree would silently delete it.
EXPECTED_IGNORED_RE='(^|/)(build|\.gradle|\.kotlin|\.cxx|target|node_modules|jniLibs)(/|$)|(^|/)local\.properties$'
SELF="bash scripts/session-worktree.sh"

die()  { echo "error: $*" >&2; exit 1; }
warn() { echo "warning: $*" >&2; }

# The first entry of `git worktree list` is always the primary checkout, whichever worktree we run from.
primary_root() {
    git worktree list --porcelain | awk '/^worktree /{ sub(/^worktree /, ""); print; exit }'
}

# Lexically collapse '.', '..', duplicate slashes and backslashes. Touches no filesystem, so it works
# for directories that do not exist yet. Keeps a leading '/' or drive letter ("Z:/") as the root.
normalize_path() {
    local p="${1//\\//}" prefix="" seg
    local -a out=()
    if [[ "$p" =~ ^([A-Za-z]:)(/.*)?$ ]]; then
        prefix="${BASH_REMATCH[1]}"
        p="${BASH_REMATCH[2]:-}"
    fi
    [[ "$p" == /* ]] && prefix="${prefix}/"
    local IFS=/
    for seg in $p; do
        case "$seg" in
            ""|.) ;;
            ..)   [[ ${#out[@]} -eq 0 ]] || unset "out[$((${#out[@]} - 1))]" ;;
            *)    out+=("$seg") ;;
        esac
    done
    echo "${prefix}${out[*]:-}"
}

# Sibling directory name: <primary-dir-name>-wt-<name>, next to the primary checkout.
# GAMESUITE_WORKTREE_ROOT overrides the parent directory. A relative value is resolved against the
# PRIMARY checkout, never the caller's cwd, so `new` and `done` agree no matter which worktree runs them.
worktree_path() {
    local primary parent override="${GAMESUITE_WORKTREE_ROOT:-}"
    primary="$(primary_root)"
    if [[ -n "$override" ]]; then
        override="${override//\\//}"
        if [[ "$override" != /* && ! "$override" =~ ^[A-Za-z]:/ ]]; then
            override="$primary/$override"
        fi
        parent="$(normalize_path "$override")"
    else
        parent="$(dirname "$primary")"
    fi
    echo "$parent/$(basename "$primary")-wt-$1"
}

# The reason a registered worktree was locked (or "no reason given"); prints nothing if it is not locked.
lock_reason() {
    git worktree list --porcelain | awk -v want="worktree $1" '
        $0 == want { inrec = 1; next }
        /^$/       { inrec = 0 }
        inrec && /^locked/ { r = $0; sub(/^locked ?/, "", r); print (r == "" ? "no reason given" : r); exit }'
}

valid_name() { [[ "$1" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; }

# --no-optional-locks: never take another session's index.lock just to look at its status.
dirty_lines() { git --no-optional-locks -C "$1" status --porcelain; }

is_registered() { git worktree list --porcelain | grep -Fxq "worktree $1"; }

cmd_new() {
    local name="${1:-}" base="${2:-$DEFAULT_BASE}"
    [[ -n "$name" ]] || die "usage: $SELF new <name> [base]"
    valid_name "$name" || die "name must be lowercase letters, digits, '.', '_' or '-' (got '$name')"

    local branch="${BRANCH_PREFIX}${name}" path primary
    path="$(worktree_path "$name")"
    primary="$(primary_root)"

    [[ ! -e "$path" ]] || die "'$path' already exists"
    ! git show-ref --verify --quiet "refs/heads/$branch" \
        || die "branch '$branch' already exists (pick another name, or run: $SELF done $name)"
    git rev-parse --verify --quiet "${base}^{commit}" >/dev/null || die "unknown base '$base'"

    # df needs a path that exists. On Git Bash the parent of Z:/repo-wt-x is the bare "Z:", which df
    # rejects, so walk up to the first existing ancestor and give df a trailing slash.
    local probe free_gb next
    probe="$(dirname "$path")"
    while [[ ! -d "$probe" ]]; do
        next="$(dirname "$probe")"
        [[ "$next" != "$probe" ]] || break
        probe="$next"
    done
    free_gb="$(df -Pk "${probe%/}/" 2>/dev/null | awk 'NR==2 { printf "%d", $4 / 1024 / 1024 }')" || free_gb=""
    if [[ -z "$free_gb" ]]; then
        warn "could not determine free disk space under '$probe'; a cold build adds about 1.4 GB"
    elif [[ "$free_gb" -lt "$MIN_FREE_GB" ]]; then
        warn "only ${free_gb} GB free on the target drive; a cold build in a new worktree adds about 1.4 GB (warning threshold: ${MIN_FREE_GB} GB)"
    fi

    git worktree add -b "$branch" "$path" "$base"

    local f
    for f in "${BOOTSTRAP_FILES[@]}"; do
        if [[ -f "$primary/$f" ]]; then
            cp "$primary/$f" "$path/$f"
            echo "copied $f from the primary checkout"
        fi
    done

    cat <<EOF

Worktree ready:
  path    $path
  branch  $branch (from $base)

Next:
  cd "$path"
  # start your Claude Code / editor session from that directory, not from $primary
Notes:
  - Uncommitted changes in the primary checkout are NOT carried over (by design).
  - The first Gradle/cargo build here is cold: Gradle outputs and rust/target/ are per-worktree.
  - When finished and merged, from OUTSIDE that directory:  $SELF done $name
EOF
}

cmd_list() {
    local base="$DEFAULT_BASE" path="" branch="" line
    printf '%-28s %-8s %-8s %s\n' BRANCH DIRTY AHEAD PATH
    while IFS= read -r line; do
        case "$line" in
            "worktree "*) path="${line#worktree }" ;;
            "branch "*)   branch="${line#branch refs/heads/}" ;;
            "detached")   branch="(detached)" ;;
            "")
                if [[ -n "$path" ]]; then
                    local dirty="-" ahead="-"
                    if [[ -d "$path" ]]; then
                        dirty="$(dirty_lines "$path" 2>/dev/null | wc -l | tr -d ' ')"
                        if [[ "$branch" != "(detached)" && "$branch" != "$base" ]]; then
                            ahead="$(git rev-list --count "${base}..${branch}" 2>/dev/null || echo '?')"
                        fi
                    else
                        dirty="MISSING"
                    fi
                    printf '%-28s %-8s %-8s %s\n' "$branch" "$dirty" "$ahead" "$path"
                fi
                path=""; branch=""
                ;;
        esac
    done < <(git worktree list --porcelain; echo)
}

# True if the branch is contained in the local or remote base branch.
branch_is_merged() {
    local branch="$1" ref
    for ref in "$DEFAULT_BASE" "origin/$DEFAULT_BASE"; do
        if git rev-parse --verify --quiet "${ref}^{commit}" >/dev/null \
            && git merge-base --is-ancestor "$branch" "$ref"; then
            return 0
        fi
    done
    return 1
}

cmd_done() {
    local name="" force=0 unmerged=0 arg
    for arg in "$@"; do
        case "$arg" in
            --force|-f) force=1 ;;
            --unmerged) unmerged=1 ;;
            -*)         die "unknown option '$arg'" ;;
            *)          [[ -z "$name" ]] || die "usage: $SELF done <name> [--unmerged | --force]"; name="$arg" ;;
        esac
    done
    [[ -n "$name" ]] || die "usage: $SELF done <name> [--unmerged | --force]"

    local branch="${BRANCH_PREFIX}${name}" path
    path="$(worktree_path "$name")"

    # A shell sitting inside the worktree blocks its deletion on Windows and leaves it half removed.
    if [[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$path" ]]; then
        die "you are inside '$path'; cd out of it (for example to the primary checkout) and run this again"
    fi

    # A locked worktree was protected on purpose: git will neither remove nor prune it, so cleaning up
    # a deleted-directory registration would also fail half way. Unlock only when explicitly forced.
    local lock
    lock="$(lock_reason "$path")"
    if [[ -n "$lock" ]]; then
        [[ $force -eq 1 ]] || die "'$path' is locked ($lock); pass --force to unlock and remove it"
        git worktree unlock "$path"
        echo "unlocked '$path' (was locked: $lock)"
    fi

    local stale=0
    if [[ ! -d "$path" ]]; then
        # Registered but the directory is gone (deleted in Explorer, or a failed removal): clean up the
        # leftover registration and branch instead of leaving `new` and `done` pointing at each other.
        if is_registered "$path" || git show-ref --verify --quiet "refs/heads/$branch"; then
            echo "worktree directory '$path' is gone; pruning its stale registration"
            git worktree prune
            stale=1
        else
            die "no worktree at '$path' (see: $SELF list)"
        fi
    fi

    if [[ $stale -eq 0 ]]; then
        # Never touch a worktree at this path that is on some other branch, even with --force.
        local head_ref
        head_ref="$(git -C "$path" symbolic-ref -q HEAD || true)"
        [[ "$head_ref" == "refs/heads/$branch" ]] \
            || die "'$path' is on ${head_ref:-a detached HEAD}, not '$branch'; refusing to remove it (see: $SELF list)"

        if [[ $force -eq 0 ]]; then
            if [[ -n "$(dirty_lines "$path")" ]]; then
                die "'$path' has uncommitted changes; commit them (a WIP commit is fine), or pass --force to discard"
            fi
            local precious
            precious="$(git --no-optional-locks -C "$path" status --porcelain --ignored \
                | sed -n 's/^!! //p' | grep -Ev "$EXPECTED_IGNORED_RE" || true)"
            if [[ -n "$precious" ]]; then
                echo "$precious" | head -10 >&2
                die "the ignored files above would be deleted with the worktree; move them out, or pass --force"
            fi
        fi
    fi

    if git show-ref --verify --quiet "refs/heads/$branch"; then
        if [[ $force -eq 0 && $unmerged -eq 0 ]] && ! branch_is_merged "$branch"; then
            die "'$branch' has commits that are not in $DEFAULT_BASE or origin/$DEFAULT_BASE. If it was squash-merged or is abandoned, pass --unmerged (uncommitted changes still block it); --force discards everything"
        fi
    fi

    if [[ $stale -eq 0 ]]; then
        local rm_flags=() out
        [[ $force -eq 1 ]] && rm_flags+=(--force)
        if ! out="$(git worktree remove ${rm_flags[@]+"${rm_flags[@]}"} "$path" 2>&1)"; then
            die "git could not remove '$path': $out
On Windows the usual cause is a process holding files open there: a Gradle daemon (./gradlew --status), an IDE, or a shell whose cwd is inside it."
        fi
    fi

    # -D, not -d: the checks above already decided it is safe, and -d judges "merged" against the
    # current HEAD, which is the wrong reference when run from another linked worktree.
    if git show-ref --verify --quiet "refs/heads/$branch"; then
        git branch -D "$branch"
    fi
    git worktree prune
    echo "removed worktree '$path' and branch '$branch'"
}

case "${1:-}" in
    new)          shift; cmd_new "$@" ;;
    list|ls)      shift; cmd_list "$@" ;;
    done|remove)  shift; cmd_done "$@" ;;
    ""|-h|--help|help)
        sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
        ;;
    *) die "unknown command '$1' (try: new, list, done)" ;;
esac

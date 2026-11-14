# Roadmap team plan (2026-11-13)

Goal: knock out the remaining roadmap with a dedicated team of agents, safely, on a machine that is short on RAM and a codebase with several shared registration files.

## Ground rules (from CLAUDE.md, memory and today's lessons)
1. Nobody starts until the current gate is GREEN and committed/pushed (worktrees branch from committed code).
2. One worktree per workstream (`bash scripts/session-worktree.sh new <name>`), branch `ws/<name>` off `session/wave-3`. One Gradle build at a time PER worktree.
3. RAM budget: at most TWO concurrent Gradle builds machine-wide (each ~6 GB; other sessions share the box). Agents write code and unit tests; targeted `--tests` runs only; the coordinator serialises full gates. Use `--max-workers=2`.
4. Shared files are edited ONLY by the coordinator at merge time: GameHubSpecs, GameCatalog, GameTheme, MainActivity routes, GameTips, GameCoach, GameAudienceTable, server/seat-ranges.json. Agents leave `integration-notes.md` in their package.
5. No APK/emulator/device installs by agents (owner's standing rule); no commit/push by agents; no `git add -A`.
6. Original names and art only; trademark searches are an owner action before store listing.
7. Every agent reports: files, test results (targeted), what was skipped, "nothing installed".
8. Merge order: A, B, then game batches, then F/H last (they touch many screens).

## Workstreams
| ID | Name | Scope | Owner agents | Phase | Depends on |
|---|---|---|---|---|---|
| A | Release readiness | signing script (no keystore committed), R8 + keep rules (UniFFI/JNA, getIdentifier, card strings), AAB, targetSdk bump, baseline profile, backup rules check, Play listing metadata drafts, privacy policy refresh for profiles/arena, CI: lint + relay Docker build + Rust tests | 1 | 1 | none |
| B | Kids experience | rewards engine + sticker book (data + screen from mockup), "what this teaches" lines, parent dashboard weekly report, kid game curation table review, kid-profile Spanish, remaining profile audit items (report/block design only) | 1 | 1 | profiles committed |
| C1 | Games: Sweet Trail, Trail Together | full builds from scaffolds (engine, bot, screen, tests, art in code) | 2 (one per game) | 1 | scaffolds committed |
| C2 | Games: Find It Frenzy, Pie Detective | same | 2 | 2 | C1 pattern proven |
| C3 | Games: Stack Zoo, Flick Rink (fixed-step physics) | same; M0 = physics tuning | 2 | 2 | none |
| C4 | Games: Bump and Run, Egg Garden | same | 2 | 3 | none |
| D | Life Road, then Property Tycoon, Territory Wars | P0..P1 per doc; balance harness first | 1 per game, one at a time | 3 | C pattern |
| E | Arena completion + cross-game handicap row | Tic-Tac-Toe, Mancala, Gomoku, Dots and Boxes, Ludo adapters; handicap/extra-help row on existing 2-player games | 1 | 2 | none |
| F | Accessibility + localisation | text-size slider honoured in 24 files, TalkBack gaps (Air Hockey etc.), Spanish for ~27 games' screens, lobby/problem messages | 2 (split by file list) | 3 | A..E merged |
| G | Online wave 5 client (S1, S2, S5, S8, S9) | BLOCKED: relay host not chosen; stranger testing skipped. Friend-room share/copy/paste + quick chat client can be built behind the empty-relay default | 0 now | later | owner decision |
| H | Audit leftovers | open items listed in the fix reports (Echo Pads restore rule, Draw and Guess long drawings, Act It Out thumbnail, Slide and Climb / Bridge Builders low items, 10 low code-review items) | 1 | 2 | none |

## Phases
- Phase 1 (up to 5 agents, 2 builds max): A, B, C1 (2 agents).
- Phase 2: C2, C3, E, H.
- Phase 3: C4, D, F.
- After each phase: coordinator merges branches into session/wave-3 in order, regenerates server/seat-ranges.json, registers new games in the shared files, runs ONE full gate, commits, pushes, checks PR CI.

## Definition of done per game
Engine tests green, bot levels distinguishable on self-play, snapshot round-trip + corrupt rejection, Spanish strings, TalkBack labels, Reduced Motion, 56 dp targets (72 dp for pad games), audience/minAge set, not networked unless stated, integration-notes.md, and a code audit pass (like docs/audit/06) before release.

## Decisions still needed from the owner
Relay host (blocks G), kids-category listing (affects A and B), trademark searches for new names, native Spanish review, BGG pages 2 and 3.

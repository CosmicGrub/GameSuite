# Intelligent job queue (consumed by the hourly Claude task `gamesuite-integrator-keepalive`)

How to use (resumed Claude session): read AGENT-BRIEFS.md COMMON RULES first. Pick work in priority order, never exceeding
**MAX_LIVE_AGENTS = 8** (use ListAgents; the box has ONE gradle build slot, so more agents only queue; each agent also burns the shared
usage limit). Skip any item whose `needs` are unmet or whose branch already has a live agent. Prefer `light` (docs/assets/design)
jobs when many `build` jobs are already waiting. Mark status here and commit/push this file after each launch or completion.
Status: `running` (agent live at write time) | `ready` (launch when slots free) | `blocked:<why>` | `done`.

| # | Job | Worktree / branch | Type | Needs | Status |
|---|---|---|---|---|---|
| 1 | Style system + Gem Cascade pilot | ws-style | build | - | running |
| 2 | Bot Bastion follow-ups | ws-bb2 | build | - | running |
| 3 | Tower Defence follow-ups | ws-td2 | build | - | running |
| 4 | Platform leftovers | ws-plat2 | build | - | running |
| 5 | Bot Bastion baked 3D atlas | ws-bb3d | light | - | running |
| 6 | Release build verification | ws-release | build | - | running |
| 7 | Kids games A (Sweet Trail, Trail Together, Find It) | ws-sweet-trail etc | build | - | running |
| 8 | Kids games B (Pie Detective, Stack Zoo, Flick Rink) | ws-pie-detective etc | build | - | running |
| 9 | Kids profiles + Computer Arena | ws-kids, ws-arena | build | - | running |
| 10 | Phase-3 batch C (Bump and Run, Egg Garden) | ws-bump-egg | build | - | running |
| 11 | Phase-3 batch D (Life Road, Property Tycoon, Territory Wars) | ws-board3 | build | - | running |
| 12 | Bot Bastion 3D Phase 1 renderer (atlas -> screen via StylePack) | new ws-bb3d-render | build | #1 merged, #5 merged | blocked:waits for style infra + atlas |
| 13 | Gem Cascade extras: personal-best store, haptic cue sheet wiring, richer sound set | new ws-gem2 | build | #1 merged (avoid GemCascadeScreen conflicts) | blocked:ws-style owns the screen |
| 14 | Tower Defence remaining 5 themes + Sandbox + Co-op modes | new ws-td3 | build | #3 merged | blocked:waits for td2 |
| 15 | Style system roll-out: next games per docs/design/style-packs/GAME-STYLE-MATRIX.md (chess, cards, dice, Connect Four...) | new ws-style2 | build | #1 merged | blocked:waits for infra |
| 16 | Accessibility + Spanish sweep (a11y labels, contrast, tests; native review stays with owner) | new ws-a11y | build | #4 merged (literals) | blocked:waits for plat2 |
| 17 | Register + integrate every merged new game (hub, glyph, accent, route, tips, coach) | primary worktree | integrate | game branches merged | ready (do after each merge) |
| 18 | Online wave-5 client | - | - | relay host from owner | blocked:owner |
| 19 | Trademark searches, native Spanish review, Gem Cascade scope picks, 3D look sign-off, device testing | owner | - | owner | blocked:owner |

Rules: when a `build` job finishes, run the integrator procedure (RESUME doc), then unblock dependents and launch the next `ready`/unblocked
job. Add every new branch to docs/handoff/INTEGRATE-BRANCHES.txt.

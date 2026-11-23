# Unbuilt items (refreshed 2026-11-21)

Replaces the 2026-11-07 list. "Built" = compiled and unit-tested on `session/wave-3` (last green gate: 6747 tests, CI green on PR #16).
Nothing below is installed on a device by Claude (owner's standing rule since 2026-11-07).

## Shipped since the first list (no longer unbuilt)
- Profiles and family mode: per-profile data, kid-safe catalogue filter, play-time limits and quiet hours, kids home, parent dashboard.
- Parent unlock beyond maths: phone screen lock, word code, parent PIN (salted hash), sums; persisted escalating lockout.
- Five new games: Slide and Climb, Draw and Guess, Echo Pads, Act It Out, Bridge Builders; audited (docs/audit/06) and fixed.
- Computer Arena for Slide and Climb, Bridge Builders, Chess, Checkers, Reversi, Connect Four (visible CPU-vs-CPU series).
- Security/perf pass: stats corruption safety, backup off, relay URL validation, Nearby admission, SFX/dictionary/Chess search off main thread.
- Docs: five-part audit, design canvas sources, release/store drafts (docs/release), scope packs for 11 games, research (BGG page 1).

## A. Paused team work (branches `session/ws-*`, WIP, unverified)
- A release: signing config, R8 keep rules, release AAB, CI lint + relay Docker build, targetSdk plan (build edits not merged)
- B kids: rewards engine, 24 stickers, sticker book, teaches table, weekly parent report, report/block design
- C: full builds of Sweet Trail, Trail Together, Find It Frenzy, Pie Detective, Stack Zoo, Flick Rink
- E arena: 11 more games, tournament mode, shared "Help the young player" row on existing games
- H audit leftovers: kid-safety verification table, skipped game-audit items, SecureWindow helper, code-review leftovers

## B. Not started
- Games from scaffolds: Bump and Run, Egg Garden, Life Road, Property Tycoon, Territory Wars
- Accessibility and Spanish sweep: text size honoured in 24 files, TalkBack gaps (Air Hockey), Spanish for ~27 games' screens, lobby problem messages
- Design rollout: shared tokens (Midnight Club), bottom nav, Home, setup, action dock, result and score-card screens across games
- Extra BGG-inspired games: Loop Pilot, Tower Climb, Scare Away, Gate Guard, Colour Cars, Gem Drop, stone-age gatherers, plank push-your-luck, real-time coop

## C. Blocked on an owner decision
- Online wave 5 client (friend rooms share/paste, quick chat client, Quick match, two-device e2e harness) and relay hosting: relay host not chosen; stranger testing skipped
- Kids-category store listing (Families Policy / COPPA work)

## D. Longer term
- Voice chat (last), wireless/online party pack, phone-as-controller, Backgammon/Yahtzee networking, Mosaic-style multi-device Air Hockey
- Fold inner screen and half-open posture; tablet layout passes; real-device pass of everything (Nearby, multi-touch, TalkBack)

## E. Known open defects and skips
- Echo Pads restore rule for a miss during checking; Draw and Guess long-drawing cost; Act It Out Recents thumbnail; Slide and Climb and Bridge Builders low items (see docs/audit/06)
- Low code-review items still open (docs/roadmap/CODE-REVIEW-WAVES-1-4.md): idle-turn timer, duplicated helpers
- Release build never verified: R8, signing, AAB, target SDK 35 (edge-to-edge) all unproven
- Echo Pads mockup colours differ from the shipped colour-blind-safe palette (design canvas)

## F. Owner actions
Relay host; kids-category decision; trademark searches (Pie Detective, Life Road, Property Tycoon, Territory Wars, Flick Rink, Sweet Trail, Bump and Run, Trail Together, Slide and Climb); native Spanish review; BGG pages 2 and 3; whether builds may go on devices again.

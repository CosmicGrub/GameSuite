# SCOPE: Gem Cascade (placeholder name, id `gem-cascade`)

Status: DESIGN DRAFT, 2026-11-22. Siblings: `FRANCHISE-MAP.md`, `SCRIPT.md`, `SCALE.md`, `GAME-IN-STYLE-SYSTEM.md`; events in `EVENTS.md` (engine team). Boards: `GCModes`, `GCGems`, `GCStyles`, `GCAnimation`, `GCLevels`, `GCModesInPlay`, `GCKidsAccess`, `GCResults` (`.dc.html`).

## 1. Goal in one sentence

One match-3 game that carries every mode and special gem of the Bejeweled-style genre under original names and art, plays in 2D, 2.5D and 3D looks that the player can switch at any time, and stays calm, honest and safe for kids.

## 2. Experiences

| Experience | Who | Default look and mode | What they see |
|---|---|---|---|
| Adult | 13+ profile | Classic flat or Soft 2.5D (suite default), Standard motion; lands on the hub with Ascent focused | All nine modes, boosters, Charms, local board, Daily, stickers, Look picker |
| Kid (4-6, 7-9, 10-12) | KID profile | Storybook pack, Calm for 4-6, Standard 7+; lands on Garden | Three tiles: Garden, Journey (kid variant), Daily. No timers, no bombs, no rising threats, no scores beyond a bloom/star count |
| Family | Several profiles, one device | Each profile keeps its own look | Family board (local profiles only), shared Daily (same seed), pass-and-play "Duel" later |

## 3. Release plan (MVP / v1 / later)

Effort: S = under 3 person-days, M = 3 to 8, L = 8 to 20, XL = over 20 (agent-days, EST).

| Item | MVP | v1 | Later | Effort |
|---|---|---|---|---|
| Engine: 8x8 board, swap, match, gravity, cascade, shuffle, hint, deterministic seed, event stream, JVM tests | X | | | L |
| Specials: Bloom, Comet, Core, Halo and all 10 combos | X | | | M |
| Modes: Ascent, Garden, Sprint, Daily | X | | | M |
| Journey: 30 levels (worlds 1-2: frost) | X | | | M |
| Journey: all 150 levels, worlds 3-10, 5 more obstacles | | X | | XL |
| Swirl (rotate), Delve, Fireflies, Fuse | | X | | L |
| Riddles (30), Thaw, Sigils, Charms | | 30 riddles | Thaw, Sigils, Riddles 31-60, Charms (v1.1) | L |
| Styles: Flat + Soft 2.5D gem/board/particles | X | | | M |
| Styles: 3D baked sprite pack (on-demand), themed packs (Paper craft, Neon arcade, Storybook) | | X (Storybook first) | real-time 3D (owner D3) | L |
| Haptics and sound palette (3 motion levels) | basic | full | | M |
| Local bests, Daily streak, stickers | bests + streak | 40 stickers, family board | | M |
| Accessibility: tap-tap, colour-blind marks, Reduced Motion, text scale | X | one-switch, 200 percent text pass | voice-over play-by-play | M |
| Kid profile: Garden default, parent view, parent gate wiring | | X | | M |
| EN/ES strings | X | | more languages | S |
| Pass-and-play Duel (same seed, best of 3) | | | X | M |
| Tablet/Fold layouts (side panel HUD, tabletop posture) | simple scale | proper | | M |

## 4. Kids rules (hard, tested in CI where possible)

1. No lives, hearts, energy or timers that block play. A failed Journey level restarts instantly and free.
2. No purchases of any kind: no gem packs, no boosters for money, no paid Charms, no loot boxes, no random rewards, no "mystery" sticker draws. All boosters and Charms are earned by play. IAP does not exist in this app (style-pack SCOPE section 8).
3. No ads, no analytics, no accounts, no chat, no friend lists, no online board, no strangers. Daily uses a date seed, not a server.
4. Calm Zen-style default: kid profiles open on Garden, with Calm motion for ages 4-6, soft sound, no flashing, no countdown ticks.
5. Kid-hidden modes: Sprint, Fireflies, Fuse, Thaw (time or loss pressure) are hidden; Swirl offers tap-tap rotate; Delve uses easy depth. Parents can override behind the parent gate.
6. Fail states are kind: "So close. Try again?"; after two fails an assist offers +5 moves (free, parent can disable).
7. Celebrations are bounded: flashes at most 3 per second (photosensitivity), confetti counts per style capped (`SCALE.md`), no gambling imagery (no reels, slot-machine bursts, card hands; Sigils is a pattern tray).
8. Streaks never punish: a missed day resets quietly, best streak is kept, no streak freeze, no notifications nagging.
9. Kid-safety review signed (style-pack Part D item 7) before the Storybook pack and the kid Journey ship; reviewer and date recorded.

## 5. Accessibility

| Need | Support | Where |
|---|---|---|
| Colour blindness | Seven distinct silhouettes, Okabe-Ito colours, optional marks; checked in four simulations (`GCKidsAccess`) | MVP |
| Motion sensitivity | Reduced Motion (OS or app) forces Calm: no tilt, parallax, shake, idle shimmer; per-game override cannot raise it | MVP |
| Photosensitivity | At most 3 flashes per second; Lively particle counts capped; no full-screen flashes | MVP |
| Low dexterity | Tap-tap swap (tap gem, tap glowing neighbour), swipe optional, hit slop around each cell, large-board option 7x7 at 50 dp or 6x6 at 56 dp | MVP |
| One-switch | Scanning: row, column, direction; speed slider; auto-pause when idle; works with Switch Access and a Bluetooth button | v1 |
| No timers | Every timed mode has a Practice variant; Journey, Ascent, Garden are untimed | MVP |
| Low vision | Text scale to 200 percent (layout reflows, board drops to 7x7), High Contrast theme wins over pack, outlines on gems | v1 |
| Screen reader | Each cell has a description ("Tide rhombus, row 3 column 4, Spark Bloom"); events announce matches and chains in a calm summary; optional "play by voice" later | MVP basic, later full |
| Sound-off play | Every cue has a visual and a haptic twin; Garden works with sound and haptics off | MVP |
| Cognitive | No reading needed in kid Garden; hints always available; tutorial replayable | MVP |

The 48 dp minimum applies to controls. The play surface is a continuous gesture area; at 8x8 on a 390 dp phone a cell is 44 dp, so tap-tap uses an enlarged hit slop (cell plus 4 dp) and the large-board option exists. This is a documented exception.

## 6. Out of scope

Online play, real-money anything, ads, user-made levels or art, licensed characters, a level editor (maybe later for the owner only), re-skinning the rules per style, web/iOS, a new game engine, ray tracing. Real-time 3D is behind owner decision D3 of the style system.

## 7. Open questions with recommended defaults

| # | Question | Recommended default |
|---|---|---|
| G1 | Final name (check trademarks) | Keep "Gem Cascade" only if search is clean; else pick another plain name |
| G2 | Board size | 8x8 default, 7x7/6x6 as accessibility and kid sizes; 9x9 in World 10 |
| G3 | Ascent fail rule | Reshuffle once free, second dead board ends |
| G4 | Sprint included in kid profiles | No (hidden), Practice available |
| G5 | Sigils (pattern bonus) | Cut unless the owner likes it; zero gambling look either way |
| G6 | Level count at launch | 30 in MVP, 150 in v1 |
| G7 | Charms | v1.1, earned only |
| G8 | Family board visibility for kids | Off; parent opts in |
| G9 | Hub on tablets | Two-pane: map left, mode card right |
| G10 | Haptics default | On for adults, off for kid Garden |

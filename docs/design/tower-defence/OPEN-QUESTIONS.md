# Tower Defence redesign: open questions for the owner

Status: DESIGN DRAFT, 2026-11-22. Each has a recommended default so work can proceed if you say "defaults are fine".

| # | Decision | Options | Recommended default | Why it matters |
|---|---|---|---|---|
| 1 | Map model | A: keep fixed pads; B: free placement beside lanes; C: open field with flow field and route rule | C (open field, route must stay open, 2.5 x length cap) | The core of "open placement as I choose"; C is the largest engine change |
| 2 | Anti-maze cap | none / 2.5 x route length / hard block | 2.5 x, per-map tunable, off in Sandbox | Without it the game becomes a single maze strategy |
| 3 | Classic maps | retire / keep as a Classic tab | Keep the three existing maps as Classic (fixed lanes, one Bolt role) | Zero stats loss; costs small engine surface |
| 4 | Castle replaces lives | keep abstract lives / castle with health | Castle with 20 health (same economy), boss costs 5 | Makes the base a visible object and a hero shot |
| 5 | Tower roles | keep one tower / 4 roles with a branch at level 3 | 4 roles (Bolt, Blast, Control, Support) | Adds decisions; most balance work |
| 6 | Enemy variety | stat scaling only / 4 archetypes plus bosses on waves 5 and 10 | 4 archetypes plus bosses | Needed for roles to matter (flyers vs Blast, brutes vs Bolt) |
| 7 | Theme list | confirm the 8 themes | Castle Siege, Space Station, Roman Frontier, Cherry Ridge, Sunstone Oasis, Frostfjord, Toy Room, Coral Reef | Art volume, scope per theme |
| 8 | First release set | all 8 / first 3 | Castle Siege, Space Station, Toy Room, then others in waves | Keeps the first release shippable |
| 9 | Naming of cultural themes | inspired-by names as drawn / literal names | Inspired-by names (Cherry Ridge, Sunstone Oasis, Frostfjord) with invented units; "Roman" kept as a setting label | Avoids IP, religious and cultural-sensitivity issues |
| 10 | Theme unlocks | stars / free / parent unlock | Stars (6 per tier of unlock), all unlocked via parent settings, never money | Progression without monetisation |
| 11 | Kid safety | kid-first themes only / kid variant switch for all | Both: Toy Room and Coral Reef kid-first, a parent-controlled kid variant for the other six | Age rating and parent trust |
| 12 | Gentle difficulty tier | add / not | Add a fourth tier (gold 200, hp x0.6, speed x0.7, count x0.7, castle x1.5), default in kid profiles | Easy is still hard for young kids |
| 13 | Game modes in v1 | Campaign only / plus Endless and Daily / all six | Campaign, Endless, Daily, Toy Room in v1; Sandbox and Co-op in v2 | Scope control |
| 14 | One-device Co-op | build / skip | Build in v2 (shared castle, own gold, circle vs square ownership, tabletop layout) | Fun on the Fold tabletop; adds ownership rules |
| 15 | Phone portrait | scroll with pinned castle / redrawn portrait routes | Scroll with pinned castle and minimap as default; redrawn top-to-bottom only as optional per-map alternates | Keeps the left to right rule; redrawn routes double authoring |
| 16 | Art style tiers | Flat + Soft 2.5D now; Full 3D later | Flat and Soft 2.5D at launch; baked 3D only if the 3D decision lands | 3D is an existing owner decision (no engine, baked only) |
| 17 | Quality gate on Lively | allow now / after perf fix | After the TD per-frame recomposition fix (P0) | Scorecard systemic item 3 |
| 18 | Co-existence with Tower Defence stats | reset / keep keys | Keep `levelId:DIFFICULTY` keys; new maps follow the same format | No stat loss, simple store |
| 19 | Audio | synthesised only / recorded | Synthesised palette per theme (like Gem Cascade); owner may later swap in recordings | Licensing, size |
| 20 | Voice prompts for kids | none / recorded voice / TTS | System TTS first; recorded voice optional later | Cost and localisation |
| 21 | Tuning numbers | accept board numbers / re-run balance | Treat all numbers on the boards as defaults and tune with a JVM simulation before art lock | Numbers derived from shipped constants, not from playtests |
| 22 | Dialog on theme change mid-run | allow / restart | Theme change is cosmetic; allowed mid-run (no restart) but only between waves | Not like map or difficulty changes which restart (current behaviour) |
| 23 | Daily twist list | cosmetic only / stat modifiers | Mix: weather and time are cosmetic; the twist is a stat modifier like "fast enemies" or "no Control towers", shown before play | Daily variety without paywalls |
| 24 | Hardware coverage | tablet + Fold + phone | Same as the rest of the app; Fold book posture is a known untested posture | Honest risk |
| 25 | Who reviews cultural themes | owner / community | Owner reviews names and art for the four cultural themes before art lock | Sensitivity and accuracy |

## Things we need from you to unblock

1. Confirm decisions 1, 4, 5 and 7 (map model, castle, tower roles, theme list). Everything else can use defaults.
2. Tell us whether any theme must be dropped or added.
3. Say whether the 12 mockup boards (`docs/design/canvas/TD*.dc.html`) are approved as the direction, or which ones need changes.

## Known limits of the mockups

- Art is vector and original, drawn for these boards; production sprites will be derived from them but 3D renders will differ (the 3D look is an approximation).
- Numbers on the boards come from the shipped engine constants (base damage 18, cooldown 0.65 s, range 0.22 of width, upgrade price curve 40 x 1.6^n) extended by role factors; they have not been playtested.
- Nothing was built or run on a device; no APK was installed (standing rule).

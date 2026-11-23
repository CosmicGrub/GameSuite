# Bot Bastion: open questions for the owner

Status: DESIGN DRAFT, 2026-11-22. Each has a recommended default so work can proceed if you say "defaults are fine".

| # | Decision | Options | Recommended default | Why it matters |
|---|---|---|---|---|
| 1 | Name | Bot Bastion / something else | Bot Bastion as a working name; run a trademark and store-name search before public use | Avoids IP trouble; no source names in the product |
| 2 | How close is "1 to 1"? | Structure only (rules, roles, economy, specials, tiers) / also copy exact numbers if you play and report them | Structure only; our own balance | Exact numbers are not public; copying remembered figures adds nothing and risks the originality rule |
| 3 | Gap-closing play session | Skip / you or a tester play the source game and fill the checklist in RESEARCH.md section 5 (numbers and behaviours only, no screenshots committed) | Do it once if you want closer fidelity | Closes unknowns: path shape, enemy roster, boss, upgrade count, co-op rules |
| 4 | Placement model | Fixed nodes v1 / open placement from the start | Fixed nodes (matches the source's limited spots) with open placement in Sandbox once `MAP-MODEL.md` option C exists | Keeps Bot Bastion independent of the open-placement phase |
| 5 | Map count at launch | 3 / 6 / 12 | 12 (as designed) with BB-1 shipping 3 first | Scope; source had 6 originally, 20+ in the remake |
| 6 | Tier set | Cadet / Captain / Hero / Insanity / Endless as in the remake / only Easy, Hard, Endless as in the original | The five-tier set (names are generic enough; Insanity and Endless are common words) | Content breadth; names are ours to change |
| 7 | Art direction | Neon vector top-down (as boards) / crayon-like | Neon vector; crayon texture is the source's identity and must not be copied | Originality rule |
| 8 | Fit in the app | Mode inside Tower Defence / standalone game card | Mode tab inside Tower Defence ("Bot Bastion"), sharing stats and settings | Reuse and discoverability |
| 9 | Co-op size | 2 / 4 / 5 (source) | Up to 4 on tablet and Fold; 2 on phone | Screen room and input |
| 10 | Co-op input | Touch only / gamepad too / second touch region on tabletop | All three; gamepad up to 3 extra | Fold tabletop use case |
| 11 | Leaderboards | Local only / local + online later | Local now; online later behind a parent toggle and the relay server | Privacy, kids, scope |
| 12 | Map unlock | By stars / by wave / all open | By total stars (0, 0, 2, 4, 6, ... 21) | Progression without money |
| 13 | Specials in Endless | On / off | On, but cap 3 per kind | Balance |
| 14 | Move bot | Not allowed (fixed-spot genre) / allowed with fee | Not allowed in campaign; allowed in Sandbox | Preserves decision weight |
| 15 | Phone portrait | Rotate prompt / redrawn portrait | Rotate prompt | Same as the rest of Tower Defence |
| 16 | Rating | E10+ cartoon / E | E10+ cartoon, Cadet kid-friendly copy | Source was criticised for no descriptors |
| 17 | Kid profile | Cadet default + gentle copy / no special handling | Cadet default, gentle copy, no co-op auto-join | Parent trust |
| 18 | Audio | Synthesised / recorded | Synthesised (as the other Tower Defence themes) | Licensing, size |
| 19 | Balance numbers | Accept `BALANCE.md` / re-tune with the simulator first | Treat all as defaults; tune with T9 before art lock | Numbers are invented, not playtested |
| 20 | Dependency on P0 | Wait for per-frame recomposition fix / ship Calm and Standard first | Ship Calm and Standard first; Lively after P0 | Avoid frame-rate regressions |
| 21 | Roadmap slot | Before or after the open-placement phases of the Tower Defence redesign | Parallel: Bot Bastion engine pieces are independent | Team planning |

## Things we need from you to unblock

1. Confirm decisions 1, 2, 4 and 5 (name, fidelity, placement model, map count).
2. Say whether the 8 mockup boards (`docs/design/canvas/ZS*.dc.html`) are approved as the direction.
3. Decide whether to do the play session in 3.

## Known limits of the research and mockups

- Public sources give roles, structure and a few anchors (4 and 5 damage per hit, +25% easy cash, swarm every 5 on Hard, specials, five bot roles); they give no exact costs, damage, wave sizes or base health. Our numbers are invented and unplaytested.
- Several sources (Nintendo Life, Time Extension, ModDB, Co-Optimus) were blocked and used only through search summaries (marked L in `RESEARCH.md`).
- Owner's "Flash origin" was not confirmed by any source.
- Mockup art is vector and original; production sprites will derive from it. Boards were render-checked in headless Edge; the in-app dc canvas was not available to this session. `canvas.json` was not modified (coordinator does that).
- Nothing was built or run on a device; no APK was installed (standing rule).

# Tower Defence: script (storyboards, cues, copy, staged plan)

Status: DESIGN DRAFT, 2026-11-22. Board: `TDStoryboards.dc.html` (five frames per beat). Haptic names follow `docs/design/haptics-cue-sheet.md`; ids use the `td_` prefix like `gc_` in Gem Cascade.

## 1. Storyboards (timings are targets at Standard motion, 1x speed)

Each beat lists the frames, then audio, haptic and Reduced Motion (RM) behaviour. Calm drops particles and secondary motion; Lively adds trails. Frame times scale with the game speed setting except UI-only cues.

### 1.1 Wave start (0 to 2.4 s)
1. Build countdown reaches 0:03; "WAVE 6 in 0:03" pill.
2. Banner rises ("WAVE 6"), gate on the west edge glows.
3. Horn sounds; first spawn group appears at the gate with a soft light flare.
4. Enemies begin the march; banner fades.
5. Wave pip in the top bar fills; tray stays active (building during a wave is allowed).

Audio: `td_horn_start`, then drum loop `td_march_loop` begins. Haptic: `medium_tick` on banner, `light_tick` per spawn group. RM: banner fades with no slide; no gate glow pulse.

### 1.2 Tower placement (0 to 1.2 s)
1. Player lifts a tray card.
2. Dragging: ghost follows 56 dp above the finger, range ring and route preview shown.
3. Ghost is valid: green footprint, check glyph, "Route open +1 tile", snap tick.
4. Release: tower rises with a dust puff, coin spend floater "-50".
5. Settle: ring fades to the idle dashed ring.

Audio: `td_place` (material by theme), `td_coin`. Haptic: `tick` per new cell with Snap on, `medium_tick` on build. Invalid: `td_nope` (short descending blip) plus `double_tick`, ghost shakes 4 px once. RM: 120 ms fade, no dust, no floater.

### 1.3 Upgrade (0 to 0.9 s)
1. Tap a tower; ring and panel.
2. Tap Upgrade (cost dims if unaffordable).
3. White flash on the tower (200 ms).
4. Sprite swaps to the next tier with a burst and "LEVEL 4" tag; pips fill.
5. Panel settles; gold rolls down.

Audio: `td_upgrade` (rising two-note chime). Haptic: `heavy_click` then `tick`. RM: flash becomes a ring highlight; pips fill instantly.

### 1.4 Boss arrival (0 to 3.0 s)
1. Screen edge darkens with a red tint (non-flashing).
2. Horn and one short shake with an exclamation icon.
3. A silhouette of the boss enters from the west gate.
4. Boss bar slides in under the wave pill.
5. Boss marches with escorts; low drone under the music until it falls.

Audio: `td_boss_horn`, drone `td_boss_drone`. Haptic: one 400 ms rumble, then silence. RM and kid variant: no shake or vignette; the bar and text banner announce the boss.

### 1.5 Castle damage (0 to 1.4 s)
1. An enemy reaches the gate.
2. Hit flash (red 12 percent for 120 ms) and a 3 px shake; "-1" floater.
3. Health bar drains with a lagging "chip" segment.
4. Dust puffs and a crack overlay (at 50 percent and 25 percent).
5. Below 25 percent: heart pill pulses with a 3 px outline; soft heartbeat tick (not looping beyond 10 s).

Audio: `td_castle_hit` (stone thud, softened in kid themes). Haptic: `heavy_tick` per leak; low-health pattern is two taps every 3 s, capped at 3 repeats. RM: no shake; heart pill flashes once and a text line "Castle 17 of 20" is announced.

### 1.6 Victory (0 to 4.0 s)
1. Last enemy falls and poofs.
2. Banner "Siege Broken!".
3. Flags and fireworks (RM: static sticker burst).
4. Stars tally one by one, each with a tick.
5. Result card with the primary action "Next map".

Audio: `td_win` (fanfare using the theme instrument). Haptic: `celebrate_triple_tick`, one extra tick per star.

### 1.7 Defeat, soft (0 to 3.0 s)
1. Gate breached.
2. World desaturates over 600 ms.
3. Smoke rises, the flag lowers.
4. Soft card "The keep held until wave 7. Best here: wave 9. Almost there."
5. Buttons: Try again (primary), Easier, Home.

Audio: `td_lose` (gentle falling minor third, no sting). Haptic: one soft long pulse. Tone rules: no red full-screen, no shake, no word "fail"; a tip from the run log comes first (leaked type, tower damage mix).

## 2. Sound id table (per theme via `SOUND_PALETTE`)

| Id | Meaning | Variants per theme |
|---|---|---|
| `td_horn_start`, `td_march_loop` | wave start | brass / synth / lur / taiko / frame drum / horn / toy xylophone / marimba |
| `td_place`, `td_sell`, `td_move`, `td_nope` | build actions | wood / metal / paper / etc. |
| `td_shoot_bolt`, `td_shoot_blast`, `td_shoot_control`, `td_support_pulse` | role fire sounds | 4 per theme |
| `td_hit_runner`, `td_hit_brute`, `td_hit_flyer`, `td_kill_pop` | enemy hits | soft in kid themes |
| `td_castle_hit` | leak | stone / hull / gate / keep / temple / hall / block / shell |
| `td_upgrade`, `td_coin` | economy | shared with small timbre shifts |
| `td_boss_horn`, `td_boss_drone` | boss | |
| `td_win`, `td_lose`, `td_star` | results | |
| `td_ui_tick` | tray, chips | shared |

Rules: synthesised or original recordings only; every sound has a Reduced Motion counterpart of at most the same loudness (RM does not change audio); kid themes cap peak loudness 6 dB lower and have no sudden stingers.

## 3. Haptic map

Haptics vocabulary reused from the cue sheet: `tick`, `light_tick`, `medium_tick`, `heavy_tick`, `heavy_click`, `double_tick`, `celebrate_triple_tick`, plus a new `rumble_400ms` for the boss. Gated by `LocalHapticsEnabled`; Calm uses only placement ticks and results; Lively adds hit ticks at 8 Hz max.

## 4. Copy (EN and ES draft; strings belong in `strings.xml`, not code)

| Key | EN | ES |
|---|---|---|
| td_build_phase | Build phase: place towers, then start the wave | Fase de construccion: coloca torres y empieza la oleada |
| td_call_early | Call wave early +%d | Llamar oleada ya +%d |
| td_wave_cleared | Wave %d cleared | Oleada %d superada |
| td_perfect | Perfect +%d | Perfecta +%d |
| td_route_open | Route open, +%d tiles | Camino libre, +%d casillas |
| td_err_blocks | Can't build here: it would seal the gate | No se puede: cerraria la puerta |
| td_err_long | Can't build here: the route would get too long | No se puede: el camino seria demasiado largo |
| td_err_unbuildable | This ground can't be built on | No se puede construir en este terreno |
| td_err_spacing | Too close to another tower | Demasiado cerca de otra torre |
| td_err_gold | Not enough gold (%d needed) | Oro insuficiente (faltan %d) |
| td_victory | Siege broken! | Asedio roto! |
| td_defeat | The keep held until wave %d | La fortaleza resistio hasta la oleada %d |
| td_defeat_best | Best here: wave %d. Almost there. | Mejor aqui: oleada %d. Casi lo logras. |
| td_tip_flyers | Flyers got past your Blast towers. Add a Bolt tower near the gate. | Los voladores pasaron tus torres de explosion. Pon una torre de dardos cerca de la puerta. |
| td_boss_incoming | Boss incoming: %s | Llega el jefe: %s |
| td_castle_status | Castle %d of %d | Castillo %d de %d |

Theme-specific nouns come from the theme tables in `THEMES.md` (castle vs station vs keep vs hall). Kid themes use picture prompts plus a read-aloud voice option instead of sentences.

## 5. Staged plan

| Stage | Deliverable | Acceptance test | Who |
|---|---|---|---|
| 0 | TD screen performance fix (no whole-screen recompose per frame) | Frame time test on existing map | engine/UI |
| 1 | Nav grid, flow field, validation, sell, move, targeting, Castle; JVM tests (`MAP-MODEL.md` section 11) | Property and parity tests green | engine |
| 2 | Castle Siege pack (Flat and Soft 2.5D), HUD v2, tray, panels, next-wave card, boss waves | Device pass on tablet and Fold; colour-blind and large-target checks | UI, art |
| 3 | Space Station, Roman Frontier packs; ambient director; motion levels | Budget gate in `SCALE.md` | art, UI |
| 4 | Cherry Ridge, Sunstone Oasis, Frostfjord packs; modes hub; Endless; Daily | Daily seed determinism test | all |
| 5 | Toy Room, Coral Reef, Gentle tier, kid variant switch, TalkBack grid, one-switch | `design:accessibility-review` pass; parent gate unchanged | all |
| 6 | Sandbox, Co-op one device, results polish, stickers, Full 3D baked sprites (if the 3D decision allows) | Owner sign-off per mode | all |

New-pack quality gate (copy from `docs/design/style-packs/SCRIPT.md`): silhouette check, 48 dp targets, colour-blind simulation, Reduced Motion check, size gate (`asset_size_gate.py`), frame budget, no third-party IP, kid-safety review.

## 6. Settings and UI copy additions

| Surface | Control |
|---|---|
| Tower Defence settings (new) | Theme; Snap on/off; Route preview on/off; Targeting default; Large targets; Auto-follow camera; Kid variant (parent only) |
| Pause sheet | Resume, Rules, Restart, Quit |
| Game info panel (existing) | Replace "8 zones" text with open placement rules and tier table |

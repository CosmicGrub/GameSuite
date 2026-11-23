# SCRIPT: rules, level pack, tutorial, cue sheet, copy

Status: DESIGN DRAFT, 2026-11-22. Event ids below are PROVISIONAL until the engine team publishes `EVENTS.md` in this folder; the cue sheet is keyed by these ids and must be re-keyed if they change.

## Part A. Rules script

### A1. Board and moves
1. Board: 8 columns x 8 rows (7x7 and 6x6 as large-board and kid options; 9x9 in World 10). Seven gem kinds: Ember, Amber, Sunbeam, Moss, Frost, Tide, Orchid (`GCGems`). Ascent levels 1-5 use only the first six kinds.
2. Start board: seeded RNG (`seed`, `rulesetId`); no existing match; at least one valid move.
3. Swap: select a gem, then swap with an orthogonal neighbour (drag or tap-tap). The swap is legal only if it makes a match (or involves a special + any gem, or two specials). An illegal swap bounces back; it costs no move and no time.
4. Swirl rotation: a quarter turn of any 2x2 block clockwise/counter-clockwise; legal always; costs a move even without a match.
5. Match: 3 or more same-kind gems in a straight line. Shapes: line of 3 (clear), line of 4 -> Spark Bloom, L/T/plus of 5 -> Cross Comet, line of 5 -> Chroma Core, line of 6+ or two crossing lines of 4 -> Halo Crown. The special is placed on the swapped gem's cell (or the middle of the shape on a cascade).
6. Gravity: gems fall; new gems enter from the top; obstacles that are static (slate, cage on a gem, dirt) do not fall; cages hold their cell's gem.
7. Cascade: after each fall, new matches clear automatically; each step increases the chain level (x1, x2, ... cap x8).
8. Dead board: if no legal move exists after settling, the board reshuffles (all gems randomised, specials and obstacles kept, no matches, at least one move). Journey: free; Ascent: free once.
9. Hint: after 8 s idle (5 s in kid profiles) a valid swap pulses; Lantern shows the best one on demand.

### A2. Scoring (all values provisional, tuned in play-testing)
| Item | Points |
|---|---|
| Match of 3 / 4 / 5 | 30 / 60 / 100 (+40 per gem beyond 5) |
| Gem cleared by a special | 20 each |
| Special created | +50 |
| Chain step n | step total x n (cap x8) |
| Sprint speed meter | x1 to x8 on top of chain; total multiplier cap x16 |
| Echo gem in a match | match points x2 |
| Combo bonus | Bloom+Bloom 400, Bloom+Comet 600, Bloom+Halo 800, Comet+Comet 500, Comet+Halo 800, Halo+Halo 1,500, any+Core 1,000, Core+Core 5,000 |
| Finale (Journey) | 300 per unused move |
| Obstacle cleared | cage 50, frost layer 40, slate 80, firefly 150, relic 250, bomb defused 120 |

Ascent level bar target for level n: 1,000 + 400 x (n - 1). Level-up spawns one Spark Bloom on the board and a 1.2 s glow (Calm: static badge).

### A3. Mode rule sets
| Mode | Goal | Fail | Spawners | Boosters |
|---|---|---|---|---|
| Ascent | level bar | two dead boards | none | Reshuffle, Smash, Lantern |
| Garden | none | never | none | all, free |
| Sprint | max score in 60 s | clock | clock gems (1 per 12 gems) | Time Drop, Reshuffle |
| Journey | level goal in moves | moves reach 0 | per level | Reshuffle, Smash, +3 moves, Lantern |
| Swirl | score or goal | no moves | per level | Smash, Lantern |
| Delve | depth | no moves | dirt rows | Smash, Lantern |
| Fireflies | free fireflies | firefly on web row | firefly every 6 moves | Reshuffle, Lantern |
| Fuse | defuse bombs | bomb at 0 | bomb every 4 defused | Smash, Lantern |
| Daily | fixed per date | moves reach 0 | seeded | none (fair for all) |

### A4. Level generator rule
Every Journey level ships with a bot-verified record: a greedy bot solves the level in 70 percent or fewer of the move budget in at least 4 of 5 fixed seeds; a random-play bot solves it in under 10 percent of runs at levels past 20 (so skill matters). Generators are bundled (fixed boards); no runtime generation on the main thread (scorecard finding 6).

## Part B. Level pack plan: 150 levels in 10 worlds

15 levels per world. Level 5, 10 are "breathers" (easier). Level 15 is the world gate (hybrid goal) and needs one star on 10 of the 15 levels of its world (never payment).
Move budget formula: `moves = round(par x 1.3)` where par is the bot's median at the level's difficulty; 2 stars at 75 percent of the 3-star score, 1 star = goal met.

| World | Levels | Name | New obstacle / mechanic introduced | New goal type | Board / kinds | Typical moves |
|---|---|---|---|---|---|---|
| 1 | 1-15 | Meadow Glade | none (tutorial) | score; collect colour (L6) | 8x8, 5 to 6 kinds | 25-30 |
| 2 | 16-30 | Frost Hollow | Frost 1 layer (L16), 2 layers (L21), 3 layers (L27) | clear frost | 8x8, 6 | 24-30 |
| 3 | 31-45 | Slate Quarry | Slate (L31), cracked slate with 2 hits (L38) | clear slate; mixed | 8x8, 6 | 24-28 |
| 4 | 46-60 | Cage Market | Cage (L46), cage + frost stacked (L53) | free caged gems | 8x8, 6-7 | 24-28 |
| 5 | 61-75 | Firefly Marsh | Firefly (L61), web row speed 3 -> 2 moves (L69) | free fireflies | 8x8, 7 | 22-26 |
| 6 | 76-90 | Fuse Foundry | Fuse bomb counter 8 (L76), two bombs (L82), bomb under frost (L88) | defuse bombs | 8x8, 7 | 22-26 |
| 7 | 91-105 | Glowworm Mines | Dirt + relic (L91), hard dirt (L97), relic chains (L103) | bring relics down | 8x9 scroll, 7 | 24-28 |
| 8 | 106-120 | Spin Atelier | Swirl rotation (L106), swirl + cage (L112), swirl + slate (L118) | goal levels | 8x8, 7 | 20-24 |
| 9 | 121-135 | Tide Reef | Pearls (drop items, L121), Current row (L127, later feature) | bring pearls down | 8x8, 7 | 22-26 |
| 10 | 136-150 | Crown Peak | All obstacles mixed; 9x9 boards (L145); boss levels every 5th | hybrid | 8x8/9x9, 7 | 20-26 |

Booster unlocks: Lantern L3, Reshuffle L5, Smash L9, +3 moves L12 (earned, then +1 each 5 levels). Special introductions: Bloom L4, Comet L7, Core L10, Halo L13, first combo card L14.
New-obstacle intro: a 3-step card the first time an obstacle appears (board before/after from `GCLevels`), then a scripted first level where the obstacle is the only goal.
Kid Journey variant: same maps, no Fuse/Firefly worlds in the main path (replaced by Glaze-free filler: collect and frost levels), no fail (moves refill by 3 when reaching 0, up to 3 times), stars from goal and speed-free bonus.

## Part C. Tutorial script (first run, skippable, replay in Settings)

Tone: short, kind, visual first. Each step highlights the board cells in question; the rest dims. Speech-bubble in kid profile adds an icon; screen reader reads the same text.

| # | Trigger | Board | EN | ES | Done when |
|---|---|---|---|---|---|
| 1 | first launch | scripted 6x6 | Swap two neighbours to line up three of a kind. | Cambia dos vecinas para alinear tres iguales. | any legal swap |
| 2 | after 1 | 1 move left to match | Nice! Matched gems pop and new ones fall. | ¡Bien! Las gemas iguales explotan y caen otras nuevas. | clear animation ends |
| 3 | after 2 | line of 4 setup | Line up four to make a Spark Bloom. | Alinea cuatro para crear una Flor de chispas. | Bloom created |
| 4 | after 3 | Bloom near a match | Match the Bloom to clear the gems around it. | Combina la flor para limpiar las gemas de alrededor. | Bloom fired |
| 5 | after 4 | L-shape setup | Make an L or T to build a Cross Comet. | Haz una L o una T para crear un Cometa cruzado. | Comet created |
| 6 | after 5 | chain setup | Clearing gems can start a chain. Chains score more! | Al limpiar gemas puede empezar una cadena. ¡Valen más! | chain of 2 or more |
| 7 | after 6 | five-line setup | Line up five for a Chroma Core. Swap it with any gem. | Alinea cinco para un Núcleo de color. Cámbialo con cualquier gema. | Core fired |
| 8 | after 7 | 20 s idle (or on demand) | Stuck? The Lantern shows a move. | ¿Atascado? La Linterna te muestra una jugada. | hint used or skipped |
| 9 | after 8 | mode hub | Pick a mode. Garden has no clock and never ends. | Elige un modo. El Jardín no tiene reloj y nunca termina. | mode tapped |
| 10 | first Journey level | 1 frost | Frost needs a match on top of it to melt. | La escarcha se derrite al combinar encima. | frost cleared |
| 11 | settings, Look and feel | n/a | Try 2D, 2.5D or 3D. You can change it any time. | Prueba 2D, 2,5D o 3D. Puedes cambiarlo cuando quieras. | picker opened or skipped |
Skip button always visible (48 dp). A kid profile gets steps 1-4, 6, 9 only, with no text on screen (icons, hand pointer, voice-over for screen readers).

## Part D. Cue sheet: sound, haptic and animation per event

Sounds are original; names are internal ids. Pitch rises one semitone per chain step (cap 8). Haptic vocabulary uses the suite's haptic signal names where they exist (`docs/design/haptics-cue-sheet.md` lives on another branch; names below are PROVISIONAL and mapped to its vocabulary at implementation). Calm = Reduced Motion forced or chosen; Lively adds the extras in the last column.

### D1. Sound and haptic (shared by all styles; style changes only the SOUND_PALETTE variant)
| Event id (provisional) | Sound id | Haptic Calm | Haptic Standard | Haptic Lively |
|---|---|---|---|---|
| select | gc_tick_soft | none | tick | tick |
| swap.ok | gc_swap | none | tick | click |
| swap.invalid | gc_thud_soft | none | soft double tick | soft double tick |
| match.3 | gc_pop_a | none | click | click |
| match.4 | gc_pop_b | none | click | strong click |
| match.5 | gc_pop_c | none | strong click | strong click |
| chain.step (n) | gc_chime_n (pitch + n) | none | click at n >= 2 | ramp up to n = 8 |
| chain.pop (label) | gc_chime_top | none | none | tick |
| special.create | gc_sparkle_up | none | strong click | double click |
| special.fire.bloom | gc_burst_round | none | thud | heavy thud |
| special.fire.comet | gc_zip_cross | none | double tick | zip ramp |
| special.fire.core | gc_wash_color | none | long ramp | long ramp + tail |
| special.fire.halo | gc_halo_swell | none | heavy thud | heavy thud + tail |
| combo.fire | gc_combo_stack | none | heavy thud | heavy thud + tail |
| obstacle.cage.open | gc_latch | none | tick | click |
| obstacle.frost.hit | gc_ice_tink | none | tick | click |
| obstacle.frost.clear | gc_ice_crack | none | click | strong click |
| obstacle.slate.hit / clear | gc_stone_knock / gc_stone_split | none | tick / click | click / strong click |
| bomb.tick (Fuse) | gc_fuse_tick (pitch rises) | none | pulse at 2, 1 | pulse at 3, 2, 1 |
| bomb.defuse | gc_snip | none | click | click |
| firefly.rise | gc_flutter | none | tiny tick | tiny tick |
| firefly.free | gc_glass_chime | none | click | double click |
| relic.collect | gc_gold_ring | none | strong click | strong click |
| dirt.break | gc_dirt_thud | none | tick | click |
| time.extend (Sprint) | gc_clock_plus | none | tick | click |
| timer.low (10 s) | gc_tock (soft) | none | tick per second | tick per second |
| shuffle | gc_swirl | none | ramp | ramp |
| hint.show | gc_glint | none | none | none |
| star.earn (1..3) | gc_star_n | none | click each | click each |
| level.complete | gc_fanfare_short | none | long ramp | long ramp + tail |
| level.fail | gc_soft_down | none | one soft tick | one soft tick |
| daily.complete | gc_streak_ding | none | strong click | strong click |
| sticker.earn | gc_sticker_pop | none | click | click |
| bloom.grow (Garden) | gc_wind_chime | none | none | none |
Photosensitivity cap: flashes at most 3 per second in every level; haptics respect `LocalHapticsEnabled`.

### D2. Animation per event and style (Standard motion; durations in `GCAnimation`)
| Event | 2D flat | 2.5D soft | 3D faceted | Calm | Lively extra |
|---|---|---|---|---|---|
| swap.ok | arc 140 ms, flat shadows | arc + lift, cached shadow shrinks | arc + 8 degree tumble | linear slide 100 ms | 40 ms anticipation |
| swap.invalid | bounce back 120 ms | same + shadow squish | same + wobble | shake-free slide back | none |
| match.clear | glow, shrink, 6 flat squares | glow, shrink, soft blobs | glow, shatter into shards | 120 ms fade | 48 particles, 50 ms hit-stop |
| special.create | ring pulse | ring + soft bloom | ring + lens flare, gem spins once | static ring | spark ring 18 particles |
| special.fire | ring, area clear | ring, glow sweep | ring, shards with depth | fade out | camera shake 4 px |
| chain.step | text slot pop | pop + glow | pop + extruded text | text fades in | pop scale 1.6 + ring |
| fall + settle | 220 ms, 12% bounce | + shadow contact | + slight board give | 140 ms, no bounce | 18% overshoot |
| obstacle.frost | layers fade | layers crack | crack mesh, ice shards | layer fades | extra sparkle |
| obstacle.slate | knock + dust | dust + chips | chips with depth | fade | chunk fly |
| bomb tick | number pulse | + glow | + fuse spark | number swaps | fuse spark particles |
| firefly.rise | glow slides up | + trail | + wing flutter | glow slides | trail longer |
| relic.collect | drop + sparkle | + gold glow | gold facets turn | fade to counter | coin arcs |
| shuffle | swirl 600 ms | swirl + depth blur (cached) | camera orbit 600 ms | 200 ms cross-fade | extra spin |
| level.complete | stars scale in, 6 squares | stars + glow, 10 blobs | stars turn, 14 shards | stars appear | burst + confetti 40 |
| level.fail | board dims, soft card | same | same | same | same |
| hint.show | outline pulse | outline pulse + glow | outline pulse + gem tilt | static outline | none |

Reduced Motion (OS or app) additionally removes tilt, parallax, shake, looping shimmer and camera orbits. Quality Battery saver forces flat art and Calm-class particles (0) irrespective of choice.

## Part E. In-app copy list (EN / ES), keys prefixed `gc_`

| Key | EN | ES |
|---|---|---|
| gc_title | Gem Cascade | Cascada de gemas |
| gc_mode_ascent | Ascent | Ascenso |
| gc_mode_garden | Garden | Jardín |
| gc_mode_sprint | Sprint | Carrera |
| gc_mode_journey | Journey | Viaje |
| gc_mode_riddles | Riddles | Acertijos |
| gc_mode_swirl | Swirl | Remolino |
| gc_mode_delve | Delve | Excavación |
| gc_mode_fireflies | Fireflies | Luciérnagas |
| gc_mode_fuse | Fuse | Mecha |
| gc_mode_thaw | Thaw | Deshielo |
| gc_mode_sigils | Sigils | Sellos |
| gc_mode_daily | Daily | Diario |
| gc_desc_ascent | Fill the bar, climb the levels. | Llena la barra y sube de nivel. |
| gc_desc_garden | Match at your own pace. Nothing ends. | Combina a tu ritmo. Nada termina. |
| gc_desc_sprint | Race the clock. Chains add seconds. | Compite con el reloj. Las cadenas suman segundos. |
| gc_desc_journey | Clear goals across 10 worlds. | Cumple objetivos en 10 mundos. |
| gc_desc_swirl | Rotate a block of four gems. | Gira un bloque de cuatro gemas. |
| gc_desc_delve | Dig down and collect relics. | Cava y recoge reliquias. |
| gc_desc_fireflies | Free the fireflies before the web. | Libera las luciérnagas antes de la red. |
| gc_desc_fuse | Defuse the bombs in time. | Desactiva las bombas a tiempo. |
| gc_desc_daily | One board a day, same for everyone. | Un tablero al día, igual para todos. |
| gc_gem_ember | Ember | Brasa |
| gc_gem_amber | Amber | Ámbar |
| gc_gem_sun | Sunbeam | Rayo de sol |
| gc_gem_moss | Moss | Musgo |
| gc_gem_frost | Frost | Escarcha |
| gc_gem_tide | Tide | Marea |
| gc_gem_orchid | Orchid | Orquídea |
| gc_sp_bloom | Spark Bloom | Flor de chispas |
| gc_sp_comet | Cross Comet | Cometa cruzado |
| gc_sp_core | Chroma Core | Núcleo de color |
| gc_sp_halo | Halo Crown | Corona de halo |
| gc_ob_cage | Cage | Jaula |
| gc_ob_frost | Frost | Escarcha |
| gc_ob_slate | Slate | Pizarra |
| gc_ob_fuse | Fuse bomb | Bomba de mecha |
| gc_ob_firefly | Firefly | Luciérnaga |
| gc_ob_relic | Relic | Reliquia |
| gc_ob_dirt | Dirt | Tierra |
| gc_booster_reshuffle | Reshuffle | Mezclar |
| gc_booster_smash | Smash | Romper |
| gc_booster_plus | +3 moves | +3 movimientos |
| gc_booster_hint | Lantern | Linterna |
| gc_hud_score | Score | Puntos |
| gc_hud_moves | Moves | Movimientos |
| gc_hud_chain | Chain x%d | Cadena x%d |
| gc_hud_time_left | %d s | %d s |
| gc_hud_speed | Speed x%d | Velocidad x%d |
| gc_hud_depth | Depth %d m | Profundidad %d m |
| gc_hud_blooms | Blooms %d | Flores %d |
| gc_goal_collect | Collect %s %d | Reúne %d de %s |
| gc_goal_clear | Clear %s | Despeja %s |
| gc_goal_free | Free fireflies | Libera luciérnagas |
| gc_goal_defuse | Defuse bombs | Desactiva bombas |
| gc_result_complete | Level %d complete | Nivel %d completado |
| gc_result_newbest | New best | Nuevo récord |
| gc_result_fail | So close. Try again? | Casi. ¿Lo intentas otra vez? |
| gc_result_finale | Finale bonus | Bono final |
| gc_result_save | Save a picture (stays on your device) | Guardar imagen (se queda en tu dispositivo) |
| gc_btn_play | Play | Jugar |
| gc_btn_practice | Practice (no clock) | Práctica (sin reloj) |
| gc_btn_next | Next level | Siguiente nivel |
| gc_btn_again | Play again | Jugar otra vez |
| gc_daily_streak | %d day streak | Racha de %d días |
| gc_daily_note | Missing a day never costs anything. | Faltar un día no cuesta nada. |
| gc_a11y_cell | %1$s %2$s, row %3$d column %4$d | %1$s %2$s, fila %3$d columna %4$d |
| gc_a11y_chain | Chain of %d | Cadena de %d |
| gc_parent_promise | No purchases, no ads, no lives, nothing leaves this device. | Sin compras, sin anuncios, sin vidas, nada sale de este dispositivo. |
| gc_look_hint | Try 2D, 2.5D or 3D. You can change it any time. | Prueba 2D, 2,5D o 3D. Puedes cambiarlo cuando quieras. |

Lint rule: no hard-coded English in `ui/*.kt` (scorecard finding 1); ES complete before MVP ships.

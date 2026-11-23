# Bot Bastion 3D: asset bible (art direction)

Status: DESIGN DRAFT, 2026-11-22. All designs are original. No existing game, film, toy or brand was used as a reference for shapes, names or palettes; if a future reviewer sees a likeness, change the design. Proof models and renders: `renders/` (Picket, Lobber, Skiff, Core, Drifter; Blender 5.1 headless; scripts included). Boards: `B3Assets`, `B3Sectors`, `B3InGame`.

## 1. Design language: "pocket diorama in a station window"

A tabletop of toy-like machines on a dark glass arena, lit by a warm key lamp and a cool rim, with small bright accents that say what each thing does. Think hand-sized hardware, not spacecraft realism.

Rules:

1. **Chunky and faceted.** 150 to 800 triangles per unit, flat shading, one bevel step on every hull edge. Visible facets are a feature (cheap, readable, consistent in baked and real-time renders).
2. **Three-part silhouette rule.** Every unit reads at 48 px as base + body + one identifying part: Picket = hex base + dome + single long barrel; Lobber = wide octagonal base + chunky block + fat tilted tube; Skiff = flat arrow hull + canopy + two glowing thrusters; Tender = round base + petal ring + central lens; Haze = round base + fan of three arc fins; Core = stepped plinth + white drum + glowing orb + four pylons.
3. **Accent glow = function.** Each bot has one accent colour on LEDs, the muzzle and the shell: cyan (Picket), amber (Lobber), violet (Haze), magenta (Skiff), mint (Tender), teal-green (Core and Aegis). Accent is never the only identifier (silhouette and ring style carry it too).
4. **Enemies are warm and rocky.** Drifters and kin are brown-rose rock with two ember-red eyes and a dark brow; they never use cool accent colours. Cool = ours, warm = theirs. This is the single most important colour rule.
5. **Materials are flat.** Principled flat colours, roughness 0.5 to 0.9, small metallic on steel parts, emissive only for accents. No image textures needed for the proof set; textures are optional later (256 px max).
6. **Lighting rig is part of the style.** Warm key from upper-left (about 3.2 intensity, colour 1.0/0.93/0.82), cool rim from behind (1.6, 0.45/0.7/1.0), small neutral fill (0.5), dark navy world. Sprites bake this rig; real-time uses the same values.
7. **Outline.** Optional dark inverted-hull outline at 1.5 to 2 px on bots and enemies for readability on phones (baked in R1, material pass in R2). No outline on terrain.
8. **No blood, no humans, no faces beyond simple eyes.** Defeated enemies pop into rock chips, dust and sparks.

## 2. Palette (shared with the 2D Bot Bastion set)

| Role | Hex | Use |
|---|---|---|
| Arena ink | `#0E1226` | world background, deep shadow |
| Plate navy | `#1A2352` | Cradle ground; also base for bot hulls `#2B3A72` |
| Steel | `#7C8AA8` | metal trims, rings, legs |
| Hull white | `#DCE4F5` | Core drum, rings |
| Cyan glow | `#4FE3FF` | Picket accent, lane edge (Cradle) |
| Amber glow | `#FFB547` | Lobber accent |
| Violet glow | `#B58CFF` | Haze accent, Hush sector |
| Magenta glow | `#FF4FD8` | Skiff accent |
| Mint glow | `#6BFF9E` | Tender and Core orb |
| Bronze | `#5A4020` | Lobber hull |
| Plum | `#6B2A68` | Skiff hull |
| Rock | `#7A5C58` / `#A8786A` | Drifter family |
| Ember red | `#FF6B5A` | enemy eyes, danger rings |

Contrast: accent on its hull >= 3:1; enemy silhouettes vs sector ground >= 3:1 (checked in the CVD tooling for protan, deutan, tritan, grayscale).

## 3. Units (what exists in the proof, what is specified)

| Unit | Status | Tris (proof) | Design notes |
|---|---|---|---|
| Picket | modelled, rendered, glTF | 288 | Hexagonal base, six rim LEDs, navy dome, single barrel with dark muzzle and cyan glow. Upgrade looks: L2 second sleeve ring, L3 side vents, L4 twin LED ring, L5 gold trim and brighter glow (overlays, not new meshes) |
| Lobber | modelled, rendered, glTF | 296 | Bronze octagon, steel upper deck, four feet with amber lights, block trunnion, tube at 55 degrees with collar and glowing shell. L-looks: longer tube, collar rings, amber core |
| Skiff | modelled, rendered, glTF | 188 | Plum arrow hull, cyan canopy, steel wings, dark twin guns, magenta thrusters, hovers 0.5 u |
| Core | modelled, rendered, glTF | 752 | Round plinth with steel ring, white drum with mint band, glowing orb in a steel cradle ring, four pylons with mint tips, antenna. State bands: 50 percent flicker, 25 percent cracks and red glints, fallen dim |
| Drifter | modelled, rendered, glTF | 220 | Jagged ico-rock, boulders, two ember eyes, dark brow, tumbles while moving |
| Haze | specified | 450 | Teal-violet round base, three arc fins like a radio dish, wide muzzle ring |
| Tender | specified | 450 | Round mint base, petal ring that spins, central lens, soft pulse ring |
| Aegis | specified | 400 | Standing shield generator beside the Core, hexagonal dome glow, stronger blue with shield |
| Darter | specified | 250 | Sharp shard with flame trail, smaller eyes |
| Plater | specified | 400 | Rock with overlapping armour plates, rivets, darker brow |
| Splitter / Bit | specified | 400 / 150 | Fissured rock with glowing crack; Bit is a pebble with one eye |
| Courier | specified | 300 | Rock with gold stripe and a little carried crate (a Special) |
| Behemoth | specified | 1800 | 3x Drifter, ring of armour plates, glowing furnace chest, slow heavy bob |

## 4. Sectors: environments, lighting variants, props

Palette = (ground, lane, key, rim, accent). Time-of-day grades (dusk, night, alarm) tint the cached layer. The proof renders in `renders/hearth_*_tilt.png` use the same Hearth Orbit layout in each look.

| Sector | Mood | Ground / lane | Key / rim | Accent | Props (12) | Ambient (living world) |
|---|---|---|---|---|---|---|
| **Cradle Reach** | A tidy relay station at a quiet harbour of asteroids; welcoming, tutorial | `#1A2352` / `#10173A` | warm gold / cool blue | cyan `#4FE3FF` | tumbling rocks, cargo pods, antenna masts, a dock arm, docking lights, a distant shuttle | dust motes, beacon pulse, rim chaser lights |
| **Glass Belt** | A crystalline field; cold, bright, sharp shadows | `#16384A` / `#0C2230` | icy white / aqua | ice `#9DEBFF` | crystal clusters (cones), floating ice shards, mirror panels, frost vents | crystal glints sweeping, ice shards drifting, aurora bands |
| **Ember Deep** | A volcanic trench station; hot, urgent, orange rim light | `#3A1A18` / `#241010` | amber / red-orange | ember `#FF8A3D` | vents with glowing caps, cooling fins, slag rocks, pipe racks | glow breathing, embers rising, heat haze band |
| **Hush Verge** | A quiet edge-of-everything lantern garden; calm but eerie late-game | `#2A1E52` / `#170F36` | pale lilac / violet | lantern `#B58CFF` (lilac-white lamps) | lantern poles with glowing globes, floating buoys, silent derelict rings, moss crystals | lantern bob, drifting mist, violet motes, pulsing far beacon |

Light variants per sector: **Dusk** (default, warm key), **Night** (cooler, lower key, brighter accents and glows), **Alarm** (boss waves: red rim added, pulse 0.7 Hz, only if Motion is not Calm).

Layout rules for props (enforced by `OcclusionRulesTest`): props sit on the arena rim and in dead zones, never within 0.25 u (screen space) of a lane strip or node pad, never taller than 1.2 u, and never in the lowest 1.5 u of the arena at e = 50 degrees (they would hide lane tiles).

## 5. Readability at tablet and phone sizes

| Surface | Unit size on screen | Check |
|---|---|---|
| Tablet 2560 x 1600 (landscape) | Bot about 90 to 110 px wide, node pad 100 px | All parts legible; LEDs visible |
| Fold inner 2176 x 1812 | Bot about 80 to 100 px | Plan view default if the window < 1.2 x height |
| Phone landscape 2400 x 1080 | Bot about 56 to 66 px (24 dp) | Silhouette test at 48 px: base + body + part still distinct; outline on; ambient reduced |
| Phone portrait | rotate prompt | Same as 2D |

Method: render each unit at 32, 48, 64, 96 px, convert to grayscale and view in CVD filters; each must still be identifiable among the others. The B3Assets board shows the 48 px strip.

## 6. FX language

| FX | Look |
|---|---|
| Picket shot | short cyan bolt, small additive halo, tiny recoil |
| Lobber shell | amber arc with shadow on the ground, big pop with ring and rock-dust |
| Haze spray | thin violet streaks, low contrast to avoid clutter |
| Skiff bolt | magenta dashes |
| Tender pulse | mint ring expanding, frozen enemies get ice-white facets |
| Hit | white flash 2 frames + 3 chips |
| Kill | rock chips (3 to 6) and a soft dust puff; Courier drops a spinning crate |
| Core hit | red flinch, shake (Standard and Lively), screen vignette; shield hit: blue hex ripple |
| Nova Pulse | single bright ring from the Core, one-frame white flash, no strobing |
| Upgrade | rising ring plus pips |
| Build | pad lights up, bot rises from the pad |

## 7. Asset hygiene

- Names: our own (Picket, Lobber, Haze, Skiff, Tender, Aegis, Drifter, Darter, Plater, Splitter, Bit, Courier, Behemoth, Core). Source-game names never appear in files or strings.
- Licensing: all geometry and materials created in the proof scripts; no downloaded models, textures or HDRIs. Any future stock asset needs a licence review and an entry in an assets ledger before it enters the repo.
- File layout (proposed): `art/botbastion/models/*.glb`, `art/botbastion/bake/*.py`, `assets/botbastion/<sector>/atlas.webp` + `manifest.json`.
- Budgets and gates: see `SPEC.md` section 4 and 9.

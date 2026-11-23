# Tower Defence: themes

Status: DESIGN DRAFT, 2026-11-22. Boards: `TDThemesA.dc.html`, `TDThemesB.dc.html`, `TDTowerEnemyCodex.dc.html` (skin map), `TDKidsAccess.dc.html`.

## 1. Rules for every theme

1. **Skins, not rebalances.** Each theme supplies sprites, names, palette, projectile style, sounds, ambient layers and copy. HP, speed, damage, cost, footprint and silhouette class (a flyer must read as a flyer) are fixed per role and archetype (`MAP-MODEL.md` section 9).
2. **Same four roles, same four archetypes.** Tower roles: Bolt, Blast, Control, Support. Enemy archetypes: Runner, Brute, Flyer, Boss.
3. **Left to right siege.** Enemies enter from the west; the theme's base object stands on the east edge.
4. **Original art and names.** No trademarks, franchise characters, real brands or third-party IP. Cultural themes are inspired by history and folklore; every unit name, place name and symbol below is invented. No real-world flags, religious symbols, slogans or letters (e.g. no SPQR text, no hinomaru, no runic alphabets that spell words).
5. **No gore in any theme.** Defeated enemies "poof" into a cartoon puff; kid-safe variants (section 4) go further.
6. **Colour is never the only cue.** Palette strips below are brand colours; gameplay cues use shape and dash (`ACCESSIBILITY.md`).
7. **One quality bar.** Every theme passes the new-pack gate in `docs/design/style-packs/SCRIPT.md` before it ships.

## 2. Theme sheet

Palette strips list six brand colours (background or sky, accent, light, warm, nature or secondary, highlight) as drawn in the boards.

| # | Theme | Setting | Base object | Age rating | Kid-first | Default order |
|---|---|---|---|---|---|---|
| 1 | Castle Siege | Medieval fantasy field and pass | Royal Castle | E10+ cartoon | no (kid variant on) | default, unlocked |
| 2 | Space Station | Orbital station, belt drones | Aurelia Station Core | E, robots only | yes | unlocked |
| 3 | Roman Frontier | Legion fort on a frontier road | Legion Fort | E10+ cartoon | no (kid variant on) | unlock 3 stars |
| 4 | Cherry Ridge | Feudal Japan-inspired mountain keep at dusk | Cherry Keep | E10+ cartoon | no | unlock 6 stars |
| 5 | Sunstone Oasis | Ancient Egypt-inspired desert temple | Sunstone Temple | E10+ cartoon | no | unlock 9 stars |
| 6 | Frostfjord | Viking-inspired winter longhall | Frostfjord Longhall | E10+ cartoon | no | unlock 12 stars |
| 7 | Toy Room | A child's room, toys on the move | Block Fortress | E, kids-first | yes | unlocked in kid profiles |
| 8 | Coral Reef | Undersea reef city | Coral Citadel | E, kids-first | yes | unlocked in kid profiles |

Unlock currency is stars earned by play, never money. Parents can unlock all (`docs/design/kids-games` conventions).

## 3. Per-theme detail

Tower order is Bolt, Blast, Control, Support. Enemy order is Runner, Brute, Flyer, Boss. Names are final working titles for review, not strings in code.

### 3.1 Castle Siege (default)

| Item | Detail |
|---|---|
| Story line | Hold the western pass. Ironhorde raiders, ember wyrmlings and a warlord storm toward your keep. |
| Towers | Archer Post, Trebuchet, Mage Spire, Banner Keep |
| Enemies | Raider (goblin with club), Bulwark Knight (tower shield), Ember Wyrmling (small dragon, flyer), Iron Warlord (horned, banner; boss) |
| Base | Royal Castle: blue-roofed keep and two flanking towers, gold pennants, gate with portcullis |
| Palette | Royal blue #3C6FD1, banner gold #F4C24A, stone #A3ABBE, meadow green #5FA85A, ember red #B8413C, sky #87B8F0 |
| Projectiles | Arrow with fletching, lobbed boulder, blue spark bolt, banner pulse ring |
| Ambient | Drifting clouds, flag ripple, grass sway, crow flocks, torches at dusk, village chimney smoke |
| Audio mood | Brass horns, snare march, birdsong between waves; stone thud on castle hits |
| Haptics | Soft thud per arrow hit, deep tick on castle damage, long rumble on boss |
| Kids-safe variant | Raiders become goblin cadets that tumble and poof into stars; Warlord wears an oversized helmet; no weapons visible on enemies |

### 3.2 Space Station

| Item | Detail |
|---|---|
| Story line | Aurelia Station is under drone assault from the dark side of the belt. Robots only. |
| Towers | Pulse Turret, Rail Mortar, Cryo Emitter, Relay Beacon |
| Enemies | Scout Drone, Armored Crawler, Wing Fighter (flyer), Dreadnought Skiff (boss) |
| Base | Aurelia Station Core with solar wings, ring and a shield dome |
| Palette | Deep space #0E1330, cyan #4FE3FF, magenta #FF4FD8, amber #FFB547, hull #D5DCEB, panel #7C8AA8 |
| Projectiles | Cyan laser line, amber rail slug, ice-blue pulse, green relay pulse |
| Ambient | Twinkling stars, drifting nebula, passing comet, blinking beacons, planet ring glints |
| Audio mood | Synth pad, soft pulses, UI blips, calm hum between waves |
| Haptics | Crisp short ticks; long low pulse on hull breach |
| Kids-safe variant | Already robots; defeated drones power down with sparkles instead of exploding |

### 3.3 Roman Frontier

| Item | Detail |
|---|---|
| Story line | The legion holds a frontier fort against hill clans, war hawks and a war elephant. |
| Towers | Ballista Tower, Onager, Oil Cauldron, Legion Standard |
| Enemies | Clan Scout, Chieftain's Guard, War Hawk (flyer), War Elephant (boss) |
| Base | Legion Fort: palisade, marble-and-terracotta gate, red banners, gold eagle standard |
| Palette | Crimson #A8242F, gold #E3B23C, marble #E8DFCB, terracotta #C2553A, olive #7A8B4A, sand #F1C98A |
| Projectiles | Bolt, lobbed stone, sticky oil glob, standard pulse |
| Ambient | Banner ripple, brazier smoke, swallows, cypress sway, distant aqueduct haze |
| Audio mood | Horns, war drums, cicadas, distant forum bustle |
| Haptics | Wooden ballista thunk, heavy stone drop on Onager blasts |
| Kids-safe variant | Clans become cheerful bandit pups; elephants wear party hats; no weapons shown on enemies |
| Naming note | "Roman" is the setting label only; no real legion numbers, slogans or lettering appear in art. |

### 3.4 Cherry Ridge (Feudal Japan-inspired)

| Item | Detail |
|---|---|
| Story line | Defend Cherry Keep at dusk from mist scouts, iron oni and the great tengu. |
| Towers | Kasumi Bow, Taiko Thunder, Sakura Shrine, Crane Banner |
| Enemies | Mist Scout, Iron Oni, Paper Kite Spirit (flyer), Great Tengu (boss) |
| Base | Cherry Keep: three-tier keep with curved tile roofs on a stone slope, red gate, blossoms |
| Palette | Tile blue-grey #2F3B52, lacquer red #C8372D, plaster #F2EFE6, blossom #F4A6B8, moss #6FA06B, brass #E8B84A |
| Projectiles | Arrow, drum shock ring, blossom burst, banner flutter |
| Ambient | Falling petals, lantern flicker, drifting mist, koi ripples, dusk tint |
| Audio mood | Taiko, shakuhachi, wind chimes, koi splashes |
| Haptics | Taiko pulse on Thunder hits, light tap on bow shots |
| Kids-safe variant | Scouts become paper-doll ninjas, oni are round and smiling, tengu has a big silly nose |
| Naming note | Folklore creatures only; no real clan names, mons (crests) or period flags. The crane on the banner is an original simplified mark. |

### 3.5 Sunstone Oasis (Ancient Egypt-inspired)

| Item | Detail |
|---|---|
| Story line | Guard the Sunstone Temple from sand scarabs, stone golems and a giant scorpion. |
| Towers | Obelisk Bow, Sunfire Bowl, Mirage Fountain, Sun Disc Standard |
| Enemies | Sand Scarab, Stone Golem, Dust Falcon (flyer), Giant Scorpion (boss) |
| Base | Sunstone Temple: sandstone pylons, obelisks, winged sun disc over the gate |
| Palette | Sand #E8C58A, turquoise #2BB5B0, gold #F2C14E, lapis #2D4FA3, sandstone #D9A55F, umber #6B4423 |
| Projectiles | Arrow, fireball, water arc, sun-disc glint |
| Ambient | Heat shimmer, sand streaks, circling falcon, palm sway, pyramid haze |
| Audio mood | Frame drums, oud, dry wind, scarab clicks |
| Haptics | Dry crack on Obelisk shots, rumble when the golem walks |
| Kids-safe variant | Scarabs are shiny toy beetles; scorpion wears a bow tie |
| Naming note | No real deities, pharaoh names or hieroglyphic text. The sun disc is a generic shape. |

### 3.6 Frostfjord (Viking-inspired)

| Item | Detail |
|---|---|
| Story line | Hold the longhall through a long winter against wolves, ice trolls and a frost giant. |
| Towers | Longbow Watch, Boulder Hurler, Rune Totem, Hearth Brazier |
| Enemies | Frost Wolf, Ice Troll, Frost Raven (flyer), Frost Giant (boss) |
| Base | Frostfjord Longhall: timber hall under a snow roof with carved gable prows, shields, glowing torches |
| Palette | Snow #EAF2FA, ice #A5D8F0, rune cyan #5FE0FF, timber #7A4F2E, fir #2F8F83, fire #FF9A3C |
| Projectiles | Arrow, hurled boulder, rune glints, ember sparks |
| Ambient | Snowfall, aurora ribbons, chimney smoke, torch flicker, breath puffs |
| Audio mood | Low horns, fiddle, wind, creaking timber |
| Haptics | Dull thud for boulders, bright tick for ice runes |
| Kids-safe variant | Wolves are fluffy huskies, trolls are snow-golems; no horned helmets on enemies |
| Naming note | Runes in art are decorative marks that spell nothing. |

### 3.7 Toy Room (kids)

| Item | Detail |
|---|---|
| Story line | The toys are on the move! Stop the grumpy wind-up bots before they knock down the block fortress. |
| Towers | Marble Shooter, Pillow Cannon, Bubble Blower, Night-light |
| Enemies | Wind-up Bot, Block Brute, Paper Plane (flyer), Mega Wind-up (boss) |
| Base | Block Fortress: stacked wooden building blocks, flag, blanket drawbridge |
| Palette | Toy red #FF5A5F, blue #3A86FF, yellow #FFD23F, green #3DDC97, purple #8E5BFF, wood #E9C58B |
| Projectiles | Marbles, soft plush balls, bubbles, star glow |
| Ambient | Bouncing ball, dust motes in a sunbeam, night-light glow, spinning mobile |
| Audio mood | Xylophone, music-box, squeaks and pops; no sudden loud sounds |
| Haptics | Gentle pops; strongest cue is a soft double tap |
| Kid rules | No timers or countdowns, no losing (castle "gets tired" and a free retry appears), only stars and coins as numbers, 64 dp targets, confetti pops, big friendly voice prompts (no reading required) |

### 3.8 Coral Reef (kids)

| Item | Detail |
|---|---|
| Story line | Protect the Coral Citadel from pufferlings, shell crabs, glider rays and a great octopus. |
| Towers | Pearl Shooter, Conch Cannon, Whirl Current, Glow Lantern |
| Enemies | Prickle Pufferling, Shell Crab, Manta Glider (flyer), Great Octopus (boss) |
| Base | Coral Citadel: pearl-white shell palace with coral spires and a pearl gate |
| Palette | Reef blue #1E7FA8, deep #0B3A5C, coral #FF6F61, sand #F2D6A0, teal #3EE0C4, violet #8E6BD8 |
| Projectiles | Pearls, spray of bubbles, swirl, glow pulse |
| Ambient | Rising bubbles, swaying light rays, fish schools, kelp sway |
| Audio mood | Marimba, bubbles, soft whale song, gentle stingers |
| Haptics | Bubbly ticks, slow wobble on boss hits |
| Kid rules | Same as Toy Room; enemies giggle and swim away rather than being "defeated" |

## 4. Kids-safe variants

Two layers:

1. **Kid-first themes** (Toy Room, Coral Reef) are authored kid-safe from the start and are the default in kid profiles.
2. **Kid variant switch** for the other six themes: replaces enemy art with cartoon stand-ins (listed per theme above), removes visible weapons on enemies, softens castle-hit audio, removes the screen shake and red vignette, and uses the Gentle difficulty tier by default. Controlled by the kid profile (and Parent settings), not by a toggle the child can reach.

Age rating labels shown on the theme card: "E" (everyone), "E10+ cartoon" (cartoon action, no blood). Labels are informational copy in the picker, not an external rating claim; store listing ratings are set by the existing release checklist.

## 5. Naming and IP check (to run before any art is finalised)

- Every unit, place and tower name is on the lists above and was written for this project.
- No theme uses a name from a known franchise (e.g. no "Orc"-style franchise lore, no named gods, no famous fictional ships or stations).
- Palette and silhouette distance check against the nearest well-known game in each setting is part of the new-pack gate.
- Sound uses the existing synthesised palette approach (`docs/design/style-packs/SCRIPT.md`, `shared-platform.md`); no sampled commercial music.

## 6. Where themes plug in

| System | Hook |
|---|---|
| `StylePack` | One pack per theme per art style tier; `StyleNeeds` declare BOARD_SURFACE, PIECE, BACKGROUND, CHROME, CELEBRATION, SOUND_PALETTE, HAPTIC_PROFILE |
| Settings | Theme is per-game (Tower Defence) with a global default of Castle Siege; Art style, Motion level and Quality stay global with per-game override |
| Stats | Theme is not in the stats key |
| Localisation | Theme strings in `strings.xml` EN and ES; names above are English working titles |

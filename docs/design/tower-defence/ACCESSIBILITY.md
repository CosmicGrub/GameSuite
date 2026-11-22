# Tower Defence: accessibility

Status: DESIGN DRAFT, 2026-11-22. Board: `TDKidsAccess.dc.html`, with layout notes in `TDTabletFold.dc.html` and gesture notes in `TDOpenPlacement.dc.html`. Follows `docs/roadmap/QUALITY-BAR.md` gate 5 (48 dp targets, TalkBack labels for every cell, colour-blind safe with colour plus shape, large-font safe, all rotations, Fold inner and cover) and the existing `LocalReducedMotion`, `LocalColorblindMode`, `GameSpeed` settings.

## 1. Touch targets

| Control | Size | Notes |
|---|---|---|
| Tray card | 96 x 64 dp (Large targets: 96 x 82 dp) | 12 dp gaps; label and cost inside the card |
| Pause, speed | 56 dp | not on the field edge |
| Upgrade panel buttons | 48 dp tall minimum, 130 dp wide | |
| Radial menu spokes | 64 dp, 8 dp gap | slide-select, release to confirm; also reachable by tap-tap |
| Targeting chips | 48 dp tall | |
| Placement ghost handle | touch area 64 dp around the offset finger point | ghost offset 56 dp above the finger |
| Placed tower | footprint plus 12 dp halo, minimum 48 dp | the grid cell is the hit area when Snap is on |
| Toy Room | 64 dp everything, tray cards 120 dp | 3 big buttons; no precision gestures |

"Large targets" setting scales the HUD by 1.25 and tray cards to 82 dp, and increases the tower halo to 20 dp. All controls respect font scale up to 200 percent without clipping (labels wrap; chips grow).

## 2. Colour-blind safe signals

The field is never decoded by colour alone. Rules:

| Signal | Colour | Non-colour cue |
|---|---|---|
| Selected tower range | white or accent | solid stroke 3.4 px plus an inner ring |
| Other tower range | white at 55 percent | dashed 7 on 6 off, 1.8 px |
| Enemy reach (Sandbox and Daily hazards) | orange | dotted stroke plus a triangle glyph on the ring |
| Valid placement | green | check glyph, solid cell outline |
| Blocked placement | red | X glyph, dashed outline, plus a text toast |
| Enemy health | green to amber to red | bar length and a notch at 50 percent and 25 percent |
| Ownership (Co-op) | blue and orange | circle versus square marker beside the tower |
| Role | accent per theme | distinct silhouette and a role icon in the tray |
| Affordability | cost chip dim | coin-X glyph and "need N" text on tap |

`LocalColorblindMode` swaps the accent set for the colour-blind-safe palette already used elsewhere (blue, vermillion, yellow, bluish green) while keeping all shapes. The board shows deuteranopia, protanopia and tritanopia simulations of the Castle Siege scene and monochrome ring swatches; the checks to run on every theme are listed in `THEMES.md` section 5.

Enemy readability: each archetype has a unique silhouette class (small runner, wide brute, winged flyer, large boss with banner or crest) that survives monochrome; flyers also cast a ground shadow offset below the sprite.

Contrast: HUD text on glass pills is at least 7:1; rings gain an inner dark stroke at Night, Rain and on bright snow; icons carry a 1.5 px dark outline.

## 3. Reduced Motion

OS Reduced Motion always wins over the Motion level. Effects removed: parallax, ambient loops (frozen on a still frame), screen shake, banner slides (fade instead), confetti and fireworks (static sticker burst), projectile trails (instant hit flash), level-up burst (ring highlight), gate glow pulse. Retained: enemy movement at the same speed (it is gameplay), state changes announced in text. Flashes never exceed 3 per second at any level. Speed setting (1x, 2x, 3x) is independent; a "Slower" 0.5x option is added for players who need more time (build phase can also be unlimited via Practice).

## 4. TalkBack and the field as a grid

The canvas exposes a virtual semantic tree so the field is navigable by swipe and by explore-by-touch.

Structure:
1. Wave status, castle status, gold (live regions).
2. Next-wave card.
3. Tray (four buttons with cost and affordability state).
4. Towers (list, in order of distance to the gate).
5. Enemies (list, closest to the gate first, max 8 announced; the rest summarised).
6. The grid (14 columns A to N by 8 rows 1 to 8; each cell is about 3.4 x 3.5 nav cells), navigable with cursor-style swipe and by touch exploration.

Announcements (templates in `SCRIPT.md` section 4):

- Cell: "Row 4, column G. Empty, buildable. Route passes 2 tiles east. Double tap to place Archer Post, 50 gold." Variants: "Occupied by Mage Spire, level 3", "Not buildable: water", "Route cell: building here would block the gate" (derived from the validator in `MAP-MODEL.md`).
- Tower: "Mage Spire, level 3 of 5, Control. Targeting First. Actions: upgrade 140, sell 85, move, change targeting." Actions are `AccessibilityAction`s on the node, so no radial gesture is needed.
- Enemy: "Brute, 60 percent health, 4 tiles from the castle."
- Wave events: "Wave 6 of 10 started, 14 enemies. Castle 17 of 20." Castle damage and boss arrival announced politely (not interrupting).
- Result: "Siege broken. Two stars. Next map button."

A **list view** alternative ("Towers, Enemies, Free cells nearest the route") offers the same actions for users who prefer lists to a grid. It is reachable from the pause sheet and is the default when TalkBack is on and the field gesture count is high.

Rules: every interactive node has role, state and action; no information exists only visually (route preview has a text equivalent "route passes 2 tiles east of G4"); live regions do not announce more than once every 2 s; TalkBack disables auto-follow camera jumps unless the user focuses the map.

## 5. One-switch and keyboard

One-switch (Switch Access or the app's own auto-scan):

1. Scan columns (a vertical band sweeps left to right, default 0.8 s per column, adjustable 0.5 to 2 s). Press to lock a column.
2. Scan rows within that column. Press to lock a cell; the ghost shows the route check live.
3. Scan the tray cards in order, each announced with cost. Press to build.
4. Cell is built; scan returns to the next-wave card, so the wave can be called or the cycle repeated. A long press or a second press during step 1 cancels.

Pause and the next-wave card are scan stops one and two. Inside the radial menu, scanning steps through spokes. The "Practice" setting removes build-phase timers and slows waves to 0.5x for switch users.

Keyboard and D-pad: arrow keys move the cursor one nav cell, Enter places, Tab cycles tower, Escape cancels, Space pauses, number keys select tray cards.

## 6. Cognitive and kid accessibility

- No timers in Toy Room; no timers or lose state for Gentle tier in kid profiles (`SCOPE.md`).
- Plain language, one instruction at a time, read-aloud option for every prompt.
- Errors explain themselves ("It would seal the gate").
- Consistent positions: tray bottom centre, next-wave top-left, castle right, castle health always above the gate.
- No dark patterns: no ads, no paid boosts, no streak loss penalties on Daily.

## 7. Verification checklist

| Check | Method |
|---|---|
| 48 dp minimum, spacing | layout inspector, 1.0 and 2.0 font scale, large-targets on |
| TalkBack walk | scripted pass on the grid, tray, panel, results; list view alternate |
| Colour-blind | simulations in the board plus real device settings, per theme |
| Reduced Motion | OS setting on; verify each removed effect and the retained ones |
| Switch Access | external switch pass; auto-scan timing |
| Fold | inner, cover, tabletop and book posture, rotation, safe areas around the hinge |
| Photosensitivity | automated flash counter on boss, castle hit, victory |
| `design:accessibility-review` | run before each theme wave merges |

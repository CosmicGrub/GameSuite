# Style system: integration notes (Phase 1, Gem Cascade pilot)

Branch `session/ws-style`. Status of what is built vs open: `docs/design/style-packs/IMPLEMENTATION-STATUS.md`.

## Edits to shared files (all minimal)
1. `ui/SettingsScreen.kt`: new `secStyle`/`showStyle` values and one call `com.gamesuite.style.ui.StyleLookSection()` before the Sound section; `anyVisible` includes `showStyle`.
2. `ui/GemCascadeScreen.kt`: now drives its look through the style system (large diff: `play()` uses `GemTimeline`, the Canvas calls `GemBoardPainter`, `GemAnim` moved to `style/gem/GemRenderState.kt`).
3. `ui/GemCascadeArt.kt`: `drawGem` split into `drawGemSpecial`, `drawGemCage`, `drawGemTimer` (same output); `GemPaints.path2` added.

NOT edited: `GameHubSpecs`, `GameCatalog`, `GameTheme`, `MainActivity`, `GameTips`, `GameCoach`.

## Nothing to register
`StyleViewModel` is created with `viewModel()` inside the screens, so no MainActivity change is needed. The style DataStore `style_prefs` uses `gameDataStore`, so it is per profile automatically.

## Later integration (when more games adopt the system)
- Add the game to `GameStyleSupport.table` (surfaces it uses, max tier) and, for 3D, a pack in `StylePackRegistry` with its asset id.
- Optionally provide `LocalStylePack` once in `MainActivity` (it is declared, default Classic); until then call `rememberGameStyle(gameId)` in the screen.
- Call `GemAtlasCache.trim()` from `onTrimMemory` (not wired).
- Game setup "Look" row, kid parent-gate and `KidControls` policy, Play Asset Delivery: open.

## Regenerating the 3D atlas
`blender -b --python scripts/gen_gem_cascade_atlas.py -- --out <abs path to app/src/main/assets/styles/gem-cascade>` (use a Windows absolute path under Git Bash). Manifest format: `size N` then `name x y w h` lines.

## Open / verify on a device (self-run kit)
Visual pass of Flat, 2.5D, 3D on tablet, Fold inner/cover, mid-range phone; picking and TalkBack focus alignment on the tilted board; frame times; Reduced Motion (no tilt, no bursts); switching look mid-level from the chrome menu; atlas missing -> 2.5D.

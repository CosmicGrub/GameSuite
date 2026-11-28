# Bot Bastion 3D sprite pack: integration notes (branch session/ws-bb3d)

Status 2026-11-22: Phase 1 ASSETS done and committed; no Kotlin renderer work here (other session builds the style
infrastructure on session/ws-style and wires the atlases).

## Done
- `scripts/bake_bot_bastion_atlas.py` + `scripts/bot_bastion_3d/{bb_kit,bb_units,bb_sectors,bb_procedural,bb_bake}.py`
  (Blender 5.1 headless + Pillow/numpy). Re-run reproduces the pack byte-for-byte except WebP encoder differences.
- `app/src/main/assets/styles/bot-bastion/`: `manifest.json`, 8 WebP atlases, README.md (conventions, licence).
- `app/src/test/java/com/gamesuite/style/BotBastionAtlasManifestTest.kt` (manifest, hashes, bounds, anchors, budget, roster).

## Open / not done
- Bots have no baked build/fire/upgrade strips: the renderer should do recoil (offset the head 2-4 px), LED pulse
  (additive `glow_*`), build rise (translate + `fx_build_ring`) in code. Muzzle flashes and rings are provided.
- Boss `roar`/`die` staged strips, Tender freeze-state ice facets on enemies, and 16 facings (8 baked) were not baked.
- Occlusion-outline silhouettes: tint the sprite alpha at runtime instead of a baked silhouette.
- Units are lit with one neutral rig, not per sector; environment sprites are baked per sector and light.
- No device checks (standing rule); desktop visual review only (contact sheets rendered during the bake).

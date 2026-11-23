# SVG to Compose: draft procedure (not executed)

Part of docs/design/style-packs, SCRIPT.md Part C1. This is a procedure, not an automated script, because the repo's convention is mostly
hand-drawn Compose `Canvas` code (QUALITY-BAR: "vector drawn in Compose/AGSL, original"). Use ImageVector only for static ornament, icons, suit glyphs and
decorative illustrations.

## 1. Author rules (so conversion is lossless)

- Export plain SVG 1.1: paths, rects, circles, polygons only. NO filters, masks, clip-path chains, blend modes, embedded rasters, text (convert text to paths).
- Fixed viewBox on an integer grid (24, 48, 96, 256, or 512). One unit = one dp at the nominal size.
- Gradients: linear/radial only, at most 3 stops (Compose `Brush` supports these; Android Vector Drawable supports a subset).
- Fill colours: use only the pack's token colours (a palette file). Never bake `#000000`/`#FFFFFF` text or outlines: they must follow theme (High Contrast, dark mode).
- Colour-blind shape cue: a separate layer/group named `cb-shape` so the renderer can draw it only when `LocalColorblindMode` is on, OR draw it in Compose on top.
- Keep path data under ~2 KB per element; simplify curves in the editor first (path count budget in `style-budgets.json`).

## 2. Conversion options

| Option | Output | Good for | Notes |
|---|---|---|---|
| Android Studio -> New -> Vector Asset -> Local file (SVG) | `res/drawable/*.xml` VectorDrawable | icons, simple shapes | Imports a SVG subset; check the import warnings panel; not all gradients/clip paths survive |
| Hand port to `ImageVector.Builder` (Kotlin) | `ImageVector` in code | suit glyphs, small ornaments | `PathParser` from the SVG `d` string, then `addPath(...)`; preferred when you need tint and multiple states |
| Third-party svg-to-compose converter tools | `ImageVector` Kotlin | bulk conversion | UNVERIFIED which tools are maintained and which Compose version they target: evaluate one on 3 sample SVGs before adopting; pin the version |
| Draw directly in `Canvas` | Kotlin draw code | anything stateful or animated | current repo convention |

Do not assume a converter is trustworthy: always compare the rendered result with the source SVG (golden screenshot if D6 approved, otherwise owner review).

## 3. Post-conversion checklist

1. Replace literal `Color(0xFF...)` with pack/theme tokens (or `LocalContentColor` tint for monochrome glyphs).
2. Set `viewportWidth/Height` equal to the SVG viewBox; set `defaultWidth/Height` in dp sensibly.
3. Add `contentDescription` at the use site (decorative art gets `null`; meaningful art needs a string resource in EN and ES).
4. Check the vector at 0.85x-1.5x text scale layouts and at `LocalCardScale` extremes (no clipped strokes).
5. Cache: expose the vector via `remember`/top-level `val` (`ImageVector` is immutable and reusable); never rebuild per frame.
6. Record the source SVG path, author, and licence in `LICENSES.md`; keep SVG masters outside `src/main`.
7. Verify size: `asset_size_gate.py` counts vector XML/Kotlin bytes toward the pack budget.

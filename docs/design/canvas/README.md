# GameSuite premium redesign canvas (source files)

Source of the design canvas published as a claude.ai Design artifact (24 + 6 artboards): design system (Midnight Club),
markup/redlines, core flow, progress/social, kids and family, tablet, and the five-state storyboards for the new games
(Slide and Climb, Draw and Guess, Echo Pads, Act It Out, Bridge Builders) with a markup board.

- `canvas.json` is the board index (positions, titles, notes). Each `*.dc.html` is one artboard.
- These are design intent only; none of it is wired into the app. Known mismatch: Echo Pads mockup colours differ from the
  shipped colour-blind-safe palette (blue, vermillion, yellow, bluish green).
- Published canvas: https://claude.ai/artifact/QxFjzpecD9dmBtVPHzJizF (private to the owner).

## Added 2026-11-22: refinement and style-system boards
- `RF*.dc.html` (21 boards): quality-refinement mockups and redlines from the 81-game scorecard (docs/audit/08-quality-scorecard/): program overview, GameChrome v2, unified deck, motion/sound/haptic/accessibility spec, score card v2, and per-family refinements (board games, cards, puzzles, words, arcade, toy sports, party/kids, tablet/Fold).
- `ST*.dc.html` (12 boards): the proposed user-selectable style system: settings and in-game choice, parent/kid rules, tablet layout, and every main game in flat / 2.5D / 3D plus the motion levels and a game-by-style support matrix. 3D views are CSS approximations.
- Related docs: docs/research/art-and-animation/ (R-A 3D rendering, R-B 2D animation and assets) and docs/design/style-packs/ (scope, architecture, scale, script, game-by-game matrix).

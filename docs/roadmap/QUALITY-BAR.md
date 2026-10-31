# Quality bar: match Clubhouse Games (owner requirement, 2026-10-31)

"Every game must feel as polished as a first-party console bundle." A game is NOT done until every gate below passes.

## Gates (per game)
1. Rules: engine matches published rules; known-value tests (perft, reference positions, scripted full games); fuzz/property tests
   (random legal play never throws, snapshot/restore round-trips, undo restores exact state). Adversarial pass (anthropic-skills:adversarial-verification).
2. CPU: three+ distinguishable levels; Easy is beatable and human-feeling, Hard is genuinely strong; never blocks UI; think-time feels natural (min delay ~400ms, animated).
3. Feel: every piece move animated (ease, slight overshoot where fitting), sound + haptic on place/capture/win/lose, win/lose celebration, subtle idle motion;
   all respecting LocalReducedMotion and LocalGameSpeed. Original art with a consistent per-game material look (wood/felt/enamel etc.), not flat debug shapes.
4. Teaching: first-run coach card (how to play + history trivia), legal-move highlights, hint button, undo, "why" message on illegal taps.
5. Accessibility: 48dp targets, TalkBack labels for every cell/piece/state with announcements for moves, colorblind-safe (color + shape), large-font safe,
   works in all rotations and on Fold inner/cover screens; design:accessibility-review pass.
6. Multiplayer: Nearby + Online with host authority, reconnect, resume, hidden info redacted, spectators where it makes sense.
7. Localization: complete EN + ES, no hard-coded strings.
8. Verification evidence: unit tests green, emulator screenshots in portrait + landscape + large font + dark mode, one real-device pass (tablet + Fold)
   before a wave merges; code review (coderabbit:code-review / code-review) clean of P1/P2.

## Tools to use
- Emulator + Z:\Dev\gs-sweep\tools (smoke_all/devtest/netlib) for screenshots and flows; real tablet + Fold for the multi-device passes.
- Skills: adversarial-verification, playtesting-a-feature, verification-before-completion, design:design-critique, design:accessibility-review,
  mobile-app-ui-design, ui-ux-pro-max, coderabbit:code-review, code-review, simplify.
- Connectors needing owner authorization (unavailable until authorized): Figma, Adobe, Canva (design/art/icon assets).
- Art direction: vector drawn in Compose/AGSL, original; no Nintendo/Mario assets.

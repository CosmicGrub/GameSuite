# ws-plat2 integration notes (branch session/ws-plat2)

Done (see commits): P6 fitBoard `cellAspect` + `HitSlop`/`hitTestWithSlop` (shared BoardFit.kt, tests in BoardFitTest);
Air Hockey body no longer recomposes per frame (derived slices in body, live state read in Canvas/offset lambdas);
Toy Soccer, Toy Tennis, Slot Cars, Fishing on `rememberGameFrameClock` + `PausableGame` (overlay pause);
`DailySeed.forTier` applied per difficulty in Sudoku, Minesweeper, KenKen, Kakuro, Nonogram, Lights Out,
Sliding Puzzle, Color Flood, Mastermind, Edge Match, Word Search (stored dailySeed unchanged; tier mixed at Random creation);
English literals -> `plat2_strings.xml` (EN+ES) for Chess, Connect Four, Dominoes, Dots and Boxes, Reversi, Tic-Tac-Toe, Air Hockey; baseline regenerated.

Open: MainActivity `*-daily` routes untouched (shared registration file; tiering is inside the games). Flow Lines already tiers
(FlowGenerator.dailySeed). Gem Cascade / Spider / Six-Ball have no difficulty tier. EnglishLiteralRatchetTest scanner regex
`[^"\n]` excludes the letter `n` and backslash, so literals containing "n" are not counted (real debt is understated); not changed here.
Not verified on device (no installs authorised).

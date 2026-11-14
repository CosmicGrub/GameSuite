# Research: games for 5-year-olds, and our parity plan

Status 2026-11-07. Purpose: find what works for ages 4-7 playing with other children, adults and family, and define original GameSuite equivalents.

## Provenance and limits (read first)
- The requested geeklist (boardgamegeek.com/geeklist/307608) could NOT be read. BoardGameGeek returns HTTP 403 to automated requests and shows a "verify you are human" check in a browser. We do not bypass bot checks, and the site's terms do not permit bulk scraping, so its game pages and sublinks were not crawled.
- Substitute sources (open pages, read normally): The Tabletop Family "best board games for five year olds" and Dad Suggests "5 best cooperative board games for 5 year olds". A third list (Robots Over Dinosaurs) also refused automated access.
- Player counts and play times were mostly NOT stated in those sources; they are marked unknown and must be taken from the owner's export or publisher pages before any parity claim is final.
- To complete the BGG research: owner exports the geeklist (save the page as HTML, or paste the item titles and comments) into docs/research/inbox/. Then each item gets a row below.
- Legal: we reproduce mechanics (not protected) and never names, art, card text, rulebook wording or layouts. All equivalents get original names and art.

## Games seen, grouped by mechanic family
| Family | Originals seen | Core loop (our reading) | Coop or competitive |
|---|---|---|---|
| Cooperative path race against a clock | Hoot Owl Hoot, Race to the Treasure, Mr. Postmouse's Picnic, Karuba Junior | Play a card, extend or move along a path, beat an opponent (sunrise, ogre, pirate ship) | Cooperative (Karuba Jr: race) |
| Cooperative deduction | Outfoxed | Gather clues, eliminate suspects, catch the thief before escape | Cooperative |
| Memory | Monster Chase | Remember which toys scare which monsters | Cooperative |
| Visual search / matching | Eye Found It, Spot It | Find hidden items or the matching symbol | Mixed |
| Dexterity stacking | Animal Upon Animal | Place pieces without toppling | Mixed |
| Majority / set collection | Mooki Island, Oh My Socks, Azul Kids, Tucano | Choose pieces, score majorities or even sets, push your luck | Competitive |
| Roll and write | That's Pretty Clever Kids, Stamp Farm | Roll dice, mark a sheet for points | Competitive |
| Abstract placement | Gobblet Gobblers, My First Carcassonne | Place or stack to make lines or close shapes | Competitive |
| Peg-jump puzzle | Sheep Hop | Jump pieces to bring all home | Solo or coop |
| Dice race | Monza, Ticket to Ride First Journey | Roll and advance, claim routes | Competitive |
| Emotion / language | Dixit Kids | Match a card to a mood and guess | Cooperative |
| Physical probability | Dragon's Breath | Remove a ring, collect the gems that fall | Mixed |
| Spatial puzzle | Katamino Family | Fit shapes into a grid | Solo or race |

## Parity plan: original equivalents
Parity means the same core loop, age fit and player experience, authored fresh. "Done" column = already shipped or in this program.

| Our game (id) | Matches | Players | Ages | Mode | Priority | Status |
|---|---|---|---|---|---|---|
| Trail Together (`trail-together`) | Hoot Owl Hoot, Race to the Treasure, Mr. Postmouse, Karuba Jr | 1-4 | 4+ | Coop | P1 | scoped in this round |
| Pie Detective (`pie-detective`) | Outfoxed | 2-4 | 5+ | Coop | P1 | scoped in this round |
| Find It Frenzy (`find-it`) | Eye Found It, Spot It | 1-4 | 4+ | Coop or race | P1 | scoped in this round |
| Stack Zoo (`stack-zoo`) | Animal Upon Animal | 1-4 | 4+ | Coop or turns | P1 | scoped in this round |
| Hop Home (`hop-home`) | Sheep Hop (puzzle) | 1-2 | 5+ | Solo or coop | P1 | scoped in this round |
| Scare Away (`scare-away`) | Monster Chase | 1-4 | 4+ | Coop | P1 | scoped in this round |
| Fruit Pickers (`fruit-pickers`) | Tucano, Oh My Socks | 2-4 | 5+ | Push-your-luck | P2 | later |
| Mood Match (`mood-match`) | Dixit Kids | 2-6 | 4+ | Coop | P2 | later |
| Stamp Sheet (`stamp-sheet`) | Stamp Farm, Clever Kids | 1-4 | 6+ | Roll and write | P2 | later |
| Stack Tac Toe (`nested-tac`) | Gobblet Gobblers | 2 | 5+ | Competitive | P2 | later |
| Colour Cars (`colour-cars`) | Monza | 2-4 | 4+ | Dice race | P3 | later |
| Gem Drop (`gem-drop`) | Dragon's Breath | 2-4 | 5+ | Physics-lite | P3 | later |
| Shape Squeeze (`shape-squeeze`) | Katamino | 1-2 | 6+ | Puzzle | P3 | later |
| Already covered | Ludo/Trouble family, Memory/Matching, Tap Race etc. | | | | | shipped |

## Design lessons to carry into every kids game
1. Cooperative first: a lost game is "try again together", never a loser. Five of the top families above are cooperative.
2. No reading: every state shown by picture, colour+shape and sound; words are for the grown-up.
3. A game is 5-15 minutes; sessions end cleanly and resume.
4. Randomness with a little choice: luck decides, but each turn offers one meaningful pick so children feel agency.
5. Fairness across ages: handicap row in setup, same wording everywhere.
6. Physical feel: big targets, drag and drop, satisfying sounds and haptics, Reduced Motion alternative.
7. Parent value: a one-line "what this teaches" shown in the parent dashboard (memory, counting, planning, deduction, fine motor).

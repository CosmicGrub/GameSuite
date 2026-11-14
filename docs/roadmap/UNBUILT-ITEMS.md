# Unbuilt items (as of 2026-11-07)

Source: roadmap docs, wave-5 design, QA reports, code-review report. "Built" = compiled and unit-tested; a lot of it is still unverified on real devices (see section G).

## A. Wave 5 multiplayer (design approved, O1-O13 decided)
- S1 client side: `OnlineTransport` wire messages, `OnlinePlayer.avatar`, peek / lock / kick / started handling
- S2 friend-room UX: share and copy room code, deep link `gamesuite://join?...` (+ intent filter), paste field, real avatars, peek preview, lock/kick UI, `core/RoomCode.kt`
- S5 quick-chat client: overlay in `GameChrome`, Nearby 0x00 prefix, settings (server side is built)
- S8 Quick match client: `OnlineRatingStore`, `QuickMatchScreen` (turn-based games only)
- S9 two-emulator end-to-end harness `scripts/e2e-two-emulators.py`
- S10 voice-readiness docs
- Always-on relay hosting (O1): not deployed; owner must choose host and payment. Docker image, hosting and CI relay workflow never run.
- Nearby: ready check and remove-player not built; Online remove-player uses a host-stamped Rejected instead of the relay `kick` frame
- Idle-turn timer (deferred)
- Spanish for lobby problem messages (`lobbyGameProblem` is English)

## B. Roadmap items never started
- Voice chat (owner: LAST)
- Takoyaki-style and Pig's Tail-style mini games (left out as unclear)
- Mosaic-style multi-device play (Air Hockey table across devices)
- Wireless/online versions of the party pack (all 8 are same-screen only; not in `networkedGames`)
- Phone-as-controller mode for the party pack
- Backgammon and Yahtzee networking
- TicTacToe / Chess guest StateSync and PlayAgain gaps (handed off)
- Candidate new games (owner has not chosen): Go, Xiangqi, Battleship, Quoridor, Hearts, Spades, etc.
- Wave 3 brainstorm leftovers: Reversi variants done; Flow Free-style etc. status per `NEW_GAMES_BRAINSTORM_WAVE_3.md`
- Scoring-honesty and "Premium 2026 vision" items the owner declined (true 3D / Filament) stay out unless re-opened
- ESP32 cabinet hardware items (audio, addressable LEDs, etc.) were never built

## C. Known defects and shortcuts left in built features
- Spider daily deal depends on device speed (12 s wall-clock cap; needs a step budget)
- Toy Tennis and Paddle Duel CPU levels lopsided (Paddle Duel Easy too hard)
- `GameSpeed` enum labels English only; shared "How to play" sheet "Got it" below the fold on some Hub sheets
- `hub_same_screen` string only defined in one party strings file (hidden dependency)
- Games with several equal winners (Word Tiles, Dominoes ties, Word Search) still record WIN for each
- Hub setup page: Start button not reachable in landscape at 1.6x font
- Host lobby shows raw relay error text; landscape 1.6x font leaves the table a sliver
- Whack-a-Mole holes ~26 dp in phone landscape; Paddle Duel court and Light-Trail portrait arena waste space
- Reaction Duel / Paddle Duel occasional blank first frame on cold start (cause unknown)
- 30 confirmed findings in `CODE-REVIEW-WAVES-1-4.md` (most fixed; check the file for any still open)

## D. Localization
- Spanish for many game screens is still not done (hub + chrome + coach done; in-game screens partial)
- Spanish native-speaker review of the quick-chat phrase list (O13)

## E. Hardware / form factor
- Fold inner screen and half-open (book) posture passes
- Tablet-sized layout pass for 3-4 player party games

## F. Process
- Everything since commit `fb05659` is uncommitted: arcade games, QA fixes, wave-5 server and lobby work, review fixes, party pack. Nothing is pushed; no PR; CI not run on any of it.
- Docker/CI workflow for the relay

## G. Built but never verified on a screen or device
- Real tablet and Fold: multi-touch, Nearby, frame rate
- In-game "Host left" dialog (never reached a started match)
- Reduced Motion, Relaxed and one-switch modes on the party games (code-read only)
- Review fixes for Shogi, Ludo, Spider, Matching (tests only)
- Live TalkBack end to end
- Nearby on any hardware

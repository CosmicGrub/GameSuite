package com.gamesuite.games.uno

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Narrowly-scoped regression tests for bugs found in the UNO deep audit and fixed across this
 * and a following pass — NOT a general [UnoGame] test suite (the audit's own finding that one
 * doesn't exist yet is still true; the engine's turn-order, Challenge, stacking, and
 * house-rule logic remain untested by any file). These exist so a future refactor can't
 * silently reintroduce any of the specific bugs they cover.
 *
 * All three tests drive [UnoGame] through its real public API (`playCard`/`drawCard`/
 * `keepDrawnCard`) against a [LocalPassAndPlayTransport] — the same transport
 * `SINGLE_DEVICE_PASS_AND_PLAY` uses in the real app — rather than reaching into private
 * internals, so they exercise the exact code path a real match runs.
 */
class UnoGameScoringAndReshuffleTest {

    private fun newGame(teamPlay: Boolean): UnoGame {
        val game = UnoGame()
        game.rules = UnoRules(teamPlay = teamPlay)
        val players = listOf(
            PlayerInfo(playerId = "p0", displayName = "P0", teamId = if (teamPlay) 0 else -1),
            PlayerInfo(playerId = "p1", displayName = "P1", teamId = if (teamPlay) 1 else -1),
            PlayerInfo(playerId = "p2", displayName = "P2", teamId = if (teamPlay) 0 else -1),
            PlayerInfo(playerId = "p3", displayName = "P3", teamId = if (teamPlay) 1 else -1)
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = players,
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.startMatch()
        return game
    }

    /** Audit finding: round-end scoring excluded only the individual winner
     *  (`it.playerId != winner.playerId`), not their whole team, so in 2v2 the winner's own
     *  teammate's leftover hand got folded into the round score and credited right back to
     *  that same team. Fix: exclude by teamId when rules.teamPlay is on. */
    @Test
    fun `team round scoring excludes the whole winning team, not just the emptied hand`() {
        val game = newGame(teamPlay = true)
        val s = game.state.value!!

        val winningCard = UnoCard(UnoColor.RED, UnoRank.THREE, instanceId = 9001)
        val teammateHand = listOf(UnoCard(UnoColor.GREEN, UnoRank.NINE, instanceId = 9002)) // 9 pts
        val opponent1Hand = listOf(UnoCard(UnoColor.BLUE, UnoRank.SEVEN, instanceId = 9003)) // 7 pts
        val opponent2Hand = listOf(UnoCard(UnoColor.YELLOW, UnoRank.TWO, instanceId = 9004)) // 2 pts

        // Hand-construct the moment before the winning play, bypassing the real (randomly
        // shuffled) deal -- p0/p2 are team 0, p1/p3 are team 1, matching newGame()'s roster.
        game.state.value = s.copy(
            players = listOf(
                s.players[0].copy(hand = listOf(winningCard)),
                s.players[1].copy(hand = opponent1Hand),
                s.players[2].copy(hand = teammateHand),
                s.players[3].copy(hand = opponent2Hand)
            ),
            currentPlayerIndex = 0,
            currentColor = UnoColor.RED,
            discardPile = listOf(UnoCard(UnoColor.RED, UnoRank.FIVE, instanceId = 9000)),
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false,
            roundOver = false,
            matchOver = false,
            cumulativeScores = emptyMap()
        )

        game.playCard(0, winningCard)

        val after = game.state.value!!
        assertTrue("round should end once player 0's hand empties", after.roundOver)
        // Correct score is the OPPOSING team's hands only: 7 + 2 = 9. Before the fix this
        // would have been 18 (7 + 9 + 2) -- the teammate's own 9-point hand double-counted
        // back into their own team's score.
        assertEquals(9, after.cumulativeScores["p0"])
        assertEquals(9, after.cumulativeScores["p2"])
    }

    /** Audit finding: every caller of drawFromPile() captured `val s = state.value` BEFORE
     *  drawing, then committed `s.copy(...)` built from that now-stale snapshot afterward --
     *  silently reverting ensureDrawPile()'s reshuffle and leaving cards double-counted
     *  between the (never-actually-shrinking) discard pile and players' hands. Fix: read
     *  currentDiscardPile() (the post-reshuffle state), not the stale local `s`, in every
     *  commit that follows a draw. This test asserts the one invariant that catches that
     *  exact failure mode directly: discardPile + drawPileSize + every hand's size must
     *  always sum to the full 108-card deck -- if a reshuffled card were ever double-counted,
     *  this sum would drift upward. */
    @Test
    fun `a reshuffle mid-draw preserves the total card count -- no duplication`() {
        val game = newGame(teamPlay = false)

        fun totalCards(): Int {
            val st = game.state.value!!
            return st.discardPile.size + st.drawPileSize + st.players.sumOf { it.hand.size }
        }

        assertEquals("sanity check right after dealing", 108, totalCards())

        // Draw-then-play-if-legal, repeatedly: this keeps cards actually cycling through the
        // discard pile (pure drawing alone leaves the discard pile stuck at its single
        // starting card forever, since nothing is ever added back to it -- ensureDrawPile()
        // would then always have nothing to reshuffle). Playing whenever possible is also a
        // much more realistic approximation of a real, long match than "everyone hoards
        // every card they draw forever."
        var sawEmptyPile = false
        var sawReshuffle = false
        var iterations = 0
        while (iterations < 400) {
            iterations++
            val s = game.state.value!!
            if (s.roundOver || s.matchOver) break
            // Stop before TRUE exhaustion (draw pile empty AND nothing left in the discard
            // pile to reshuffle either) -- that's a separate, different pre-existing issue
            // (drawFromPile(1).first() throws NoSuchElementException on a genuinely empty
            // result -- found by an earlier, more aggressive version of this exact test, and
            // NOT one of the two bugs this test targets or this pass fixed). This test only
            // needs a reshuffle to happen and survive, not the entire deck driven to nothing.
            if (s.drawPileSize == 0 && s.discardPile.size <= 1) break
            if (s.drawPileSize == 0) sawEmptyPile = true

            val playerIndex = s.currentPlayerIndex
            game.drawCard(playerIndex)
            val afterDraw = game.state.value!!
            if (afterDraw.awaitingDrawDecision) {
                val drawnCard = afterDraw.players[playerIndex].hand.last() // draw appends
                // Only ever plays a non-wild card -- a played Wild/Wild Draw Four would need
                // this loop to also drive chooseColor()/resolveChallenge(), which is real
                // complexity this test doesn't need: plenty of non-wild draws (100 of 108
                // cards) are enough to grow the discard pile across this many iterations.
                if (!drawnCard.isWild) game.playCard(playerIndex, drawnCard)
                else game.keepDrawnCard(playerIndex)
            }

            if (sawEmptyPile && game.state.value!!.drawPileSize > 0) sawReshuffle = true
            assertEquals(
                "total card count drifted after a draw -- a reshuffled card was likely duplicated",
                108, totalCards()
            )
        }

        assertTrue("test never actually reached a reshuffle -- loop bound needs adjusting", sawReshuffle)
    }

    /** Regression test for the crash the reshuffle test above deliberately stops short of:
     *  drawCard()'s single-draw branch used to call drawFromPile(1).first(), which throws
     *  NoSuchElementException once the draw pile AND the discard pile (which would otherwise
     *  reshuffle back in) are both genuinely exhausted -- an earlier, more aggressive version
     *  of the reshuffle test above hit this directly. Fixed by passing the turn with nothing
     *  drawn instead of crashing -- the same "can't give what doesn't exist" outcome every
     *  other drawFromPile() caller already tolerates silently. This test drives a real deck
     *  all the way to that exact terminal state and confirms the game keeps working. */
    @Test
    fun `drawing with nothing left anywhere passes the turn instead of crashing`() {
        val game = newGame(teamPlay = false)

        fun totalCards(): Int {
            val st = game.state.value!!
            return st.discardPile.size + st.drawPileSize + st.players.sumOf { it.hand.size }
        }

        // Unlike the reshuffle test above, this one always KEEPS the drawn card, never plays
        // it -- every draw permanently removes exactly one card from circulation into a hand,
        // and the discard pile never receives a new card, so it stays at its single starting
        // card the whole time. That makes depletion fully deterministic (no dependence on
        // which cards happen to get drawn): the ~78-80 cards outside hands after the initial
        // deal shrink by exactly one per draw, reliably reaching true exhaustion (draw pile
        // empty AND the discard pile down to just that one live top card, nothing left
        // anywhere to reshuffle) well within the iteration budget below.
        var reachedExhaustion = false
        var iterations = 0
        while (iterations < 150 && !reachedExhaustion) {
            iterations++
            val s = game.state.value!!
            if (s.roundOver || s.matchOver) break

            val playerIndex = s.currentPlayerIndex
            game.drawCard(playerIndex)
            val afterDraw = game.state.value!!
            if (afterDraw.awaitingDrawDecision) game.keepDrawnCard(playerIndex)
            assertEquals(
                "total card count drifted after a draw",
                108, totalCards()
            )

            val now = game.state.value!!
            if (now.drawPileSize == 0 && now.discardPile.size <= 1) reachedExhaustion = true
        }

        assertTrue(
            "test never actually reached true exhaustion within the iteration budget -- can't confirm the crash fix this way",
            reachedExhaustion
        )

        // The actual regression check: drawing again from this exact exhausted state used to
        // throw NoSuchElementException. It must not, the actor's hand must be unchanged (there
        // was nothing to draw), and the turn must still move on rather than getting stuck.
        val before = game.state.value!!
        val actor = before.currentPlayerIndex
        val handSizeBefore = before.players[actor].hand.size

        game.drawCard(actor) // <-- must not throw

        val after = game.state.value!!
        assertEquals("no card existed to draw, so the actor's hand must be unchanged", handSizeBefore, after.players[actor].hand.size)
        assertTrue("turn should still advance to a different player even though nothing was drawn", after.currentPlayerIndex != actor)
        assertEquals(108, totalCards())
    }

    /** Audit finding: drawCard()'s own top-level guard never checked awaitingDrawDecision, so a
     *  player who just drew a card that turned out to be playable (turn stays on them per this
     *  function's own KDoc, awaiting a play-or-keep decision) could call drawCard() again before
     *  resolving that decision -- silently drawing a second, third, etc. card in the same turn.
     *  Reachable both locally (the UI's Draw button has no turn-state gate of its own beyond
     *  whose turn it is) and over the network (a DrawCard intent reaches drawCard() after only a
     *  sender-identity check in applyIntent(), never a decision-state one). Fix: added
     *  s.awaitingDrawDecision to the guard, mirroring keepDrawnCard()'s own (inverse) check of
     *  the same flag.
     *
     *  This test drives real turns until a draw happens to land on a playable card -- roughly
     *  30% of the 108-card deck (all 8 wilds plus same-color cards) is playable against any
     *  single current color, so this reliably happens well within the iteration budget -- then
     *  confirms a second drawCard() call for that same player is now a no-op. */
    @Test
    fun `drawing again while a draw decision is pending does not draw a second card`() {
        val game = newGame(teamPlay = false)

        var iterations = 0
        while (iterations < 300) {
            iterations++
            val s = game.state.value!!
            if (s.roundOver || s.matchOver) break
            if (s.awaitingColorChoice) {
                // Rare (only the very first discard can land here, and only if it's a non-Wild-
                // Draw-Four Wild -- flipInitialCard() always redraws a Wild Draw Four): resolve
                // it exactly like a real player would so the loop can keep making progress.
                game.chooseColor(UnoColor.RED)
                continue
            }

            val playerIndex = s.currentPlayerIndex
            game.drawCard(playerIndex)
            val afterDraw = game.state.value!!

            if (afterDraw.awaitingDrawDecision) {
                // Found the exact state the bug targets: playerIndex just drew a playable card
                // and the turn is still theirs, awaiting a play-or-keep decision.
                val handSizeBefore = afterDraw.players[playerIndex].hand.size

                game.drawCard(playerIndex) // <-- the regression: must now be a no-op

                val after = game.state.value!!
                assertEquals(
                    "a second draw must not add a card while a draw decision is still pending",
                    handSizeBefore, after.players[playerIndex].hand.size
                )
                assertTrue("turn must stay on the same player", after.currentPlayerIndex == playerIndex)
                assertTrue("still awaiting the original draw decision", after.awaitingDrawDecision)
                return
            }
            // Not playable -- drawCard() already advanced the turn on its own; loop continues.
        }

        fail("test never hit a playable draw within the iteration budget -- can't confirm the fix this way")
    }
}

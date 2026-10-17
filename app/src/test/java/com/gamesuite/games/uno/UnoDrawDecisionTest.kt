package com.gamesuite.games.uno

import com.gamesuite.core.PlayMode
import com.gamesuite.games.uno.UnoTestKit.c
import com.gamesuite.games.uno.UnoTestKit.seedDrawPile
import com.gamesuite.games.uno.UnoTestKit.table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local (non-networked) semantics of the "draw, then play-or-keep" decision that commit a0879b2
 * guards with `awaitingDrawDecision`.
 *
 * Every test here is fully deterministic: UnoGame deals from an unseeded shuffle, so instead of
 * hoping a random draw is playable the tests overwrite `state.value` with a hand-built table
 * ([UnoTestKit.table]: top RED FIVE, colour RED, seats hold unplayable BLUE/GREEN cards) and
 * replace the private draw pile with known cards ([UnoTestKit.seedDrawPile]), so "the drawn card
 * is playable / unplayable" is certain rather than probabilistic.
 */
class UnoDrawDecisionTest {

    private val redNine = c(UnoColor.RED, UnoRank.NINE, 500)       // playable: colour match
    private val greenFive = c(UnoColor.GREEN, UnoRank.FIVE, 501)   // playable: rank match
    private val wild = c(UnoColor.WILD, UnoRank.WILD, 502)         // playable: wild
    private val blueNine = c(UnoColor.BLUE, UnoRank.NINE, 503)     // NOT playable
    private val greenSeven = c(UnoColor.GREEN, UnoRank.SEVEN, 504) // NOT playable

    private fun game(
        rules: UnoRules = UnoRules(),
        state: UnoState = table(),
        pile: List<UnoCard> = listOf(redNine, blueNine, greenSeven, wild)
    ): UnoGame {
        val n = UnoTestKit.node(localIndex = 0, n = state.players.size, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, rules = rules, start = true)
        n.game.state.value = state
        seedDrawPile(n.game, pile)
        return n.game
    }

    // ---- What a draw produces ----

    /** Guards the core draw contract: a playable draw keeps the turn on the drawer with the flag set,
     *  the drawn card lands at the END of the hand, and drawPileSize tracks the pile. A regression that
     *  advances the turn anyway (the pre-fix behaviour) would deny the player their option to play it. */
    @Test
    fun `drawing a playable card keeps the turn on the drawer and raises the decision flag`() {
        val g = game(pile = listOf(redNine, blueNine))
        g.drawCard(1)
        val s = g.state.value!!
        assertTrue(s.awaitingDrawDecision)
        assertEquals("turn must stay on the drawer", 1, s.currentPlayerIndex)
        assertEquals(redNine, s.players[1].hand.last())
        assertEquals(4, s.players[1].hand.size)
        assertEquals("drawPileSize must reflect the one card taken from the 2-card pile", 1, s.drawPileSize)
        assertEquals(listOf(table().topCard), s.discardPile)
    }

    /** Guards the other branch: an unplayable draw ends the turn immediately with no decision pending
     *  (otherwise a player would be stuck "deciding" about a card they cannot play). */
    @Test
    fun `drawing an unplayable card advances the turn and leaves no decision pending`() {
        val g = game(pile = listOf(blueNine, redNine))
        g.drawCard(1)
        val s = g.state.value!!
        assertFalse(s.awaitingDrawDecision)
        assertEquals(2, s.currentPlayerIndex)
        assertEquals(blueNine, s.players[1].hand.last())
    }

    /** Guards isLegalPlay's three independent playability paths as applied to the drawn card. A mutation
     *  dropping any one (wild / colour / rank) misclassifies exactly one of these cards. */
    @Test
    fun `drawn card playability follows colour match, rank match and wild, and nothing else`() {
        val expectations = listOf(
            redNine to true, greenFive to true, wild to true,
            blueNine to false, greenSeven to false
        )
        for ((drawn, playable) in expectations) {
            val g = game(pile = listOf(drawn))
            g.drawCard(1)
            val s = g.state.value!!
            assertEquals("$drawn playable?", playable, s.awaitingDrawDecision)
            assertEquals("$drawn turn owner", if (playable) 1 else 2, s.currentPlayerIndex)
        }
    }

    /** The flag is set for a playable draw even under forcePlayDrawnCard=true; the rule only removes the
     *  decline option, not the "you still owe a play" state that also blocks a second draw. */
    @Test
    fun `a playable draw raises the flag under forcePlayDrawnCard as well`() {
        val g = game(rules = UnoRules(forcePlayDrawnCard = true), pile = listOf(redNine))
        g.drawCard(1)
        val s = g.state.value!!
        assertTrue(s.awaitingDrawDecision)
        assertEquals(1, s.currentPlayerIndex)
    }

    /** Guards the pendingDraw branch: a stacked penalty draws ALL owed cards, in pile order, ends the turn,
     *  clears the debt and never leaves a decision pending (penalty cards are never "optional plays"). */
    @Test
    fun `drawing under a pending penalty takes every owed card and advances`() {
        val penalty = table().copy(
            pendingDraw = 2,
            discardPile = listOf(c(UnoColor.RED, UnoRank.DRAW_TWO, 901))
        )
        val g = game(state = penalty, pile = listOf(redNine, wild, blueNine))
        g.drawCard(1)
        val s = g.state.value!!
        assertEquals(0, s.pendingDraw)
        assertEquals(listOf(redNine, wild), s.players[1].hand.takeLast(2))
        assertEquals(5, s.players[1].hand.size)
        assertEquals(2, s.currentPlayerIndex)
        assertFalse("even though redNine is 'playable', a penalty draw never opens a decision", s.awaitingDrawDecision)
        assertEquals(1, s.drawPileSize)
    }

    /** Guards the exhausted-deck path: with nothing to draw the turn is passed (no crash, no flag). */
    @Test
    fun `drawing from an empty pile with nothing to reshuffle passes the turn`() {
        val g = game(pile = emptyList())
        g.drawCard(1)
        val s = g.state.value!!
        assertEquals(3, s.players[1].hand.size)
        assertEquals(2, s.currentPlayerIndex)
        assertFalse(s.awaitingDrawDecision)
    }

    // ---- Second draw while a decision is pending ----

    /** Deterministic version of the a0879b2 regression (the existing test relies on a random playable draw):
     *  with TWO playable cards on top of the pile a second drawCard() would visibly take the second one.
     *  Removing the awaitingDrawDecision guard makes the hand grow to 5 and the pile shrink to 0. */
    @Test
    fun `a second draw while the first draw is undecided is a no-op`() {
        val g = game(pile = listOf(redNine, wild))
        g.drawCard(1)
        val afterFirst = g.state.value!!

        g.drawCard(1)

        assertEquals("second drawCard must change nothing at all", afterFirst, g.state.value)
        assertEquals(4, g.state.value!!.players[1].hand.size)
    }

    /** After the decision is resolved by keeping, the flag is gone and the NEXT player can draw normally,
     *  drawing the card that was still on top (proving the ignored second draw did not consume it). */
    @Test
    fun `the ignored second draw did not consume a card the next player then receives`() {
        val g = game(pile = listOf(redNine, wild, blueNine))
        g.drawCard(1)
        g.drawCard(1) // ignored
        g.keepDrawnCard(1)
        g.drawCard(2)
        assertEquals("seat 2 must receive the wild that the ignored draw left alone", wild, g.state.value!!.players[2].hand.last())
    }

    // ---- keepDrawnCard ----

    /** Guards keepDrawnCard's happy path: clears the flag, moves to the next seat, keeps the card in hand,
     *  leaves the discard pile alone and says so in lastAction. */
    @Test
    fun `keeping the drawn card clears the flag and advances the turn`() {
        val g = game(pile = listOf(redNine))
        g.drawCard(1)
        val topBefore = g.state.value!!.topCard

        g.keepDrawnCard(1)

        val s = g.state.value!!
        assertFalse(s.awaitingDrawDecision)
        assertEquals(2, s.currentPlayerIndex)
        assertEquals("the kept card must stay in the hand", redNine, s.players[1].hand.last())
        assertEquals(4, s.players[1].hand.size)
        assertEquals("keeping must not touch the discard pile", topBefore, s.topCard)
        assertTrue(s.lastAction, s.lastAction.contains("kept"))
    }

    /** keepDrawnCard must honour direction (a reverse-order table advances to seat 0, not seat 2). */
    @Test
    fun `keeping the drawn card advances in the current play direction`() {
        val g = game(state = table().copy(direction = -1), pile = listOf(redNine))
        g.drawCard(1)
        g.keepDrawnCard(1)
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    /** forcePlayDrawnCard=true removes the decline escape: keepDrawnCard must change nothing and the
     *  decision stays pending. Guards the `if (rules.forcePlayDrawnCard) return` line. */
    @Test
    fun `keepDrawnCard is a no-op when forcePlayDrawnCard is on`() {
        val g = game(rules = UnoRules(forcePlayDrawnCard = true), pile = listOf(redNine))
        g.drawCard(1)
        val pending = g.state.value!!

        g.keepDrawnCard(1)

        assertEquals(pending, g.state.value)
        assertTrue(g.state.value!!.awaitingDrawDecision)
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    /** keepDrawnCard without a pending decision must not skip the caller's turn (a "free skip" exploit).
     *  Guards the `!s.awaitingDrawDecision` half of keepDrawnCard's guard. */
    @Test
    fun `keepDrawnCard without a pending draw decision does nothing`() {
        val g = game()
        val before = g.state.value!!
        g.keepDrawnCard(1)
        assertEquals(before, g.state.value)
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    /** Only the seat that owns the decision can resolve it. Guards the `playerIndex != currentPlayerIndex`
     *  half of keepDrawnCard's guard: another seat must not be able to end the drawer's turn. */
    @Test
    fun `keepDrawnCard from a seat that is not the current player is ignored`() {
        val g = game(pile = listOf(redNine))
        g.drawCard(1)
        val pending = g.state.value!!
        g.keepDrawnCard(2)
        g.keepDrawnCard(0)
        assertEquals(pending, g.state.value)
    }

    /** keepDrawnCard is inert while any of the modal states is up, even if the flag is (inconsistently) set.
     *  Guards the roundOver/matchOver/awaitingColorChoice/awaitingChallenge clause. */
    @Test
    fun `keepDrawnCard is ignored during colour choice, challenge, round end and match end`() {
        val flagged = table().copy(awaitingDrawDecision = true)
        val variants = mapOf(
            "awaitingColorChoice" to flagged.copy(awaitingColorChoice = true),
            "awaitingChallenge" to flagged.copy(awaitingChallenge = true, challengeVictimIndex = 1, challengePlayedByIndex = 0),
            "roundOver" to flagged.copy(roundOver = true),
            "matchOver" to flagged.copy(matchOver = true)
        )
        for ((name, st) in variants) {
            val g = game(state = st)
            g.keepDrawnCard(1)
            assertEquals("keepDrawnCard must be inert under $name", st, g.state.value)
        }
    }

    // ---- Playing the drawn card ----

    /** Playing the just-drawn card resolves the decision: flag cleared, card on the discard pile, hand back
     *  to its original size, turn advanced, colour updated. Guards commitState's default
     *  `awaitingDrawDecision = false` reset for the play path. */
    @Test
    fun `playing the drawn card clears the decision flag and advances the turn`() {
        val g = game(pile = listOf(redNine))
        g.drawCard(1)
        assertTrue(g.state.value!!.awaitingDrawDecision)

        g.playCard(1, redNine)

        val s = g.state.value!!
        assertFalse(s.awaitingDrawDecision)
        assertEquals(redNine, s.topCard)
        assertEquals(3, s.players[1].hand.size)
        assertEquals(2, s.currentPlayerIndex)
    }

    /** Wild drawn then played: the flag must be cleared by the Wild's own commit (it pauses for a colour, so
     *  the turn does not advance yet) - a stale flag here would keep blocking that player's draws forever. */
    @Test
    fun `playing a drawn wild clears the flag even though the turn is paused for a colour choice`() {
        val g = game(pile = listOf(wild))
        g.drawCard(1)
        g.playCard(1, wild)
        val s = g.state.value!!
        assertFalse(s.awaitingDrawDecision)
        assertTrue(s.awaitingColorChoice)
        assertEquals(1, s.currentPlayerIndex)
    }

    // ---- drawCard guards ----

    /** Only the current player may draw. Guards `playerIndex != s.currentPlayerIndex`: state must be
     *  untouched and the pile card must not be consumed by the illegal attempt. */
    @Test
    fun `drawCard by a seat that is not the current player is ignored and consumes nothing`() {
        val g = game(pile = listOf(blueNine, greenSeven))
        val before = g.state.value!!
        g.drawCard(0)
        g.drawCard(2)
        assertEquals(before, g.state.value)

        g.drawCard(1) // the rightful player still gets the FIRST card
        assertEquals(blueNine, g.state.value!!.players[1].hand.last())
    }

    /** drawCard must be inert during colour choice / challenge / round end / match end. Guards the four
     *  modal flags in drawCard's guard, one variant each so removing any one is caught by name. */
    @Test
    fun `drawCard is ignored during colour choice, challenge, round end and match end`() {
        val base = table()
        val variants = mapOf(
            "awaitingColorChoice" to base.copy(awaitingColorChoice = true),
            "awaitingChallenge" to base.copy(awaitingChallenge = true, challengeVictimIndex = 1, challengePlayedByIndex = 0),
            "roundOver" to base.copy(roundOver = true),
            "matchOver" to base.copy(matchOver = true)
        )
        for ((name, st) in variants) {
            val g = game(state = st, pile = listOf(redNine))
            g.drawCard(1)
            assertEquals("drawCard must be inert under $name", st, g.state.value)
        }
    }

    // ---- Bot completion of a playable draw ----

    /** playBotTurn's follow-up: because the turn stays on a bot that drew a playable card, the bot must play
     *  it itself in the same call (the UI effect will not re-fire on an unchanged index). Guards that
     *  recursion: without it the bot would be stuck holding an open decision. */
    @Test
    fun `a bot that draws a playable card plays it within the same turn`() {
        val st = table().let { it.copy(players = it.players.mapIndexed { i, p -> if (i == 1) p.copy(isBot = true) else p }) }
        val g = game(state = st, pile = listOf(redNine))
        g.playBotTurn()
        val s = g.state.value!!
        assertEquals(redNine, s.topCard)
        assertEquals("drew one and played one", 3, s.players[1].hand.size)
        assertEquals(2, s.currentPlayerIndex)
        assertFalse(s.awaitingDrawDecision)
    }

    /** ...and a bot that draws an unplayable card simply ends its turn holding it. */
    @Test
    fun `a bot that draws an unplayable card keeps it and passes the turn`() {
        val st = table().let { it.copy(players = it.players.mapIndexed { i, p -> if (i == 1) p.copy(isBot = true) else p }) }
        val g = game(state = st, pile = listOf(blueNine))
        g.playBotTurn()
        val s = g.state.value!!
        assertEquals(4, s.players[1].hand.size)
        assertEquals(2, s.currentPlayerIndex)
        assertFalse(s.awaitingDrawDecision)
    }

    // ---- Non-networked play never touches the transport ----

    /** In a non-networked mode a draw/keep/play sequence must not emit a single transport message and
     *  must not register listeners. Guards isNetworked's mode test from being widened to every mode. */
    @Test
    fun `local play never sends anything over the transport`() {
        for (mode in listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)) {
            val n = UnoTestKit.node(localIndex = 0, mode = mode, start = true)
            n.game.state.value = table()
            seedDrawPile(n.game, listOf(redNine, blueNine))
            n.game.drawCard(1)
            n.game.keepDrawnCard(1)
            n.game.callUno(2)
            assertTrue("$mode: sent=${n.transport.sent.size}", n.transport.sent.isEmpty())
            assertTrue(n.transport.messageListeners.isEmpty())
            assertTrue(n.transport.reconnectedListeners.isEmpty())
            assertTrue(n.transport.leftListeners.isEmpty())
        }
    }

    // ---- Regression for an engine bug found by this suite (fixed together with it) ----

    /**
     * UNO-BUG-D (fixed; canonical local regression, the networked one is in UnoNetworkedHostTest):
     * commitState() used to reset `awaitingDrawDecision` to false on EVERY commit except
     * drawCard()'s own, and callUno() has no turn check. So while seat 1 sat on an open draw
     * decision, any seat calling UNO (or a disconnect notice, or a catch) silently cleared seat 1's
     * flag: drawCard(1) was allowed a second time (a second draw in one turn, the exact hole a0879b2
     * closed) and keepDrawnCard(1) stopped working. The three non-turn commits (callUno,
     * catchUnoFailure, onPlayerLeft) now pass the flag through.
     */
    @Test
    fun `an unrelated commit must not clear a pending draw decision`() {
        val g = game(pile = listOf(redNine, wild))
        g.drawCard(1)
        g.callUno(2) // unrelated action by another seat
        g.drawCard(1)
        assertEquals("second draw must still be refused", 4, g.state.value!!.players[1].hand.size)
    }

    /** UNO-BUG-D (catch side): a successful UNO catch is a non-turn commit and must keep the drawer's
     *  pending decision; otherwise it re-opens a second draw exactly like an unrelated callUno did. */
    @Test
    fun `a UNO catch must not clear a pending draw decision`() {
        val base = table()
        val state = base.copy(
            players = base.players.mapIndexed { i, p ->
                if (i == 2) p.copy(hand = listOf(c(UnoColor.BLUE, UnoRank.NINE, 700)), catchWindowClosesAfterPlayerIndex = 1) else p
            }
        )
        val g = game(state = state, pile = listOf(redNine, wild, greenSeven, blueNine))
        g.drawCard(1)
        assertTrue(g.state.value!!.awaitingDrawDecision)
        g.catchUnoFailure(0, 2)
        assertEquals("the catch must have landed (two penalty cards)", 3, g.state.value!!.players[2].hand.size)
        assertTrue("the drawer's decision must survive the catch", g.state.value!!.awaitingDrawDecision)
        g.drawCard(1)
        assertEquals("second draw must still be refused", 4, g.state.value!!.players[1].hand.size)
    }

    /** A pending decision is still cleared by the drawer's own resolution (keeping the card), so the
     *  pass-through added for non-turn commits cannot leave the flag stuck. */
    @Test
    fun `keeping the drawn card after an unrelated callUno still ends the turn and clears the flag`() {
        val g = game(pile = listOf(redNine, blueNine))
        g.drawCard(1)
        g.callUno(2)
        g.keepDrawnCard(1)
        val s = g.state.value!!
        assertFalse(s.awaitingDrawDecision)
        assertEquals(2, s.currentPlayerIndex)
    }
}

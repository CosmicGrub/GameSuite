package com.gamesuite.games.uno

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for this pass's two fixes:
 *  - jump-in (audited finding: fully engine-complete since the original build, but with zero UI
 *    path — [UnoScreen.kt] now wires it up; these tests cover the ENGINE side, which was already
 *    correct but had no direct test of its own).
 *  - the UNO-catch window (audited finding: previously never closed on its own — see
 *    [UnoPlayerState.catchWindowClosesAfterPlayerIndex]'s own KDoc).
 *
 * `dealNewRound()` deals from a shuffled deck, so every test here starts a real match via
 * [newGame] and then overwrites `game.state.value` directly with a hand-crafted [UnoState] —
 * `state` is a public, plain `mutableStateOf`, so this is the same "inject a known state, then
 * exercise one real engine method" shape [UnoBotTest] already uses for [UnoBot] in isolation,
 * applied here to [UnoGame] itself.
 */
class UnoGameTest {

    private fun newGame(playerCount: Int = 3, rules: UnoRules = UnoRules()): UnoGame {
        val game = UnoGame()
        game.rules = rules
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = (0 until playerCount).map { PlayerInfo(playerId = "p$it", displayName = "Player $it") },
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.startMatch()
        return game
    }

    private fun card(color: UnoColor, rank: UnoRank, id: Int) = UnoCard(color, rank, id)

    // ---- Jump-in ----

    @Test
    fun `jump-in lets an out-of-turn player play an exact color and rank match when the house rule is on`() {
        val game = newGame(rules = UnoRules(jumpIn = true))
        val s = game.state.value!!
        val topCard = card(UnoColor.RED, UnoRank.FIVE, 900)
        val jumpCard = card(UnoColor.RED, UnoRank.FIVE, 901)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p -> if (i == 2) p.copy(hand = listOf(jumpCard) + p.hand) else p },
            discardPile = listOf(topCard),
            currentColor = UnoColor.RED,
            currentPlayerIndex = 0,
            direction = 1,
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false
        )

        game.jumpIn(playerIndex = 2, card = jumpCard)

        val after = game.state.value!!
        assertEquals("the jumped-in card must actually land on the discard pile", jumpCard, after.discardPile.last())
        assertFalse("the jumper's hand must no longer contain the played card", after.players[2].hand.contains(jumpCard))
    }

    @Test
    fun `jump-in does nothing when the house rule is off`() {
        val game = newGame(rules = UnoRules(jumpIn = false))
        val s = game.state.value!!
        val topCard = card(UnoColor.RED, UnoRank.FIVE, 900)
        val jumpCard = card(UnoColor.RED, UnoRank.FIVE, 901)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p -> if (i == 2) p.copy(hand = listOf(jumpCard) + p.hand) else p },
            discardPile = listOf(topCard),
            currentColor = UnoColor.RED,
            currentPlayerIndex = 0,
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false
        )

        game.jumpIn(playerIndex = 2, card = jumpCard)

        val after = game.state.value!!
        assertEquals(topCard, after.discardPile.last())
        assertTrue(after.players[2].hand.contains(jumpCard))
    }

    @Test
    fun `jump-in is rejected when the card is not an exact color and rank match`() {
        val game = newGame(rules = UnoRules(jumpIn = true))
        val s = game.state.value!!
        val topCard = card(UnoColor.RED, UnoRank.FIVE, 900)
        // Same rank, different color -- not an exact match, so jump-in must reject it even
        // though it would be a perfectly legal WHOLE-TURN play.
        val closeButNotExact = card(UnoColor.BLUE, UnoRank.FIVE, 901)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p -> if (i == 2) p.copy(hand = listOf(closeButNotExact) + p.hand) else p },
            discardPile = listOf(topCard),
            currentColor = UnoColor.RED,
            currentPlayerIndex = 0,
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false
        )

        game.jumpIn(playerIndex = 2, card = closeButNotExact)

        val after = game.state.value!!
        assertEquals(topCard, after.discardPile.last())
        assertTrue(after.players[2].hand.contains(closeButNotExact))
    }

    // ---- UNO-catch window ----

    @Test
    fun `reaching one card without calling UNO opens a catch window on the very next seat`() {
        val game = newGame()
        val s = game.state.value!!
        val topCard = card(UnoColor.RED, UnoRank.FIVE, 900)
        val matchingCard = card(UnoColor.RED, UnoRank.THREE, 901)
        val otherCard = card(UnoColor.BLUE, UnoRank.SEVEN, 902)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(hand = listOf(matchingCard, otherCard), calledUno = false) else p },
            discardPile = listOf(topCard),
            currentColor = UnoColor.RED,
            currentPlayerIndex = 0,
            direction = 1,
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false
        )

        game.playCard(playerIndex = 0, card = matchingCard)

        val after = game.state.value!!
        assertEquals(listOf(otherCard), after.players[0].hand)
        assertEquals(1, after.currentPlayerIndex)
        assertEquals(
            "the window must be keyed to whoever's turn is next, not the target's own index",
            1,
            after.players[0].catchWindowClosesAfterPlayerIndex
        )
    }

    @Test
    fun `a catch succeeds while the window is still open`() {
        val game = newGame()
        val s = game.state.value!!
        val topCard = card(UnoColor.RED, UnoRank.FIVE, 900)
        val matchingCard = card(UnoColor.RED, UnoRank.THREE, 901)
        val otherCard = card(UnoColor.BLUE, UnoRank.SEVEN, 902)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(hand = listOf(matchingCard, otherCard), calledUno = false) else p },
            discardPile = listOf(topCard),
            currentColor = UnoColor.RED,
            currentPlayerIndex = 0,
            direction = 1,
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false
        )
        game.playCard(playerIndex = 0, card = matchingCard)
        assertEquals(1, game.state.value!!.currentPlayerIndex) // player 1 is up, window still open

        game.catchUnoFailure(accuserIndex = 2, targetIndex = 0)

        // otherCard (1) + a 2-card penalty draw = 3.
        assertEquals(3, game.state.value!!.players[0].hand.size)
    }

    @Test
    fun `a catch fails once the turn has passed the next player -- the official rule's own cutoff`() {
        val game = newGame()
        val s = game.state.value!!
        val topCard = card(UnoColor.RED, UnoRank.FIVE, 900)
        val matchingCard = card(UnoColor.RED, UnoRank.THREE, 901)
        val otherCard = card(UnoColor.BLUE, UnoRank.SEVEN, 902)
        val player1Card = card(UnoColor.RED, UnoRank.NINE, 903) // color-matches the new top (RED THREE)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p ->
                when (i) {
                    0 -> p.copy(hand = listOf(matchingCard, otherCard), calledUno = false)
                    1 -> p.copy(hand = listOf(player1Card) + p.hand)
                    else -> p
                }
            },
            discardPile = listOf(topCard),
            currentColor = UnoColor.RED,
            currentPlayerIndex = 0,
            direction = 1,
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false
        )

        game.playCard(playerIndex = 0, card = matchingCard) // player 0 -> hand size 1, window opens on seat 1
        game.playCard(playerIndex = 1, card = player1Card) // seat 1 finishes their own turn -- window is now stale
        assertEquals(2, game.state.value!!.currentPlayerIndex)

        val handSizeBefore = game.state.value!!.players[0].hand.size
        game.catchUnoFailure(accuserIndex = 2, targetIndex = 0)

        assertEquals(
            "a catch attempted after the next player's own turn already happened must be a no-op",
            handSizeBefore,
            game.state.value!!.players[0].hand.size
        )
    }

    @Test
    fun `calling UNO closes the catch window even before anyone tries`() {
        val game = newGame()
        val s = game.state.value!!
        val topCard = card(UnoColor.RED, UnoRank.FIVE, 900)
        val matchingCard = card(UnoColor.RED, UnoRank.THREE, 901)
        val otherCard = card(UnoColor.BLUE, UnoRank.SEVEN, 902)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(hand = listOf(matchingCard, otherCard), calledUno = false) else p },
            discardPile = listOf(topCard),
            currentColor = UnoColor.RED,
            currentPlayerIndex = 0,
            direction = 1,
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false
        )
        game.playCard(playerIndex = 0, card = matchingCard)
        game.callUno(0)
        assertNull(game.state.value!!.players[0].catchWindowClosesAfterPlayerIndex)

        val handSizeBefore = game.state.value!!.players[0].hand.size
        game.catchUnoFailure(accuserIndex = 2, targetIndex = 0)

        assertEquals(handSizeBefore, game.state.value!!.players[0].hand.size)
    }
}

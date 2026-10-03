package com.gamesuite.games.dominoes

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine rules and session guards for [DominoGame]: the legality helpers the drag ghost reads
 * ([DominoGame.canPlace] / [DominoGame.wouldFlip]), hand scoring (emptied hand, blocked hand),
 * and the guarantee that once the session was left or aborted a stray tap or an in-flight CPU
 * turn cannot mutate the table or score again. The hand's tiles come from a shuffled deck, so
 * every rule test replaces the dealt state with a fixed fixture; the boneyard itself stays the
 * real one (see [drainBoneyard] for the one test that needs it empty).
 */
class DominoGameTest {

    private fun newGame(vsBot: Boolean = false): DominoGame {
        val game = DominoGame()
        game.init(
            GameContext(
                activeMode = if (vsBot) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = "p0", displayName = "P0"),
                    PlayerInfo(playerId = "p1", displayName = "P1", isBot = vsBot)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.MEDIUM
        return game
    }

    private fun fixture(
        hand0: List<Domino>,
        hand1: List<Domino>,
        chain: List<PlacedDomino> = emptyList(),
        current: Int = 0,
        boneyardSize: Int = 14,
        bot1: Boolean = false
    ) = DominoState(
        players = listOf(
            DominoPlayerState(playerId = "p0", displayName = "P0", isBot = false, hand = hand0),
            DominoPlayerState(playerId = "p1", displayName = "P1", isBot = bot1, hand = hand1)
        ),
        boneyardSize = boneyardSize,
        chain = chain,
        currentPlayerIndex = current,
        consecutivePasses = 0,
        lastAction = "Fixture"
    )

    /** A one-tile chain whose exposed ends are [left] and [right]. */
    private fun chainOf(left: Int, right: Int) = listOf(PlacedDomino(Domino(left, right, 90), flipped = false))

    /**
     * Empties the real boneyard by repeatedly handing the current player an empty hand (so a draw
     * is legal) against a 6|6 chain. The drawn tile is discarded by the next iteration's reset.
     */
    private fun drainBoneyard(game: DominoGame) {
        repeat(40) {
            val s = game.state.value!!
            if (s.boneyardSize == 0) return
            val idx = s.currentPlayerIndex
            val players = s.players.toMutableList()
            players[idx] = players[idx].copy(hand = emptyList())
            game.state.value = s.copy(players = players, chain = chainOf(6, 6))
            game.drawFromBoneyard(idx)
        }
    }

    // ---- dealing ----

    @Test
    fun `a two player deal gives seven distinct tiles each and leaves fourteen in the boneyard`() {
        val game = newGame()
        game.startMatch()

        val s = game.state.value!!
        assertEquals(7, s.players[0].hand.size)
        assertEquals(7, s.players[1].hand.size)
        assertEquals(14, s.boneyardSize)
        val ids = s.players.flatMap { p -> p.hand.map { it.instanceId } }
        assertEquals(14, ids.toSet().size)
        assertTrue(s.chain.isEmpty())
        assertFalse(s.handOver)
    }

    // ---- legality helpers ----

    @Test
    fun `canPlace and wouldFlip agree with the chain's exposed ends`() {
        val game = newGame()
        game.startMatch()
        game.state.value = fixture(hand0 = emptyList(), hand1 = emptyList(), chain = chainOf(2, 5))

        val twoThree = Domino(2, 3, 1)
        val fiveSix = Domino(5, 6, 2)
        val threeFour = Domino(3, 4, 3)

        assertTrue(game.canPlace(twoThree, attachToLeft = true))
        assertFalse(game.canPlace(twoThree, attachToLeft = false))
        // On the left its 2 must face the chain, so it is laid b-then-a.
        assertTrue(game.wouldFlip(twoThree, attachToLeft = true))

        assertTrue(game.canPlace(fiveSix, attachToLeft = false))
        assertFalse(game.canPlace(fiveSix, attachToLeft = true))
        // On the right its 5 already faces the chain, so it is laid as-is.
        assertFalse(game.wouldFlip(fiveSix, attachToLeft = false))

        assertFalse(game.canPlace(threeFour, attachToLeft = true))
        assertFalse(game.canPlace(threeFour, attachToLeft = false))
    }

    @Test
    fun `any tile may open an empty chain and is never flipped`() {
        val game = newGame()
        game.startMatch()
        game.state.value = fixture(hand0 = emptyList(), hand1 = emptyList())

        val tile = Domino(1, 4, 1)
        assertTrue(game.canPlace(tile, attachToLeft = true))
        assertTrue(game.canPlace(tile, attachToLeft = false))
        assertFalse(game.wouldFlip(tile, attachToLeft = true))
    }

    // ---- playing ----

    @Test
    fun `a legal play moves the tile from hand to chain and passes the turn`() {
        val game = newGame()
        game.startMatch()
        val twoThree = Domino(2, 3, 1)
        game.state.value = fixture(
            hand0 = listOf(twoThree, Domino(0, 0, 2)),
            hand1 = listOf(Domino(4, 4, 3)),
            chain = chainOf(2, 5)
        )

        game.playDomino(0, twoThree, attachToLeft = true)

        val s = game.state.value!!
        assertEquals(2, s.chain.size)
        assertEquals(3, s.leftEnd) // the 2 now faces the old left end, exposing the 3
        assertEquals(5, s.rightEnd)
        assertEquals(listOf(Domino(0, 0, 2)), s.players[0].hand)
        assertEquals(1, s.currentPlayerIndex)
        assertFalse(s.handOver)
    }

    @Test
    fun `an illegal, out of turn or unheld play is ignored`() {
        val game = newGame()
        game.startMatch()
        val noMatch = Domino(3, 4, 1)
        val held = Domino(2, 3, 2)
        game.state.value = fixture(
            hand0 = listOf(noMatch, held),
            hand1 = listOf(Domino(5, 5, 3)),
            chain = chainOf(2, 5)
        )
        val before = game.state.value

        game.playDomino(0, noMatch, attachToLeft = true)   // does not match either end
        game.playDomino(0, noMatch, attachToLeft = false)
        game.playDomino(1, Domino(5, 5, 3), attachToLeft = false) // not player 1's turn
        game.playDomino(0, Domino(2, 6, 77), attachToLeft = true) // never in the hand

        assertEquals(before, game.state.value)
    }

    @Test
    fun `emptying the hand wins the hand and scores the pips left in the other hands`() {
        val game = newGame()
        game.startMatch()
        val last = Domino(5, 6, 1)
        game.state.value = fixture(
            hand0 = listOf(last),
            hand1 = listOf(Domino(0, 1, 2), Domino(3, 3, 3)), // 1 + 6 = 7 pips
            chain = chainOf(2, 5)
        )

        game.playDomino(0, last, attachToLeft = false)

        val s = game.state.value!!
        assertTrue(s.handOver)
        assertEquals("p0", s.winnerPlayerId)
        assertEquals(7, game.sessionScores.value["p0"])
        assertEquals(0, game.sessionScores.value["p1"])
        assertFalse("a finished hand does not end the session", game.matchOver.value)
    }

    @Test
    fun `playAgain keeps the session score and deals a fresh hand`() {
        val game = newGame()
        game.startMatch()
        val last = Domino(5, 6, 1)
        game.state.value = fixture(
            hand0 = listOf(last),
            hand1 = listOf(Domino(0, 1, 2), Domino(3, 3, 3)),
            chain = chainOf(2, 5)
        )
        game.playDomino(0, last, attachToLeft = false)

        game.playAgain()

        val s = game.state.value!!
        assertFalse(s.handOver)
        assertNull(s.winnerPlayerId)
        assertTrue(s.chain.isEmpty())
        assertEquals(7, s.players[0].hand.size)
        assertEquals(7, game.sessionScores.value["p0"])
    }

    // ---- drawing and passing ----

    @Test
    fun `drawing is refused while a legal tile is held`() {
        val game = newGame()
        game.startMatch()
        game.state.value = fixture(
            hand0 = listOf(Domino(6, 0, 1)),
            hand1 = listOf(Domino(1, 1, 2)),
            chain = chainOf(6, 6)
        )
        val before = game.state.value

        game.drawFromBoneyard(0)

        assertEquals(before, game.state.value)
    }

    @Test
    fun `drawing hands over one tile when nothing fits`() {
        val game = newGame()
        game.startMatch()
        game.state.value = fixture(
            hand0 = listOf(Domino(0, 1, 1)),
            hand1 = listOf(Domino(1, 1, 2)),
            chain = chainOf(6, 6)
        )

        game.drawFromBoneyard(0)

        val s = game.state.value!!
        assertEquals(2, s.players[0].hand.size)
        assertEquals(13, s.boneyardSize)
        assertEquals(0, s.currentPlayerIndex) // drawing never passes the turn
    }

    @Test
    fun `passing is refused while the boneyard still has tiles`() {
        val game = newGame()
        game.startMatch()
        game.state.value = fixture(
            hand0 = listOf(Domino(0, 1, 1)),
            hand1 = listOf(Domino(1, 1, 2)),
            chain = chainOf(6, 6)
        )
        val before = game.state.value

        game.pass(0)

        assertEquals(before, game.state.value)
    }

    @Test
    fun `a blocked hand goes to the lowest pip total and scores the other hand's pips`() {
        val game = newGame()
        game.startMatch()
        drainBoneyard(game)
        assertEquals(0, game.state.value!!.boneyardSize)
        game.state.value = fixture(
            hand0 = listOf(Domino(0, 1, 1), Domino(2, 3, 2)),  // 6 pips
            hand1 = listOf(Domino(1, 1, 3), Domino(4, 5, 4)),  // 11 pips
            chain = chainOf(6, 6),
            boneyardSize = 0
        )

        game.pass(0)
        assertFalse(game.state.value!!.handOver)
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        game.pass(1)

        val s = game.state.value!!
        assertTrue(s.handOver)
        assertEquals("p0", s.winnerPlayerId)
        assertEquals(11, game.sessionScores.value["p0"])
        assertEquals(0, game.sessionScores.value["p1"])
    }

    @Test
    fun `a blocked hand with level lowest totals goes to the first seat`() {
        // The screen's help text and result panel state this rule, so it is pinned here.
        val game = newGame()
        game.startMatch()
        drainBoneyard(game)
        game.state.value = fixture(
            hand0 = listOf(Domino(0, 2, 1)), // 2 pips
            hand1 = listOf(Domino(1, 1, 2)), // 2 pips
            chain = chainOf(6, 6),
            boneyardSize = 0
        )

        game.pass(0)
        game.pass(1)

        val s = game.state.value!!
        assertTrue(s.handOver)
        assertEquals("p0", s.winnerPlayerId)
        assertEquals(2, game.sessionScores.value["p0"])
        assertEquals(0, game.sessionScores.value["p1"])
    }

    // ---- the CPU ----

    @Test
    fun `the CPU plays a legal tile on its turn`() {
        val game = newGame(vsBot = true)
        game.startMatch()
        game.state.value = fixture(
            hand0 = listOf(Domino(0, 0, 1)),
            hand1 = listOf(Domino(6, 2, 2), Domino(4, 4, 3)),
            chain = chainOf(6, 6),
            current = 1,
            bot1 = true
        )

        game.playBotTurn()

        val s = game.state.value!!
        assertEquals(2, s.chain.size)
        assertEquals(1, s.players[1].hand.size)
        assertEquals(0, s.currentPlayerIndex)
    }

    // ---- session guards ----

    @Test
    fun `every input is ignored once the session was left`() {
        val game = newGame(vsBot = true)
        game.startMatch()
        val tile = Domino(2, 3, 1)
        game.state.value = fixture(
            hand0 = listOf(tile),
            hand1 = listOf(Domino(0, 1, 2)),
            chain = chainOf(2, 5),
            bot1 = true
        )
        game.leaveSession()
        val before = game.state.value

        game.playDomino(0, tile, attachToLeft = true)
        game.drawFromBoneyard(0)
        game.pass(0)
        game.playBotTurn()
        game.playAgain()

        assertEquals(before, game.state.value)
        assertTrue(game.matchOver.value)
    }

    @Test
    fun `every input is ignored once the match was aborted and the abort reports no scores`() {
        val game = newGame(vsBot = true)
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        // The CPU is up, holding a tile that fits: only the guard can stop it playing.
        game.state.value = fixture(
            hand0 = listOf(Domino(0, 0, 1)),
            hand1 = listOf(Domino(6, 2, 2)),
            chain = chainOf(6, 6),
            current = 1,
            bot1 = true
        )
        game.abortMatch()
        val before = game.state.value

        game.playBotTurn()
        game.drawFromBoneyard(1)
        game.pass(1)

        assertEquals(before, game.state.value)
        assertEquals(1, results.size)
        assertTrue(results.single().wasAborted)
        assertTrue(results.single().scores.isEmpty())
    }

    @Test
    fun `leaving reports the session score once and a second leave is ignored`() {
        val game = newGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        val last = Domino(5, 6, 1)
        game.state.value = fixture(
            hand0 = listOf(last),
            hand1 = listOf(Domino(0, 1, 2), Domino(3, 3, 3)),
            chain = chainOf(2, 5)
        )
        game.playDomino(0, last, attachToLeft = false)

        game.leaveSession()
        game.leaveSession()

        assertEquals(1, results.size)
        val scores = results.single().scores.associateBy { it.playerId }
        assertEquals(7, scores.getValue("p0").score)
        assertTrue(scores.getValue("p0").isWinner)
        assertFalse(scores.getValue("p1").isWinner)
    }

    @Test
    fun `pause and resume are stateless and idempotent`() {
        val game = newGame()
        game.startMatch()
        val before = game.state.value

        game.pause()
        game.pause()
        game.resume()
        game.resume()
        game.pause()

        assertEquals(before, game.state.value)
        assertFalse(game.matchOver.value)
    }
}

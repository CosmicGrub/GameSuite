package com.gamesuite.games.dominoes

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.dominoes.DomTestKit.t
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blocked-hand rules, empty-boneyard draws/passes and the double-six / double-nine tile-set option. */
class DominoRulesTest {

    private fun players(n: Int) = (0 until n).map { PlayerInfo("p$it", "P$it") }

    private fun newGame(n: Int = 2, set: DominoSet = DominoSet.DOUBLE_SIX): DominoGame {
        val game = DominoGame()
        game.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players(n), 0, LocalPassAndPlayTransport()))
        game.tileSet = set
        return game
    }

    private fun chain66() = listOf(PlacedDomino(t(6, 6), false))

    /** Replaces the table with [hands] (one list per seat) against a 6-6 chain. */
    private fun fixture(game: DominoGame, hands: List<List<Domino>>, current: Int = 0) {
        game.state.value = DominoState(
            players = hands.mapIndexed { i, h -> DominoPlayerState("p$i", "P$i", false, h) },
            boneyardSize = 0,
            chain = chain66(),
            currentPlayerIndex = current,
            consecutivePasses = 0,
            lastAction = "Fixture"
        )
    }

    /** Empties the real boneyard by drawing it down with an empty hand against a 6-6 chain. */
    private fun drain(game: DominoGame) {
        repeat(80) {
            val s = game.state.value!!
            if (s.boneyardSize == 0) return
            val idx = s.currentPlayerIndex
            val ps = s.players.toMutableList()
            ps[idx] = ps[idx].copy(hand = emptyList())
            game.state.value = s.copy(players = ps, chain = chain66())
            game.drawFromBoneyard(idx)
        }
    }

    private fun blockEveryone(game: DominoGame, n: Int) {
        repeat(n) { game.pass(game.state.value!!.currentPlayerIndex) }
    }

    // ---- blocked hands ----

    @Test
    fun `a blocked three player hand scores the summed difference to each opponent`() {
        val game = newGame(3)
        game.startMatch()
        drain(game)
        fixture(game, listOf(listOf(t(0, 1)), listOf(t(0, 5)), listOf(t(4, 5))), current = 0) // 1, 5, 9 pips
        blockEveryone(game, 3)
        val s = game.state.value!!
        assertTrue(s.handOver)
        assertEquals("p0", s.winnerPlayerId)
        assertEquals((5 - 1) + (9 - 1), game.sessionScores.value["p0"])
        assertEquals(0, game.sessionScores.value["p1"])
        assertEquals(1, game.handsFinished.value)
    }

    @Test
    fun `a blocked hand with a shared lowest total is a tie even when other seats differ`() {
        val game = newGame(3)
        game.startMatch()
        drain(game)
        fixture(game, listOf(listOf(t(0, 2)), listOf(t(1, 1)), listOf(t(4, 5))), current = 0) // 2, 2, 9 pips
        blockEveryone(game, 3)
        val s = game.state.value!!
        assertTrue(s.handOver)
        assertNull(s.winnerPlayerId)
        assertEquals(listOf(0, 0, 0), (0..2).map { game.sessionScores.value["p$it"] })
        assertEquals("a tie still counts as a finished hand", 1, game.handsFinished.value)
        game.playAgain()
        assertFalse(game.state.value!!.handOver)
        assertEquals(5, game.state.value!!.players[0].hand.size)
    }

    @Test
    fun `a blocked hand where only the losers are level still has a winner`() {
        val game = newGame(3)
        game.startMatch()
        drain(game)
        fixture(game, listOf(listOf(t(0, 1)), listOf(t(2, 3)), listOf(t(1, 4))), current = 0) // 1, 5, 5 pips
        blockEveryone(game, 3)
        assertEquals("p0", game.state.value!!.winnerPlayerId)
        assertEquals(4 + 4, game.sessionScores.value["p0"])
    }

    @Test
    fun `an emptied hand still scores every pip left in the other hands`() {
        val game = newGame(2)
        game.startMatch()
        fixture(game, listOf(listOf(t(6, 0)), listOf(t(2, 3), t(4, 4))), current = 0)
        game.playDomino(0, t(6, 0), attachToLeft = false)
        val s = game.state.value!!
        assertTrue(s.handOver)
        assertEquals("p0", s.winnerPlayerId)
        assertEquals(5 + 8, game.sessionScores.value["p0"])
    }

    @Test
    fun `drawing and passing follow the standard rule when the boneyard is empty`() {
        val game = newGame(2)
        game.startMatch()
        drain(game)
        // Nothing fits and the boneyard is empty: no draw possible, but a pass is.
        fixture(game, listOf(listOf(t(0, 1)), listOf(t(0, 2))), current = 0)
        val handBefore = game.state.value!!.players[0].hand
        game.drawFromBoneyard(0)
        assertEquals(handBefore, game.state.value!!.players[0].hand)
        game.pass(0)
        assertEquals(1, game.state.value!!.consecutivePasses)
        assertEquals(1, game.state.value!!.currentPlayerIndex)

        // Something fits: a pass is refused (and so is a draw).
        fixture(game, listOf(listOf(t(0, 1)), listOf(t(0, 6))), current = 1)
        game.pass(1)
        game.drawFromBoneyard(1)
        assertEquals(0, game.state.value!!.consecutivePasses)
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        assertFalse(game.state.value!!.handOver)
    }

    @Test
    fun `a play resets the pass count so one pass never blocks the hand`() {
        val game = newGame(2)
        game.startMatch()
        drain(game)
        fixture(game, listOf(listOf(t(0, 1), t(3, 3)), listOf(t(0, 6), t(1, 2))), current = 0)
        game.pass(0)
        assertEquals(1, game.state.value!!.consecutivePasses)
        game.playDomino(1, t(0, 6), attachToLeft = false)
        assertEquals(0, game.state.value!!.consecutivePasses)
        assertFalse(game.state.value!!.handOver)
    }

    // ---- tile set ----

    @Test
    fun `the default set is double-six with 28 tiles and seven each`() {
        val game = newGame(2)
        assertEquals(DominoSet.DOUBLE_SIX, game.tileSet)
        game.startMatch()
        val s = game.state.value!!
        assertEquals(listOf(7, 7), s.players.map { it.hand.size })
        assertEquals(14, s.boneyardSize)
        assertEquals(28, DominoSet.DOUBLE_SIX.tileCount)
        assertEquals(28, DominoSet.DOUBLE_SIX.fullSet().map { it.instanceId }.toSet().size)
    }

    @Test
    fun `double-nine has 55 distinct tiles dealt ten each to two players`() {
        val set = DominoSet.DOUBLE_NINE
        assertEquals(55, set.tileCount)
        assertEquals(55, set.fullSet().size)
        assertEquals(55, set.fullSet().map { it.a to it.b }.toSet().size)
        assertTrue(set.fullSet().all { it.a in 0..9 && it.b in it.a..9 })
        val game = newGame(2, set)
        game.startMatch()
        val s = game.state.value!!
        assertEquals(listOf(10, 10), s.players.map { it.hand.size })
        assertEquals(35, s.boneyardSize)
        val all = s.players.flatMap { it.hand }
        assertEquals(20, all.map { it.instanceId }.toSet().size)
    }

    @Test
    fun `double-nine hand sizes for three and four players leave a boneyard`() {
        for (n in 3..4) {
            val game = newGame(n, DominoSet.DOUBLE_NINE)
            game.startMatch()
            assertEquals(List(n) { 7 }, game.state.value!!.players.map { it.hand.size })
            assertEquals(55 - 7 * n, game.state.value!!.boneyardSize)
        }
        val six = newGame(4)
        six.startMatch()
        assertEquals(List(4) { 5 }, six.state.value!!.players.map { it.hand.size })
    }

    @Test
    fun `a double-nine hand can be played to the end`() {
        val game = newGame(2, DominoSet.DOUBLE_NINE)
        game.startMatch()
        repeat(800) {
            val s = game.state.value!!
            if (s.handOver) return@repeat
            val i = s.currentPlayerIndex
            val tile = s.players[i].hand.firstOrNull { game.canPlace(it, true) || game.canPlace(it, false) }
            when {
                tile != null -> game.playDomino(i, tile, game.canPlace(tile, true))
                s.boneyardSize > 0 -> game.drawFromBoneyard(i)
                else -> game.pass(i)
            }
        }
        assertTrue(game.state.value!!.handOver)
    }

    @Test
    fun `initialising again returns to the default set`() {
        val game = newGame(2, DominoSet.DOUBLE_NINE)
        game.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players(2), 0, LocalPassAndPlayTransport()))
        assertEquals(DominoSet.DOUBLE_SIX, game.tileSet)
    }

    @Test
    fun `fromKey maps the hub values and falls back to double-six`() {
        assertEquals(DominoSet.DOUBLE_SIX, DominoSet.fromKey("double-six"))
        assertEquals(DominoSet.DOUBLE_NINE, DominoSet.fromKey("double-nine"))
        assertEquals(DominoSet.DOUBLE_SIX, DominoSet.fromKey(null))
        assertEquals(DominoSet.DOUBLE_SIX, DominoSet.fromKey("double-twelve"))
        assertNull(DominoSet.fromMaxPip(7))
    }

    // ---- resume snapshot ----

    @Test
    fun `the tile set is saved in the snapshot and restored`() {
        val a = newGame(2, DominoSet.DOUBLE_NINE)
        a.startMatch()
        val snap = a.snapshot()!!
        assertTrue(snap.contains("\"maxPip\":9"))

        val b = newGame(2) // double-six until the save says otherwise
        assertTrue(b.restore(snap))
        assertEquals(DominoSet.DOUBLE_NINE, b.tileSet)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `a double-six snapshot carries maxPip 6 and an old snapshot without it restores as double-six`() {
        val a = newGame(2)
        a.startMatch()
        val snap = a.snapshot()!!
        assertTrue(snap.contains("\"maxPip\":6"))
        val old = snap.replace(",\"maxPip\":6", "")
        assertFalse(old.contains("maxPip"))
        val b = newGame(2, DominoSet.DOUBLE_NINE)
        assertTrue(b.restore(old))
        assertEquals(DominoSet.DOUBLE_SIX, b.tileSet)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `a snapshot with an unknown or mismatched tile set is refused`() {
        val six = newGame(2).also { it.startMatch() }.snapshot()!!
        val nine = newGame(2, DominoSet.DOUBLE_NINE).also { it.startMatch() }.snapshot()!!

        assertFalse(newGame(2).restore(six.replace("\"maxPip\":6", "\"maxPip\":7")))
        assertFalse("a 28-tile save cannot claim to be double-nine", newGame(2).restore(six.replace("\"maxPip\":6", "\"maxPip\":9")))
        assertFalse("a 55-tile save cannot claim to be double-six", newGame(2).restore(nine.replace("\"maxPip\":9", "\"maxPip\":6")))
        assertFalse(newGame(2).restore(six.replace("\"maxPip\":6", "\"maxPip\":-1")))
        // The untouched ones still restore.
        assertTrue(newGame(2).restore(six))
        assertTrue(newGame(2).restore(nine))
    }

    @Test
    fun `undo works inside a double-nine hand`() {
        val game = newGame(2, DominoSet.DOUBLE_NINE)
        game.startMatch()
        val before = game.state.value!!
        val i = before.currentPlayerIndex
        game.playDomino(i, before.players[i].hand.first(), attachToLeft = true)
        assertTrue(game.canUndo)
        game.undo()
        assertEquals(before, game.state.value)
        assertEquals(DominoSet.DOUBLE_NINE, game.tileSet)
    }

    // ---- hub option ----

    @Test
    fun `the hub offers a tile set choice on the cpu and pass modes with double-six as the default`() {
        val spec = GameHubSpecs.get("dominoes")
        for (modeId in listOf("cpu", "pass")) {
            val mode = spec.modes.first { it.id == modeId }
            val set = mode.options.filterIsInstance<HubOption.Choice>().first { it.key == "set" }
            assertEquals("double-six", set.default)
            assertEquals(listOf("double-six", "double-nine"), set.choices.map { it.id })
            assertTrue(set.choices.all { DominoSet.fromKey(it.id).key == it.id })
        }
    }
}

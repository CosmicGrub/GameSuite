package com.gamesuite.games.yahtzee

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.yahtzee.YahtzeeCategory.*
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class YahtzeeGameTest {

    private fun roster(humans: Int, bots: Int): List<PlayerInfo> =
        (1..humans).map { PlayerInfo("p$it", if (humans == 1) "You" else "Player $it") } +
            (1..bots).map { PlayerInfo("bot$it", "CPU $it", isBot = true) }

    private fun newGame(
        humans: Int = 2,
        bots: Int = 0,
        seed: Int = 1,
        level: CpuDifficulty = CpuDifficulty.MEDIUM,
        start: Boolean = true
    ): YahtzeeGame {
        val game = YahtzeeGame(Random(seed))
        val players = roster(humans, bots)
        val mode = if (humans > 1 && bots == 0) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT
        game.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        game.difficulty = level
        if (start) game.startMatch()
        return game
    }

    private fun card(vararg filled: Pair<YahtzeeCategory, Int>, bonuses: Int = 0): YahtzeeScorecard {
        var c = YahtzeeScorecard(yahtzeeBonuses = bonuses)
        for ((cat, pts) in filled) c = c.filled(cat, pts)
        return c
    }

    /** Fills the current player's first legal box with throwaway dice. */
    private fun fillOne(game: YahtzeeGame, dice: List<Int> = listOf(1, 2, 3, 4, 6)) {
        game.setDiceForTest(dice)
        assertTrue(game.chooseCategory(game.legalCategories().first()))
    }

    /** Plays to the end: humans roll once and take their first legal box, CPUs play their turn. */
    private fun finishGame(game: YahtzeeGame) {
        var guard = 0
        while (guard++ < 600) {
            val s = game.state.value!!
            if (s.gameOver) return
            if (s.currentPlayer.isBot) {
                game.playBotTurn()
            } else {
                assertTrue(game.roll())
                assertTrue(game.chooseCategory(game.legalCategories().first()))
            }
        }
        throw AssertionError("game did not finish")
    }

    // ---- basics -------------------------------------------------------------------------------

    @Test
    fun `module metadata matches the contract`() {
        val game = YahtzeeGame()
        assertEquals("yahtzee", game.gameId)
        assertEquals("Yahtzee", game.displayName)
        assertEquals(1, game.minPlayers)
        assertEquals(4, game.maxPlayers)
        assertEquals(
            listOf(PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY),
            game.supportedModes
        )
    }

    @Test
    fun `a new game starts unrolled with three rolls and thirteen empty boxes`() {
        val s = newGame(humans = 2).state.value!!
        assertEquals(3, s.rollsLeft)
        assertEquals(1, s.round)
        assertEquals(0, s.currentPlayerIndex)
        assertTrue(s.dice.all { it == 0 })
        assertTrue(s.held.none { it })
        assertTrue(s.players.all { it.card.filledCount == 0 })
        assertFalse(s.gameOver)
    }

    @Test
    fun `the first roll throws five dice from one to six and uses a roll`() {
        val game = newGame(humans = 1)
        assertTrue(game.roll())
        val s = game.state.value!!
        assertEquals(2, s.rollsLeft)
        assertEquals(5, s.dice.size)
        assertTrue(s.dice.all { it in 1..6 })
        assertTrue(s.rolled.all { it })
    }

    @Test
    fun `rolling is deterministic for a seed`() {
        val a = newGame(humans = 1, seed = 7)
        val b = newGame(humans = 1, seed = 7)
        a.roll(); b.roll()
        assertEquals(a.state.value!!.dice, b.state.value!!.dice)
    }

    @Test
    fun `no fourth roll`() {
        val game = newGame(humans = 1)
        assertTrue(game.roll())
        game.toggleHold(0)
        assertTrue(game.roll())
        game.toggleHold(1)
        assertTrue(game.roll())
        assertEquals(0, game.state.value!!.rollsLeft)
        assertFalse(game.roll())
    }

    @Test
    fun `held dice keep their faces through a reroll`() {
        val game = newGame(humans = 1, seed = 3)
        game.roll()
        val before = game.state.value!!.dice
        game.toggleHold(0)
        game.toggleHold(3)
        assertTrue(game.roll())
        val s = game.state.value!!
        assertEquals(before[0], s.dice[0])
        assertEquals(before[3], s.dice[3])
        assertFalse(s.rolled[0])
        assertTrue(s.rolled[1])
    }

    @Test
    fun `holds are refused before the first roll and after the last`() {
        val game = newGame(humans = 1)
        assertFalse(game.toggleHold(0))
        game.roll(); game.roll(); game.roll()
        assertFalse(game.toggleHold(0))
        assertFalse(game.toggleHold(9))
    }

    @Test
    fun `a reroll with every die held is refused`() {
        val game = newGame(humans = 1)
        game.roll()
        repeat(5) { game.toggleHold(it) }
        assertFalse(game.roll())
        assertEquals(2, game.state.value!!.rollsLeft)
    }

    @Test
    fun `no box can be chosen before rolling`() {
        val game = newGame(humans = 1)
        assertTrue(game.legalCategories().isEmpty())
        assertFalse(game.chooseCategory(CHANCE))
    }

    // ---- turns, rounds, end -------------------------------------------------------------------

    @Test
    fun `choosing a box scores it and passes the turn with fresh dice`() {
        val game = newGame(humans = 2)
        game.setDiceForTest(listOf(3, 3, 3, 1, 2))
        assertTrue(game.chooseCategory(THREES))
        val s = game.state.value!!
        assertEquals(9, s.players[0].card[THREES])
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(3, s.rollsLeft)
        assertTrue(s.dice.all { it == 0 })
        assertEquals(THREES, s.lastScored!!.category)
        assertEquals(9, s.lastScored!!.points)
    }

    @Test
    fun `a box can be filled only once`() {
        val game = newGame(humans = 1)
        fillOne(game)
        val first = game.state.value!!.players[0].card.scores.indexOfFirst { it != null }
        game.setDiceForTest(listOf(1, 1, 1, 1, 1))
        assertFalse(game.chooseCategory(YahtzeeCategory.values()[first]))
    }

    @Test
    fun `the round advances after the last seat and the opener plays first`() {
        val game = newGame(humans = 3)
        fillOne(game); fillOne(game)
        assertEquals(1, game.state.value!!.round)
        fillOne(game)
        val s = game.state.value!!
        assertEquals(2, s.round)
        assertEquals(0, s.currentPlayerIndex)
    }

    @Test
    fun `the requested first seat opens the game and rounds wrap from there`() {
        val game = newGame(humans = 2, start = false)
        game.firstOpenerIndex = 1
        game.startMatch()
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        fillOne(game)
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertEquals(1, game.state.value!!.round)
        fillOne(game)
        assertEquals(2, game.state.value!!.round)
        assertEquals(1, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `solo game ends after thirteen rounds and records the best score`() {
        val game = newGame(humans = 1)
        repeat(13) { fillOne(game) }
        val s = game.state.value!!
        assertTrue(s.gameOver)
        assertTrue(s.players[0].card.isComplete)
        assertEquals(1, game.gamesFinished.value)
        assertEquals(s.players[0].card.total, game.sessionBest.value["p1"])
        assertFalse(game.roll())
        assertFalse(game.chooseCategory(CHANCE))
    }

    @Test
    fun `leaving a finished solo session reports the best score as a win`() {
        val game = newGame(humans = 1)
        var result: GameResult? = null
        game.setOnMatchEnd { result = it }
        repeat(13) { fillOne(game) }
        game.leaveSession()
        val r = result!!
        assertFalse(r.wasAborted)
        assertEquals(1, r.scores.size)
        assertEquals(game.state.value!!.players[0].card.total, r.scores[0].score)
        assertTrue(r.scores[0].isWinner)
        assertTrue(game.matchOver.value)
    }

    @Test
    fun `play again keeps the session best and flags a new best`() {
        val game = newGame(humans = 1)
        repeat(13) { fillOne(game) }
        val firstTotal = game.state.value!!.players[0].card.total
        game.playAgain()
        assertEquals(1, game.state.value!!.round)
        assertEquals(0, game.state.value!!.players[0].card.filledCount)
        repeat(13) { fillOne(game, listOf(6, 6, 6, 6, 6)) }
        assertEquals(2, game.gamesFinished.value)
        assertTrue(game.state.value!!.players[0].card.total > firstTotal)
        assertTrue(game.lastGameNewBest.value)
        assertEquals(game.state.value!!.players[0].card.total, game.sessionBest.value["p1"])
    }

    @Test
    fun `the higher total wins a two player game and the ranking orders by total`() {
        val game = newGame(humans = 2)
        repeat(13) {
            game.setDiceForTest(listOf(6, 6, 6, 6, 6)); game.chooseCategory(game.legalCategories().first())
            fillOne(game)
        }
        val s = game.state.value!!
        assertTrue(s.gameOver)
        assertTrue(s.players[0].card.total > s.players[1].card.total)
        assertEquals(listOf(0, 1), game.ranking())
        assertEquals(1, game.sessionWins.value["p1"])
        assertEquals(0, game.sessionWins.value["p2"])
    }

    @Test
    fun `a tied game gives nobody the win`() {
        val game = newGame(humans = 2)
        repeat(26) { fillOne(game) }
        assertTrue(game.state.value!!.gameOver)
        assertEquals(game.state.value!!.players[0].card.total, game.state.value!!.players[1].card.total)
        assertEquals(0, game.sessionWins.value["p1"])
        assertEquals(0, game.sessionWins.value["p2"])
    }

    // ---- bonus and joker through the engine ---------------------------------------------------

    @Test
    fun `a second yahtzee earns the hundred point bonus and goes to its upper box`() {
        val game = newGame(humans = 1)
        game.setCardForTest(0, card(YAHTZEE to 50))
        game.setDiceForTest(listOf(4, 4, 4, 4, 4))
        assertEquals(listOf(FOURS), game.legalCategories())
        assertFalse(game.chooseCategory(CHANCE))
        assertTrue(game.chooseCategory(FOURS))
        val c = game.state.value!!.players[0].card
        assertEquals(20, c[FOURS])
        assertEquals(1, c.yahtzeeBonuses)
        assertEquals(170, c.total)
        assertEquals(100, game.state.value!!.lastScored!!.bonus)
    }

    @Test
    fun `joker scores a full house and a straight at full value in the lower section`() {
        val game = newGame(humans = 1)
        game.setCardForTest(0, card(YAHTZEE to 50, SIXES to 24))
        game.setDiceForTest(listOf(6, 6, 6, 6, 6))
        assertTrue(game.chooseCategory(LARGE_STRAIGHT))
        assertEquals(40, game.state.value!!.players[0].card[LARGE_STRAIGHT])
        assertEquals(1, game.state.value!!.players[0].card.yahtzeeBonuses)
    }

    @Test
    fun `a yahtzee after a zeroed yahtzee box gets joker values but no bonus`() {
        val game = newGame(humans = 1)
        game.setCardForTest(0, card(YAHTZEE to 0, TWOS to 6))
        game.setDiceForTest(listOf(2, 2, 2, 2, 2))
        assertTrue(game.chooseCategory(FULL_HOUSE))
        val c = game.state.value!!.players[0].card
        assertEquals(25, c[FULL_HOUSE])
        assertEquals(0, c.yahtzeeBonuses)
    }

    @Test
    fun `a first yahtzee in the yahtzee box is not a bonus`() {
        val game = newGame(humans = 1)
        game.setDiceForTest(listOf(3, 3, 3, 3, 3))
        assertTrue(game.chooseCategory(YAHTZEE))
        assertEquals(0, game.state.value!!.players[0].card.yahtzeeBonuses)
        assertEquals(50, game.state.value!!.players[0].card[YAHTZEE])
    }

    // ---- CPU ----------------------------------------------------------------------------------

    @Test
    fun `every CPU level plays a complete legal game`() {
        for (level in CpuDifficulty.values()) {
            val game = newGame(humans = 1, bots = 1, seed = 11, level = level)
            finishGame(game)
            val s = game.state.value!!
            assertTrue("$level", s.gameOver)
            for (p in s.players) {
                assertTrue("$level ${p.playerId}", p.card.isComplete)
                for (c in YahtzeeCategory.ALL) assertTrue(p.card[c]!! in 0..c.maxScore)
            }
        }
    }

    @Test
    fun `a table of one human and three CPUs finishes`() {
        val game = newGame(humans = 1, bots = 3, seed = 5, level = CpuDifficulty.EASY)
        finishGame(game)
        assertTrue(game.state.value!!.players.all { it.card.isComplete })
    }

    @Test
    fun `a CPU respects the joker rule`() {
        for (level in CpuDifficulty.values()) {
            val game = newGame(humans = 1, bots = 1, seed = 2, level = level, start = false)
            game.firstOpenerIndex = 1
            game.startMatch()
            game.setCardForTest(1, card(YAHTZEE to 50))
            game.setDiceForTest(listOf(3, 3, 3, 3, 3), rollsLeft = 0)
            game.playBotTurn()
            val bot = game.state.value!!.players[1].card
            assertEquals("$level", 1, bot.filledCount - 1)
            assertEquals("$level", 15, bot[THREES])
            assertEquals("$level", 1, bot.yahtzeeBonuses)
        }
    }

    @Test
    fun `a CPU turn starts by rolling and shows its holds before rerolling`() {
        val game = newGame(humans = 1, bots = 1, seed = 4, level = CpuDifficulty.HARD, start = false)
        game.firstOpenerIndex = 1
        game.startMatch()
        assertTrue(game.botStep())
        assertEquals(2, game.state.value!!.rollsLeft)
        val roll = game.state.value!!.rollSerial
        // Next steps either hold then reroll, or score; the serial moves only on real rolls.
        var guard = 0
        while (game.state.value!!.currentPlayerIndex == 1 && guard++ < 6) game.botStep()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertTrue(game.state.value!!.rollSerial >= roll)
    }

    @Test
    fun `botStep does nothing on a human turn`() {
        val game = newGame(humans = 1, bots = 1)
        assertFalse(game.botStep())
        game.playBotTurn()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertEquals(3, game.state.value!!.rollsLeft)
    }

    @Test
    fun `the same seed gives the same CPU game`() {
        fun total(): Int {
            val g = newGame(humans = 1, bots = 1, seed = 21, level = CpuDifficulty.EASY)
            finishGame(g)
            return g.state.value!!.players[1].card.total
        }
        assertEquals(total(), total())
    }

    @Test
    fun `the hard planner keeps four of a kind and stops on a yahtzee`() {
        val empty = YahtzeeScorecard()
        val advisor = YahtzeeAdvisor(empty, 1.0, 0.5, 2)
        val keep = advisor.plan(listOf(6, 6, 6, 2, 6), rollsLeft = 2)
        assertEquals(listOf(true, true, true, false, true), keep.holds)
        val stop = advisor.plan(listOf(5, 5, 5, 5, 5), rollsLeft = 2)
        assertNull(stop.holds)
        assertEquals(YAHTZEE, stop.category)
    }

    @Test
    fun `the hard planner takes a made large straight`() {
        val plan = YahtzeeAdvisor(YahtzeeScorecard(), 1.0, 0.5, 2).plan(listOf(2, 3, 4, 5, 6), rollsLeft = 2)
        assertNull(plan.holds)
        assertEquals(LARGE_STRAIGHT, plan.category)
    }

    // ---- resume -------------------------------------------------------------------------------

    @Test
    fun `snapshot is null before the game starts`() {
        assertNull(newGame(start = false).snapshot())
    }

    @Test
    fun `snapshot round trips dice holds scorecards and the session`() {
        val a = newGame(humans = 2, seed = 9)
        fillOne(a)
        a.roll()
        a.toggleHold(2)
        val text = a.snapshot()
        assertNotNull(text)

        val b = newGame(humans = 2, seed = 99, start = false)
        assertTrue(b.restore(text!!))
        val sa = a.state.value!!
        val sb = b.state.value!!
        assertEquals(sa.dice, sb.dice)
        assertEquals(sa.held, sb.held)
        assertEquals(sa.rollsLeft, sb.rollsLeft)
        assertEquals(sa.round, sb.round)
        assertEquals(sa.currentPlayerIndex, sb.currentPlayerIndex)
        assertEquals(sa.players.map { it.card }, sb.players.map { it.card })
        assertEquals(text, b.snapshot())
    }

    @Test
    fun `snapshot after the game or the session is over is null`() {
        val game = newGame(humans = 1)
        repeat(13) { fillOne(game) }
        assertNull(game.snapshot())
        val other = newGame(humans = 1)
        other.leaveSession()
        assertNull(other.snapshot())
    }

    @Test
    fun `garbage snapshots are rejected and leave the game untouched`() {
        val game = newGame(humans = 2)
        fillOne(game)
        val before = game.snapshot()
        assertFalse(game.restore("not json at all"))
        assertFalse(game.restore("{}"))
        assertFalse(game.restore(""))
        assertEquals(before, game.snapshot())
    }

    @Test
    fun `tampered snapshots are rejected`() {
        val source = newGame(humans = 2, seed = 5)
        fillOne(source)
        source.roll()
        val text = source.snapshot()!!
        val target = newGame(humans = 2, start = false)
        assertFalse("round", target.restore(text.replace("\"round\":1", "\"round\":7")))
        assertFalse("rolls", target.restore(text.replace("\"rollsLeft\":2", "\"rollsLeft\":9")))
        assertFalse("turn", target.restore(text.replace("\"currentPlayerIndex\":1", "\"currentPlayerIndex\":5")))
        assertNull(target.state.value)
    }

    @Test
    fun `a snapshot for another roster is rejected`() {
        val source = newGame(humans = 2)
        fillOne(source)
        val text = source.snapshot()!!
        assertFalse(newGame(humans = 3, start = false).restore(text))
        assertFalse(newGame(humans = 1, bots = 1, start = false).restore(text))
    }

    @Test
    fun `a card holding an impossible score is rejected`() {
        val source = newGame(humans = 1)
        fillOne(source)
        val text = source.snapshot()!!
        val forged = text.replace(Regex("\"scores\":\\[[^\\]]*\\]"), "\"scores\":[4,null,null,null,null,null,null,null,null,null,null,null,null]")
        // 4 in Aces is possible, but 99 in Aces is not, and a wrong filled-count is not either.
        val impossible = text.replace(Regex("\"scores\":\\[[^\\]]*\\]"), "\"scores\":[99,null,null,null,null,null,null,null,null,null,null,null,null]")
        val target = newGame(humans = 1, start = false)
        assertFalse(target.restore(impossible))
        assertFalse(target.restore(forged.replace("\"round\":2", "\"round\":5")))
    }

    // ---- undo and hint ------------------------------------------------------------------------

    @Test
    fun `undo takes back a hold toggle`() {
        val game = newGame(humans = 1)
        game.roll()
        assertFalse(game.canUndo)
        game.toggleHold(1)
        assertTrue(game.state.value!!.held[1])
        assertTrue(game.canUndo)
        game.undo()
        assertFalse(game.state.value!!.held[1])
        assertFalse(game.canUndo)
    }

    @Test
    fun `undo takes back a category choice including the turn change`() {
        val game = newGame(humans = 2)
        game.setDiceForTest(listOf(1, 1, 1, 2, 2))
        game.chooseCategory(FULL_HOUSE)
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        assertTrue(game.canUndo)
        game.undo()
        val s = game.state.value!!
        assertEquals(0, s.currentPlayerIndex)
        assertNull(s.players[0].card[FULL_HOUSE])
        assertEquals(listOf(1, 1, 1, 2, 2), s.dice)
        assertTrue(game.legalCategories().contains(FULL_HOUSE))
    }

    @Test
    fun `a roll cannot be undone and clears earlier undo steps`() {
        val game = newGame(humans = 1)
        game.roll()
        game.toggleHold(0)
        assertTrue(game.canUndo)
        val dice = game.state.value!!.dice
        game.roll()
        assertFalse(game.canUndo)
        game.undo()
        assertEquals(1, game.state.value!!.rollsLeft)
        assertTrue(game.state.value!!.held[0])
        assertEquals(dice[0], game.state.value!!.dice[0])
    }

    @Test
    fun `undo is unavailable on a CPU turn and after the game`() {
        val game = newGame(humans = 1, bots = 1)
        game.roll()
        game.toggleHold(0)
        game.setDiceForTest(listOf(1, 2, 3, 4, 6))
        game.chooseCategory(CHANCE)
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        assertFalse(game.canUndo)
        val solo = newGame(humans = 1)
        repeat(13) { fillOne(solo) }
        assertFalse(solo.canUndo)
    }

    @Test
    fun `undoing a choice against the CPU brings the human turn back`() {
        val game = newGame(humans = 1, bots = 1, seed = 6)
        game.setDiceForTest(listOf(6, 6, 2, 3, 4))
        game.chooseCategory(SIXES)
        game.playBotTurn()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertTrue(game.canUndo)
        game.undo()
        val s = game.state.value!!
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(0, s.players[1].card.filledCount)
        assertNull(s.players[0].card[SIXES])
    }

    @Test
    fun `hint needs a roll then suggests holds or a box`() {
        val game = newGame(humans = 1)
        assertFalse(game.hint())
        game.roll()
        assertTrue(game.hint())
        assertTrue((game.hintHolds.value != null) xor (game.hintCategory.value != null))
        game.toggleHold(0)
        assertNull(game.hintHolds.value)
        assertNull(game.hintCategory.value)
    }

    @Test
    fun `hint suggests the box when no rolls remain`() {
        val game = newGame(humans = 1)
        game.setDiceForTest(listOf(5, 5, 5, 5, 5), rollsLeft = 0)
        assertTrue(game.hint())
        assertEquals(YAHTZEE, game.hintCategory.value)
        assertNull(game.hintHolds.value)
    }

    @Test
    fun `hint is refused on a CPU turn`() {
        val game = newGame(humans = 1, bots = 1, start = false)
        game.firstOpenerIndex = 1
        game.startMatch()
        game.botStep()
        assertFalse(game.hint())
    }

    @Test
    fun `aborting reports no scores`() {
        val game = newGame(humans = 1)
        var result: GameResult? = null
        game.setOnMatchEnd { result = it }
        fillOne(game)
        game.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }
}

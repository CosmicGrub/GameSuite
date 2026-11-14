package com.gamesuite.core

import com.gamesuite.games.checkers.CheckersGame
import com.gamesuite.games.chess.ChessGame
import com.gamesuite.games.connectfour.ConnectFourGame
import com.gamesuite.games.dotsandboxes.DotsAndBoxesGame
import com.gamesuite.games.gomoku.GomokuGame
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.games.reversi.ReversiGame
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraHelpTest {
    private val withHelp = ExtraHelp(setOf(0))

    @Test
    fun offChangesNothingAtAnyLevel() {
        for (level in CpuDifficulty.entries) {
            assertEquals(level, ExtraHelp.NONE.cpuLevel(level, 1))
        }
        assertFalse(ExtraHelp.NONE.active)
    }

    @Test
    fun onMakesTheOpposingComputerOneLevelGentler() {
        assertEquals(CpuDifficulty.MEDIUM, withHelp.cpuLevel(CpuDifficulty.HARD, 1))
        assertEquals(CpuDifficulty.EASY, withHelp.cpuLevel(CpuDifficulty.MEDIUM, 1))
        assertEquals(CpuDifficulty.EASY, withHelp.cpuLevel(CpuDifficulty.EASY, 1))
        // The helped seat itself is never made weaker by its own help.
        assertEquals(CpuDifficulty.HARD, withHelp.cpuLevel(CpuDifficulty.HARD, 0))
    }

    @Test
    fun parsesTheLaunchExtras() {
        assertEquals(setOf(0), ExtraHelp.fromExtras(mapOf("extraHelp" to "true")).helpedSeats)
        assertEquals(setOf(0), ExtraHelp.fromExtras(mapOf("extraHelp" to "on")).helpedSeats)
        assertEquals(setOf(1), ExtraHelp.fromExtras(mapOf("extraHelp" to "p2")).helpedSeats)
        assertFalse(ExtraHelp.fromExtras(mapOf("extraHelp" to "false")).active)
        assertFalse(ExtraHelp.fromExtras(mapOf("extraHelp" to "off")).active)
        assertFalse(ExtraHelp.fromExtras(mapOf("extraHelp" to "p9")).active)
        assertFalse(ExtraHelp.fromExtras(mapOf("extraHelp" to "banana")).active)
        assertFalse(ExtraHelp.fromExtras(emptyMap()).active)
    }

    @Test
    fun theHubOffersTheSwitchOnlyAgainstTheComputerInTheEightGames() {
        val eight = listOf("connect-four", "checkers", "mancala", "reversi", "tic-tac-toe", "gomoku", "dots-and-boxes", "chess")
        for (id in eight) {
            val spec = GameHubSpecs.get(id)
            val cpu = spec.modes.first { it.kind == HubModeKind.VS_CPU }
            val keys = cpu.options.map { it.key }
            assertTrue("$id offers extraHelp", ExtraHelp.KEY in keys)
            // Same wording and place in every game: right before the computer level.
            val option = cpu.options.first { it.key == ExtraHelp.KEY } as HubOption.Toggle
            assertEquals("Help the young player", option.label)
            assertFalse(option.default)
            assertEquals(keys.indexOf(HubKeys.DIFFICULTY) - 1, keys.indexOf(ExtraHelp.KEY))
            val pass = spec.modes.first { it.kind == HubModeKind.PASS_AND_PLAY }
            assertFalse("$id pass and play has no computer to soften", ExtraHelp.KEY in pass.options.map { it.key })
        }
        val others = GameHubSpecs.all.filter { it.gameId !in eight }
        assertTrue(others.none { spec -> spec.modes.any { m -> m.options.any { it.key == ExtraHelp.KEY } } })
    }

    @Test
    fun theSwitchReachesTheGameAsAnExtra() {
        val cpu = GameHubSpecs.get("connect-four").modes.first { it.kind == HubModeKind.VS_CPU }
        val on = cpu.resolve(HubChoices().with(ExtraHelp.KEY, true)).asMap()
        assertEquals("true", on[ExtraHelp.KEY])
        assertTrue(ExtraHelp.fromExtras(on).active)
        val off = cpu.resolve(HubChoices()).asMap()
        assertFalse(ExtraHelp.fromExtras(off).active)
    }

    private fun vsBot(game: GameModule) {
        game.init(
            GameContext(
                PlayMode.SINGLE_PLAYER_VS_BOT,
                listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true)),
                0, LocalPassAndPlayTransport()
            )
        )
    }

    /** Every one of the eight engines applies the rule through its own level choice, and only when the option is on. */
    @Test
    fun everyEngineAppliesTheRuleOnlyWhenOn() {
        val t = TicTacToeGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val m = MancalaGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val g = GomokuGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val d = DotsAndBoxesGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val c4 = ConnectFourGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val ch = CheckersGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val rv = ReversiGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val cs = ChessGame().also { it.difficulty = CpuDifficulty.HARD; vsBot(it) }
        val levels = listOf<() -> CpuDifficulty>(
            { t.botLevelFor(1) }, { m.botLevelFor(1) }, { g.botLevelFor(1) }, { d.botLevelFor(1) },
            { c4.botLevelFor(1) }, { ch.botLevelFor(1) }, { rv.botLevelFor(1) }, { cs.botLevelFor(1) }
        )
        levels.forEach { assertEquals("off keeps the chosen level", CpuDifficulty.HARD, it()) }
        t.extraHelp = withHelp; m.extraHelp = withHelp; g.extraHelp = withHelp; d.extraHelp = withHelp
        c4.extraHelp = withHelp; ch.extraHelp = withHelp; rv.extraHelp = withHelp; cs.extraHelp = withHelp
        levels.forEach { assertEquals("on plays one level gentler", CpuDifficulty.MEDIUM, it()) }
        t.extraHelp = ExtraHelp.NONE; cs.extraHelp = ExtraHelp.NONE
        assertEquals(CpuDifficulty.HARD, t.botLevelFor(1))
        assertEquals(CpuDifficulty.HARD, cs.botLevelFor(1))
    }

    @Test
    fun arenaLevelsStillWinOverTheBaseLevelAndHelpStacksOnTop() {
        val game = TicTacToeGame().also { it.difficulty = CpuDifficulty.EASY; vsBot(it) }
        game.arena = ArenaHook(mapOf(1 to CpuDifficulty.HARD))
        assertEquals(CpuDifficulty.HARD, game.botLevelFor(1))
        game.extraHelp = withHelp
        assertEquals(CpuDifficulty.MEDIUM, game.botLevelFor(1))
        assertNotNull(game)
        assertNull(null)
    }
}

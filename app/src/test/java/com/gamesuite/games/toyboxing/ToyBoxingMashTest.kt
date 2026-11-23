package com.gamesuite.games.toyboxing

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Button-mashing against the computer: Hard punishes it, a player who reads the tell still beats Hard. */
class ToyBoxingMashTest {
    private fun bout(seed: Int, level: CpuDifficulty, rounds: Int = 3, player: (ToyBoxingGame, BoxingState, Int) -> Unit): String? {
        val clock = TbClock(); val game = ToyBoxingGame(Random(seed), clock)
        game.difficulty = level; game.rounds = rounds
        game.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("a", "Me"), PlayerInfo("b", "Bot", isBot = true)), 0, TbFakeTransport()))
        game.startMatch()
        var n = 0
        while (!game.state.value!!.over && n++ < 30_000) {
            clock.advance(25)
            player(game, game.state.value!!, n)
            game.tick()
        }
        return game.state.value!!.winnerPlayerId
    }

    private val masher: (ToyBoxingGame, BoxingState, Int) -> Unit = { g, s, _ ->
        if (s.fighters[0].action == "DOWN") g.play(0, BoxingAction.TAP) else g.play(0, BoxingAction.JAB)
    }

    private val reader: (ToyBoxingGame, BoxingState, Int) -> Unit = { g, s, n ->
        val me = s.fighters[0]; val opp = s.fighters[1]
        when {
            me.action == "DOWN" -> g.play(0, BoxingAction.TAP)
            me.action != "IDLE" -> {}
            opp.action == "HOOK" && opp.actionAgeMs < 450 -> if (opp.actionAgeMs in 100..250) g.play(0, BoxingAction.DODGE)
            me.counter && opp.action != "IDLE" && me.stamina >= 16 -> g.play(0, BoxingAction.HOOK)
            (opp.action == "STAGGER" || opp.action == "DODGE") && me.stamina >= 16 -> g.play(0, BoxingAction.HOOK)
            !me.guard -> g.play(0, BoxingAction.BLOCK)
        }
    }

    private fun wins(level: CpuDifficulty, script: (ToyBoxingGame, BoxingState, Int) -> Unit, bouts: Int = 24): Pair<Int, Int> {
        var human = 0; var bot = 0
        for (seed in 1..bouts) { when (bout(seed, level, 3, script)) { "a" -> human++; "b" -> bot++ } }
        return human to bot
    }

    @Test fun hardBeatsAJabMasherAlmostEveryBout() {
        val (human, bot) = wins(CpuDifficulty.HARD, masher)
        assertTrue("masher $human - hard $bot", bot >= human * 4 && bot >= 18)
    }

    @Test fun mashingIsPunishedMoreAtEveryLevel() {
        val (easyM, easyB) = wins(CpuDifficulty.EASY, masher)
        val (medM, medB) = wins(CpuDifficulty.MEDIUM, masher)
        val (hardM, hardB) = wins(CpuDifficulty.HARD, masher)
        assertTrue("easy $easyM-$easyB medium $medM-$medB hard $hardM-$hardB", medB - medM > easyB - easyM && hardB - hardM > medB - medM)
    }

    @Test fun hardStillLosesToAPlayerWhoReadsTheTell() {
        // Guard up, dodge every hook on its wind-up, then hook the boxer who whiffed: skill beats the strongest computer.
        val (human, bot) = wins(CpuDifficulty.HARD, reader)
        assertTrue("reader $human - hard $bot", human > bot)
    }

    @Test fun aJabStreakCostsMoreStaminaEachTime() {
        assertEquals(0, BoxingRules.jabSurcharge(1)); assertEquals(2, BoxingRules.jabSurcharge(2)); assertEquals(10, BoxingRules.jabSurcharge(30))
    }

    @Test fun aTiredMasherCannotKeepJabbingEvenAtFullTilt() {
        val clock = TbClock(); val game = ToyBoxingGame(Random(5), clock)
        game.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(PlayerInfo("a", "A"), PlayerInfo("b", "B")), 0, TbFakeTransport()))
        game.startMatch()
        repeat(120) { clock.advance(25); game.tick() } // the bell
        var thrown = 0
        repeat(40) { if (game.play(0, BoxingAction.JAB) == BoxingReject.NONE) thrown++; repeat(12) { clock.advance(25); game.tick() } }
        // 40 attempts, one every 300 ms for 12 s: a streak that costs more each time and a tired boxer throw far fewer than 40.
        assertTrue("threw $thrown", thrown in 8..30)
    }
}

package com.gamesuite.games.toybaseball

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.toyboxing.TbClock
import com.gamesuite.games.toyboxing.TbFakeTransport
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Toy Baseball: the contact model, a fully scripted game, counts and runners, the computer's levels and fuzzing. */
class ToyBaseballRulesTest {
    private val humans = listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Ben"))
    private val bots = listOf(PlayerInfo("a", "Bot A", isBot = true), PlayerInfo("b", "Bot B", isBot = true))

    private class ScriptedRolls : BaseballRolls {
        private val v = ArrayDeque<Int>(); private val p = ArrayDeque<Int>()
        fun push(variance: Int, placement: Int) { v.addLast(variance); p.addLast(placement) }
        override fun variance(): Int = v.removeFirst()
        override fun placement(): Int = p.removeFirst()
        fun empty() = v.isEmpty() && p.isEmpty()
    }

    private class Rig(val game: ToyBaseballGame, val rolls: ScriptedRolls) {
        val s get() = game.state.value!!

        /** One whole pitch: the pitcher throws, the batter answers; returns the outcome. */
        fun play(type: PitchType, row: Int, col: Int, swing: BaseballSwing, variance: Int = 0, placement: Int = 0): String {
            val pitcher = s.pitcherSeat; val batter = s.batterSeat
            if (!swing.take) rolls.push(variance, placement)
            assertEquals(BaseballReject.NONE, game.pitch(pitcher, type, row, col))
            assertEquals("SWING", s.phase)
            assertEquals(BaseballReject.NONE, game.swing(batter, swing))
            return s.last!!.outcome
        }

        fun strikeLooking() = play(PitchType.FASTBALL, 2, 2, BaseballSwing.TAKE)
        fun strikeoutLooking() { repeat(2) { assertEquals(BaseballOutcome.CALLED_STRIKE, strikeLooking()) }; assertEquals(BaseballOutcome.STRIKEOUT_LOOKING, strikeLooking()) }
        fun retire(n: Int = 3) = repeat(n) { strikeoutLooking() }
        fun homeRun() = assertEquals(BaseballOutcome.HOME_RUN, play(PitchType.FASTBALL, 2, 2, BaseballSwing(false, 1, 1, PitchType.FASTBALL, true), 0, 0))
        fun single(p: Int = 50) = assertEquals(BaseballOutcome.SINGLE, play(PitchType.FASTBALL, 2, 2, BaseballSwing(false, 1, 1, PitchType.FASTBALL, false), 0, p))
        fun double() = assertEquals(BaseballOutcome.DOUBLE, play(PitchType.FASTBALL, 2, 2, BaseballSwing(false, 1, 1, PitchType.FASTBALL, false), 0, 0))
    }

    private fun rig(innings: Int = 3, roster: List<PlayerInfo> = humans, awaySeat: Int = 0): Rig {
        val rolls = ScriptedRolls()
        val game = ToyBaseballGame(Random(1), TbClock(), rolls)
        game.innings = innings; game.awaySeat = awaySeat
        game.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, roster, 0, TbFakeTransport()))
        game.startMatch()
        return Rig(game, rolls)
    }

    // ---- the contact model ----

    @Test fun qualityHasKnownValues() {
        val fast = BaseballSwing(false, 1, 1, PitchType.FASTBALL, false)
        assertEquals(106, BaseballRules.quality(PitchType.FASTBALL, 2, 2, fast, 0))
        assertEquals(94, BaseballRules.quality(PitchType.FASTBALL, 2, 2, fast.copy(power = true), 0))
        assertEquals(100, BaseballRules.quality(PitchType.FASTBALL, 2, 2, fast, -6))
        assertEquals(52, BaseballRules.quality(PitchType.SLIDER, 2, 3, fast, 0))     // timing 1 (24) + place 1 (22) + slider break 8
        assertEquals(34, BaseballRules.quality(PitchType.FASTBALL, 2, 2, fast.copy(timing = PitchType.CHANGEUP), 0))
        assertEquals(106 - 6, BaseballRules.quality(PitchType.FASTBALL, 1, 1, fast.copy(row = 0, col = 0), 0)) // zone corner
        // A pitch outside the zone costs 14 and can never be hit harder than "in play".
        assertEquals(70, BaseballRules.quality(PitchType.FASTBALL, 0, 2, fast.copy(row = 0, col = 1), 0))
        assertTrue(BaseballRules.quality(PitchType.FASTBALL, 0, 2, fast.copy(row = 0, col = 1), 6) <= BaseballRules.OUTSIDE_CAP)
    }

    @Test fun theResultBandsAreExactlyWhereTheRulesSayTheyAre() {
        val o = BaseballOutcome
        fun c(q: Int, power: Boolean = false, p: Int = 50, row: Int = 2) = BaseballRules.contactOutcome(q, power, p, row)
        assertEquals(o.SWING_MISS, c(24)); assertEquals(o.FOUL, c(25)); assertEquals(o.FOUL, c(39))
        assertEquals(o.LINE_OUT, c(40)); assertEquals(o.GROUND_OUT, c(54, row = 4)); assertEquals(o.FLY_OUT, c(54, row = 0))
        assertEquals(o.SINGLE, c(55, p = 49)); assertEquals(o.LINE_OUT, c(55, p = 50)); assertEquals(o.SINGLE, c(74, power = true, p = 39)); assertEquals(o.LINE_OUT, c(74, power = true, p = 40))
        assertEquals(o.DOUBLE, c(75, p = 14)); assertEquals(o.SINGLE, c(75, p = 15)); assertEquals(o.LINE_OUT, c(87, p = 75))
        assertEquals(o.DOUBLE, c(87, power = true, p = 34)); assertEquals(o.SINGLE, c(87, power = true, p = 35))
        assertEquals(o.HOME_RUN, c(88, power = true, p = 59)); assertEquals(o.DOUBLE, c(88, power = true, p = 60)); assertEquals(o.SINGLE, c(88, power = true, p = 85))
        assertEquals(o.DOUBLE, c(88, p = 34)); assertEquals(o.SINGLE, c(88, p = 35)); assertEquals(o.SINGLE, c(107, p = 99))
        assertEquals(o.HOME_RUN, c(108)); assertEquals(o.HOME_RUN, c(130, power = true, p = 99))
    }

    @Test fun betterGuessesAreWorthMore() {
        fun v(q: Int, power: Boolean = false) = BaseballRules.swingValue(q, power, 2, 0)
        assertTrue(v(0) < v(30) + 0.2 && v(106) > v(80) && v(80) > v(60) && v(60) > v(45) - 0.01)
        assertTrue(v(94, power = true) > v(94, power = false))
        assertTrue(BaseballRules.takeValue(2, 2, 0, 0) < BaseballRules.takeValue(0, 0, 0, 0))   // taking a strike is bad, a ball is fine
        assertTrue(BaseballRules.takeValue(0, 0, 3, 0) > BaseballRules.takeValue(0, 0, 0, 0))   // ball four is a walk
        assertTrue(BaseballRules.takeValue(2, 2, 0, 2) < BaseballRules.takeValue(2, 2, 0, 0))   // looking at strike three
    }

    @Test fun pitchTypesAreOrderedBySpeed() {
        assertEquals(listOf(PitchType.FASTBALL, PitchType.SLIDER, PitchType.CURVEBALL, PitchType.CHANGEUP), PitchType.entries.sortedBy { it.speed })
        assertTrue(BaseballRules.inZone(1, 1) && BaseballRules.inZone(3, 3) && !BaseballRules.inZone(0, 2) && !BaseballRules.inZone(2, 4))
    }

    @Test fun cpuProfilesAreOrdered() {
        val e = BaseballRules.profile(CpuDifficulty.EASY); val m = BaseballRules.profile(CpuDifficulty.MEDIUM); val h = BaseballRules.profile(CpuDifficulty.HARD)
        assertTrue(e.adapt < m.adapt && m.adapt < h.adapt); assertTrue(e.noise > m.noise && m.noise > h.noise)
        assertTrue(e.wild >= m.wild && m.wild >= h.wild); assertTrue(h.thinkMs >= 400)
    }

    // ---- a full scripted game ----

    @Test fun aWholeThreeInningGameScriptedPitchByPitch() {
        val r = rig()
        val o = BaseballOutcome
        // Top 1: the away seat (Ana) bats against Ben.
        assertEquals(o.SINGLE, r.play(PitchType.FASTBALL, 2, 2, BaseballSwing(false, 1, 1, PitchType.FASTBALL, false), 0, 50))
        assertEquals(listOf(true, false, false), r.s.bases)
        assertEquals(o.CALLED_STRIKE, r.play(PitchType.FASTBALL, 2, 2, BaseballSwing.TAKE)); assertEquals(1, r.s.strikes)
        assertEquals(o.BALL, r.play(PitchType.FASTBALL, 0, 0, BaseballSwing.TAKE)); assertEquals(1, r.s.balls)
        assertEquals(o.LINE_OUT, r.play(PitchType.SLIDER, 2, 3, BaseballSwing(false, 1, 1, PitchType.FASTBALL, false), 0, 0))
        assertEquals(1, r.s.outs); assertEquals(listOf(true, false, false), r.s.bases); assertEquals(0, r.s.balls + r.s.strikes)
        r.homeRun()
        assertEquals(listOf(2, 0), r.s.score); assertEquals(2, r.s.lastRuns); assertEquals(listOf(false, false, false), r.s.bases)
        r.strikeoutLooking(); assertEquals(2, r.s.outs)
        val miss = BaseballSwing(false, 0, 0, PitchType.CHANGEUP, true)
        assertEquals(o.SWING_MISS, r.play(PitchType.FASTBALL, 2, 2, miss)); assertEquals(o.SWING_MISS, r.play(PitchType.FASTBALL, 2, 2, miss))
        assertEquals(o.STRIKEOUT_SWINGING, r.play(PitchType.FASTBALL, 2, 2, miss))
        assertTrue(r.s.halfEnded); assertFalse(r.s.top); assertEquals(1, r.s.inning); assertEquals(0, r.s.outs)
        assertEquals(1, r.s.batterSeat); assertEquals(0, r.s.pitcherSeat)
        // Bottom 1: Ben bats.
        assertEquals(o.DOUBLE, r.play(PitchType.FASTBALL, 2, 2, BaseballSwing(false, 1, 1, PitchType.FASTBALL, false), -6, 10))
        assertEquals(listOf(false, true, false), r.s.bases)
        r.single(80)
        assertEquals(listOf(true, false, true), r.s.bases); assertEquals(0, r.s.lastRuns)
        r.double()
        assertEquals(listOf(false, true, true), r.s.bases); assertEquals(1, r.s.lastRuns); assertEquals(listOf(2, 1), r.s.score)
        repeat(3) { assertEquals(o.BALL, r.play(PitchType.FASTBALL, 0, 0, BaseballSwing.TAKE)) }
        assertEquals(o.WALK, r.play(PitchType.FASTBALL, 0, 0, BaseballSwing.TAKE))
        assertEquals(listOf(true, true, true), r.s.bases); assertEquals(listOf(2, 1), r.s.score)
        assertEquals(o.DOUBLE, r.play(PitchType.FASTBALL, 2, 2, BaseballSwing(false, 1, 1, PitchType.FASTBALL, true), 0, 70))
        assertEquals(listOf(2, 3), r.s.score); assertEquals(listOf(false, true, true), r.s.bases)
        r.single()
        assertEquals(listOf(2, 4), r.s.score); assertEquals(listOf(true, false, true), r.s.bases)
        repeat(3) {
            assertEquals(o.SWING_MISS, r.play(PitchType.FASTBALL, 2, 2, miss)); assertEquals(o.SWING_MISS, r.play(PitchType.FASTBALL, 2, 2, miss))
            assertEquals(o.STRIKEOUT_SWINGING, r.play(PitchType.FASTBALL, 2, 2, miss))
        }
        assertEquals(2, r.s.inning); assertTrue(r.s.top); assertEquals(listOf(2, 4), r.s.score) // the stranded runners did not score
        // Innings 2 and 3 are all strikeouts. The home seat leads, so the bottom of the 3rd is never played.
        r.retire(); assertFalse(r.s.top); r.retire()
        assertEquals(3, r.s.inning); assertTrue(r.s.top); assertFalse(r.s.over)
        r.retire()
        assertTrue(r.s.over); assertEquals("OVER", r.s.phase)
        assertEquals("b", r.s.winnerPlayerId); assertEquals(listOf(2, 4), r.s.score); assertEquals(listOf(2, 5), r.s.hits)
        assertEquals(listOf(2, 0, 0), r.s.lineScore[0]); assertEquals(listOf(4, 0), r.s.lineScore[1].take(2))
        assertEquals(1, r.game.sessionWins.value["b"]); assertEquals(0, r.game.sessionWins.value["a"])
        assertTrue(r.rolls.empty())
        assertEquals(BaseballReject.NOT_PLAYING, r.game.pitch(0, PitchType.FASTBALL, 2, 2))
    }

    @Test fun theAwaySeatWinsWhenItLeadsAfterTheBottomOfTheLastInning() {
        val r = rig()
        r.homeRun()
        r.retire(); repeat(5) { r.retire() }
        assertTrue(r.s.over); assertEquals("a", r.s.winnerPlayerId); assertEquals(listOf(1, 0), r.s.score)
        assertEquals(3, r.s.inning); assertFalse(r.s.top)
    }

    @Test fun aTieAfterRegulationPlaysAnExtraInningAndAWalkOffEndsItAtOnce() {
        val r = rig()
        repeat(6) { r.retire() } // innings 1-3, nobody scores
        assertEquals(4, r.s.inning); assertTrue(r.s.top); assertFalse(r.s.over)
        r.retire() // top of the 4th
        assertFalse(r.s.top)
        r.homeRun() // the home seat's first batter wins it: no need to finish the half
        assertTrue(r.s.over); assertEquals("b", r.s.winnerPlayerId); assertEquals(listOf(0, 1), r.s.score); assertEquals(0, r.s.outs)
        assertEquals(1, r.s.lineScore[1][3])
    }

    @Test fun aGameStillTiedAfterThreeExtraInningsIsADraw() {
        val r = rig()
        repeat(12) { r.retire() }
        assertTrue(r.s.over); assertNull(r.s.winnerPlayerId); assertEquals(6, r.s.inning)
        assertEquals(1, r.game.sessionDraws.value)
    }

    @Test fun anAwayLeadInAnExtraInningWinsAtTheEndOfIt() {
        val r = rig()
        repeat(6) { r.retire() }
        r.homeRun(); r.retire()
        assertFalse(r.s.over)
        r.retire()
        assertTrue(r.s.over); assertEquals("a", r.s.winnerPlayerId); assertEquals(listOf(1, 0), r.s.score)
    }

    // ---- counts and runners ----

    @Test fun foulsAddStrikesUntilTwoThenNothing() {
        val r = rig()
        val foul = BaseballSwing(false, 1, 1, PitchType.CHANGEUP, false) // q 34 against a fastball in the middle
        assertEquals(BaseballOutcome.FOUL, r.play(PitchType.FASTBALL, 2, 2, foul)); assertEquals(1, r.s.strikes)
        assertEquals(BaseballOutcome.FOUL, r.play(PitchType.FASTBALL, 2, 2, foul)); assertEquals(2, r.s.strikes)
        assertEquals(BaseballOutcome.FOUL, r.play(PitchType.FASTBALL, 2, 2, foul)); assertEquals(2, r.s.strikes); assertEquals(0, r.s.outs)
    }

    @Test fun fourBallsWalkAndAWalkOnlyForcesRunnersAlong() {
        val r = rig()
        r.single() // runner on first
        repeat(3) { r.play(PitchType.FASTBALL, 4, 4, BaseballSwing.TAKE) }
        assertEquals(BaseballOutcome.WALK, r.play(PitchType.FASTBALL, 4, 4, BaseballSwing.TAKE))
        assertEquals(listOf(true, true, false), r.s.bases)
        r.single(); assertEquals(listOf(true, true, true), r.s.bases)
        repeat(3) { r.play(PitchType.FASTBALL, 4, 4, BaseballSwing.TAKE) }
        r.play(PitchType.FASTBALL, 4, 4, BaseballSwing.TAKE)
        assertEquals(1, r.s.lastRuns); assertEquals(listOf(1, 0), r.s.score) // bases loaded walk forces a run
    }

    @Test fun aGroundBallWithARunnerOnFirstAndPoorContactIsADoublePlay() {
        val r = rig()
        r.single()
        val weak = BaseballSwing(false, 2, 1, PitchType.CURVEBALL, true) // q 46 against a fastball at row 3, column 2
        assertEquals(BaseballOutcome.DOUBLE_PLAY, r.play(PitchType.FASTBALL, 3, 2, weak))
        assertEquals(2, r.s.outs); assertEquals(listOf(false, false, false), r.s.bases)
    }

    @Test fun aFlyBallWithARunnerOnThirdAndLessThanTwoOutsIsASacrificeFly() {
        val r = rig()
        r.double(); r.single() // runners on first and third
        assertEquals(listOf(true, false, true), r.s.bases)
        val fly = BaseballSwing(false, 0, 1, PitchType.CURVEBALL, true) // q 46 against a fastball at row 1, column 2
        assertEquals(BaseballOutcome.SAC_FLY, r.play(PitchType.FASTBALL, 1, 2, fly))
        assertEquals(1, r.s.outs); assertEquals(1, r.s.score[0]); assertEquals(listOf(true, false, false), r.s.bases)
    }

    @Test fun twoOutRunnersScoreFromSecondOnASingleAndNoRunsCountOnTheThirdOut() {
        val r = rig()
        r.double(); r.strikeoutLooking(); r.strikeoutLooking()
        assertEquals(2, r.s.outs)
        r.single() // runner from second scores with two outs
        assertEquals(1, r.s.score[0]); assertEquals(listOf(true, false, false), r.s.bases)
        r.strikeoutLooking() // third out: the runner on first never counts
        assertEquals(1, r.s.score[0]); assertTrue(r.s.halfEnded)
    }

    @Test fun everyHomeRunScoresEveryRunnerAndTheBatter() {
        val r = rig()
        r.single(); r.single(); r.single() // bases loaded after three singles, no runs yet
        assertEquals(listOf(true, true, true), r.s.bases)
        assertEquals(0, r.s.score[0])
        r.homeRun()
        assertEquals(4, r.s.score[0]); assertEquals(4, r.s.lastRuns)
    }

    // ---- roles, hiding, refusals ----

    @Test fun onlyTheRightSeatCanActInTheRightPhase() {
        val r = rig()
        assertEquals(BaseballReject.NOT_PITCHER, r.game.pitch(0, PitchType.FASTBALL, 2, 2))
        assertEquals(BaseballReject.WRONG_PHASE, r.game.swing(0, BaseballSwing.TAKE))
        assertEquals(BaseballReject.BAD_INPUT, r.game.pitch(1, PitchType.FASTBALL, 5, 0))
        assertEquals(BaseballReject.BAD_INPUT, r.game.pitch(1, PitchType.FASTBALL, 0, -1))
        assertEquals(BaseballReject.NONE, r.game.pitch(1, PitchType.CURVEBALL, 3, 3))
        assertEquals(BaseballReject.WRONG_PHASE, r.game.pitch(1, PitchType.FASTBALL, 2, 2))
        assertEquals(BaseballReject.NOT_BATTER, r.game.swing(1, BaseballSwing.TAKE))
        assertEquals(BaseballReject.BAD_INPUT, r.game.swing(0, BaseballSwing(false, 3, 0)))
        assertEquals(BaseballReject.NOT_YOUR_SEAT, r.game.swing(2, BaseballSwing.TAKE))
    }

    @Test fun theHumanBatterNeverSeesTheComputersPendingPitch() {
        val clock = TbClock(); val game = ToyBaseballGame(Random(4), clock)
        game.awaySeat = 0
        game.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Bot", isBot = true)), 0, TbFakeTransport()))
        game.startMatch()
        var n = 0
        while (game.state.value!!.phase != "SWING" && n++ < 200) { clock.advance(100); game.tick() }
        assertEquals("SWING", game.state.value!!.phase)
        assertNull(game.state.value!!.pending)
        // ... and the same engine, with Ana pitching, lets her see her own pending choice.
        val g2 = ToyBaseballGame(Random(4), TbClock()); g2.awaySeat = 1
        g2.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Bot", isBot = true)), 0, TbFakeTransport()))
        g2.startMatch()
        g2.pitch(0, PitchType.SLIDER, 1, 1)
        assertEquals(PendingPitch("SLIDER", 1, 1), g2.state.value!!.pending)
    }

    // ---- the computer ----

    private fun cpuGame(seed: Int, level0: CpuDifficulty, level1: CpuDifficulty, innings: Int = 3, away: Int = 0): ToyBaseballGame {
        val clock = TbClock(); val game = ToyBaseballGame(Random(seed), clock)
        game.seatLevelOverride[0] = level0; game.seatLevelOverride[1] = level1; game.innings = innings; game.awaySeat = away
        game.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, bots, 0, TbFakeTransport())); game.startMatch()
        var n = 0
        while (!game.state.value!!.over && n++ < 100_000) { clock.advance(100); game.tick() }
        assertTrue("game $seed did not finish", game.state.value!!.over)
        return game
    }

    private fun duel(a: CpuDifficulty, b: CpuDifficulty, games: Int): Pair<Int, Int> {
        var aw = 0; var bw = 0
        for (i in 0 until games) {
            val aSeat = i % 2
            val g = if (aSeat == 0) cpuGame(500 + i, a, b, away = i / 2 % 2) else cpuGame(500 + i, b, a, away = i / 2 % 2)
            val w = g.state.value!!.winnerPlayerId
            val ws = if (w == null) -1 else if (w == "a") 0 else 1
            if (ws == aSeat) aw++ else if (ws == 1 - aSeat) bw++
        }
        return aw to bw
    }

    @Test fun harderComputersWinMoreOverManySeededGames() {
        val (hardOverEasy, easyOverHard) = duel(CpuDifficulty.HARD, CpuDifficulty.EASY, 60)
        val (hardOverMed, medOverHard) = duel(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 60)
        val (medOverEasy, easyOverMed) = duel(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 60)
        assertTrue("hard v easy $hardOverEasy-$easyOverHard", hardOverEasy > easyOverHard + 10)
        assertTrue("hard v medium $hardOverMed-$medOverHard", hardOverMed > medOverHard)
        assertTrue("medium v easy $medOverEasy-$easyOverMed", medOverEasy > easyOverMed)
    }

    @Test fun theComputerThinksForAWhileBeforeEveryChoice() {
        val clock = TbClock(); val game = ToyBaseballGame(Random(2), clock)
        game.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, bots, 0, TbFakeTransport())); game.startMatch()
        assertFalse(game.tick()) // first look only schedules the choice
        clock.advance(300); assertFalse(game.tick())
        clock.advance(400); // 700 ms now: Hard-ish pauses of ~480-1000 ms: acts by 1.5 s at the latest
        clock.advance(900)
        assertTrue(game.tick())
        assertEquals("SWING", game.state.value!!.phase)
    }

    // ---- fuzz and snapshots ----

    @Test fun randomPlayAlwaysTerminatesWithConsistentScoresAndRoundTripsItsSnapshot() {
        for (seed in 1..80) {
            val rnd = Random(seed)
            val clock = TbClock()
            val roster = if (seed % 3 == 0) listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Bot", isBot = true)) else humans
            val mode = if (roster === humans) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT
            val game = ToyBaseballGame(Random(seed), clock)
            game.innings = listOf(3, 5, 9)[seed % 3]; game.awaySeat = seed % 2; game.difficulty = CpuDifficulty.values()[seed % 3]
            game.init(GameContext(mode, roster, 0, TbFakeTransport())); game.startMatch()
            var steps = 0
            while (!game.state.value!!.over && steps++ < 20_000) {
                clock.advance(rnd.nextLong(0, 400))
                val s = game.state.value!!
                when (rnd.nextInt(5)) {
                    0 -> game.pitch(rnd.nextInt(-1, 3), PitchType.entries[rnd.nextInt(4)], rnd.nextInt(-1, 6), rnd.nextInt(-1, 6))
                    1 -> game.swing(rnd.nextInt(-1, 3), BaseballSwing(rnd.nextBoolean(), rnd.nextInt(-1, 4), rnd.nextInt(-1, 4), PitchType.entries[rnd.nextInt(4)], rnd.nextBoolean()))
                    2 -> game.pitch(s.pitcherSeat, PitchType.entries[rnd.nextInt(4)], rnd.nextInt(5), rnd.nextInt(5))
                    3 -> game.swing(s.batterSeat, BaseballSwing(rnd.nextInt(4) == 0, rnd.nextInt(3), rnd.nextInt(3), PitchType.entries[rnd.nextInt(4)], rnd.nextBoolean()))
                    else -> game.tick()
                }
                val t = game.state.value!!
                assertTrue(t.outs in 0..2 && t.balls in 0..3 && t.strikes in 0..2)
                assertEquals(t.score, t.lineScore.map { it.sum() })
                assertEquals(1 - t.batterSeat, t.pitcherSeat)
                assertTrue(t.history.size <= BaseballRules.HISTORY_KEPT)
                if (t.pending != null) assertEquals("SWING", t.phase)
                if (steps % 53 == 0 && !t.over) {
                    val snap = game.snapshot()
                    assertNotNull(snap)
                    val g2 = ToyBaseballGame(Random(77), TbClock())
                    g2.init(GameContext(mode, roster, 0, TbFakeTransport()))
                    assertTrue("restore $seed", g2.restore(snap!!))
                    assertEquals(t.copy(eventSerial = 0, halfEnded = false), g2.state.value!!.copy(eventSerial = 0, halfEnded = false))
                }
            }
            assertTrue("seed $seed never finished", game.state.value!!.over)
        }
    }

    @Test fun snapshotsAreValidatedAndRefuseGarbage() {
        val r = rig()
        r.single()
        val snap = r.game.snapshot()
        assertNotNull(snap)
        val g = ToyBaseballGame(Random(2), TbClock())
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, TbFakeTransport()))
        assertFalse(g.restore("{nope"))
        assertFalse(g.restore(snap!!.replace("\"outs\":0", "\"outs\":7")))
        assertFalse(g.restore(snap.replace("\"batterSeat\":0", "\"batterSeat\":1")))
        assertFalse(g.restore(snap.replace("\"a\"", "\"zzz\"")))
        assertTrue(g.restore(snap))
        assertEquals(listOf(true, false, false), g.state.value!!.bases)
        val over = rig(); over.retire(36)
        assertNull(over.game.snapshot())
    }
}

package com.gamesuite.games.toyboxing

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Toy Boxing rules, scripted rounds, the turn-based assist, the computer's levels and fuzzing. */
class ToyBoxingRulesTest {
    private val humans = listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Ben"))
    private val bots = listOf(PlayerInfo("a", "Bot A", isBot = true), PlayerInfo("b", "Bot B", isBot = true))

    private class Rig(val game: ToyBoxingGame, val clock: TbClock) {
        /** Moves time on in 25 ms slices, ticking the game after each (the screen ticks about this often). */
        fun adv(ms: Long) {
            var left = ms
            while (left > 0) { val d = minOf(25L, left); clock.advance(d); game.tick(); left -= d }
        }
        val s get() = game.state.value!!
        fun hp(seat: Int) = s.fighters[seat].hp
    }

    private fun rig(
        seed: Int = 1, roster: List<PlayerInfo> = humans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        assist: Boolean = false, knockouts: Boolean = true, rounds: Int = 3, live: Boolean = true
    ): Rig {
        val clock = TbClock()
        val game = ToyBoxingGame(Random(seed), clock)
        game.assist = assist; game.knockouts = knockouts; game.rounds = rounds
        game.init(GameContext(mode, roster, 0, TbFakeTransport()))
        game.startMatch()
        val r = Rig(game, clock)
        if (live && !assist) r.adv(3_000)
        return r
    }

    // ---- numbers ----

    @Test fun damageNumbersAreExact() {
        assertEquals(6, BoxingRules.damage(BoxingMove.JAB, counter = false, tiredAfterCost = false, dizzy = false))
        assertEquals(18, BoxingRules.damage(BoxingMove.HOOK, false, false, false))
        assertEquals(9, BoxingRules.damage(BoxingMove.JAB, true, false, false))
        assertEquals(27, BoxingRules.damage(BoxingMove.HOOK, true, false, false))
        assertEquals(23, BoxingRules.damage(BoxingMove.HOOK, false, false, true))
        assertEquals(4, BoxingRules.damage(BoxingMove.JAB, false, true, false))
        assertEquals(11, BoxingRules.damage(BoxingMove.HOOK, false, true, false))
        assertEquals(4, BoxingRules.points(BoxingMove.HOOK, true))
        assertEquals(4, BoxingRules.riseNeeded(0)); assertEquals(8, BoxingRules.riseNeeded(2))
        assertEquals(35, BoxingRules.riseHp(0)); assertEquals(25, BoxingRules.riseHp(2))
    }

    @Test fun theHookTellIsLongerThanAHumanReactionAndTheJabIsNot() {
        assertTrue(BoxingMove.HOOK.windupMs >= 400)
        assertTrue(BoxingMove.JAB.windupMs <= 150)
        // A dodge begun while the hook winds up is safe at the moment of the strike, a jab started 150 ms in lands first.
        assertTrue(BoxingMove.HOOK.windupMs in (BoxingRules.DODGE_SAFE_FROM_MS + 100)..(BoxingRules.DODGE_SAFE_TO_MS + 100))
        assertTrue(150 + BoxingMove.JAB.windupMs < BoxingMove.HOOK.windupMs)
    }

    @Test fun cpuProfilesGetFasterAndMoreAccurateWithLevel() {
        val e = BoxingRules.profile(CpuDifficulty.EASY); val m = BoxingRules.profile(CpuDifficulty.MEDIUM); val h = BoxingRules.profile(CpuDifficulty.HARD)
        assertTrue(e.reactMs > m.reactMs && m.reactMs > h.reactMs)
        assertTrue(e.reactAccuracy < m.reactAccuracy && m.reactAccuracy < h.reactAccuracy)
        assertTrue(e.decisionMs > m.decisionMs && m.decisionMs > h.decisionMs)
        assertTrue(e.tapMs > m.tapMs && m.tapMs > h.tapMs)
        assertTrue(e.assistAccuracy < m.assistAccuracy && m.assistAccuracy < h.assistAccuracy)
        // Easy cannot dodge a hook it notices late; Hard can.
        assertTrue(e.reactMs * (1 - BoxingRules.JITTER) > BoxingMove.HOOK.windupMs - BoxingRules.DODGE_SAFE_FROM_MS)
        assertTrue(h.reactMs * (1 + BoxingRules.JITTER) < BoxingMove.HOOK.windupMs - BoxingRules.DODGE_SAFE_FROM_MS)
    }

    // ---- a scripted round ----

    @Test fun aJabLandsAfterItsWindUpForSixDamageAndOnePoint() {
        val r = rig()
        assertEquals("FIGHT", r.s.phase)
        assertEquals(BoxingReject.NONE, r.game.play(0, BoxingAction.JAB))
        assertEquals("JAB", r.s.fighters[0].action)
        r.adv(50); assertEquals(100, r.hp(1))
        r.adv(50)
        assertEquals(94, r.hp(1)); assertEquals(1, r.s.fighters[0].points)
        assertEquals(BoxingEvent.JAB_HIT, r.s.event); assertEquals(0, r.s.eventSeat); assertEquals(6, r.s.eventAmount)
        assertEquals("STAGGER", r.s.fighters[1].action)
        assertEquals(92, r.s.fighters[0].stamina)
    }

    @Test fun aWellTimedDodgeMakesAHookMissAndOpensARiposte() {
        val r = rig()
        r.game.play(1, BoxingAction.HOOK)
        r.adv(100)
        assertEquals(BoxingReject.NONE, r.game.play(0, BoxingAction.DODGE))
        r.adv(350)
        assertEquals(BoxingEvent.DODGED, r.s.event); assertEquals(1, r.s.eventSeat)
        assertEquals(100, r.hp(0)); assertTrue(r.s.fighters[0].counter)
        r.adv(350) // the dodge is over; the whiffing hook is still recovering
        assertEquals(BoxingReject.NONE, r.game.play(0, BoxingAction.JAB))
        r.adv(100)
        assertEquals(91, r.hp(1)) // 6 x 1.5 counter
        assertEquals(BoxingEvent.JAB_HIT, r.s.event)
    }

    @Test fun aDodgeThatIsTooEarlyOrTooLateDoesNotSave() {
        for (delay in listOf(0L, 400L)) { // a dodge is safe 100 to 400 ms after it starts; the hook lands at 450: starting at 0 is too early, at 400 too late
            val r = rig()
            r.game.play(1, BoxingAction.HOOK)
            r.adv(delay)
            r.game.play(0, BoxingAction.DODGE)
            r.adv(900)
            assertEquals("delay $delay", 82, r.hp(0))
        }
    }

    @Test fun aJabCancelsAHookThatIsStillWindingUp() {
        val r = rig()
        r.game.play(1, BoxingAction.HOOK)
        r.adv(150)
        r.game.play(0, BoxingAction.JAB)
        r.adv(100)
        assertEquals(94, r.hp(1)); assertEquals("STAGGER", r.s.fighters[1].action)
        r.adv(600)
        assertEquals(100, r.hp(0)) // the hook never landed
    }

    @Test fun aRaisedGuardTakesAllOfAJabButTiresAndAHookGetsHalfThrough() {
        val r = rig()
        r.game.play(1, BoxingAction.BLOCK)
        r.adv(100)
        r.game.play(0, BoxingAction.JAB)
        r.adv(100)
        assertEquals(BoxingEvent.BLOCKED, r.s.event); assertEquals(100, r.hp(1)); assertTrue(r.s.fighters[1].stamina <= 98)
        assertEquals(0, r.s.fighters[0].points)
        r.adv(300)
        r.game.play(0, BoxingAction.HOOK)
        r.adv(450)
        assertEquals(BoxingEvent.BLOCKED, r.s.event); assertEquals(100 - 9, r.hp(1))
    }

    @Test fun aGuardRaisedTooLateOrLoweredEarlyLeavesYouOpen() {
        val late = rig()
        late.game.play(0, BoxingAction.JAB) // lands 100 ms later; a guard needs 100 ms to come up
        late.adv(50)
        late.game.play(1, BoxingAction.BLOCK)
        late.adv(50)
        assertEquals(94, late.hp(1))
        val onTime = rig()
        onTime.game.play(1, BoxingAction.BLOCK)
        onTime.game.play(0, BoxingAction.JAB) // ties go to the guard
        onTime.adv(100)
        assertEquals(100, onTime.hp(1))
        val dropped = rig()
        dropped.game.play(1, BoxingAction.BLOCK)
        dropped.game.play(0, BoxingAction.JAB)
        dropped.adv(50)
        dropped.game.play(1, BoxingAction.GUARD_DOWN)
        dropped.adv(50)
        assertEquals(94, dropped.hp(1))
    }

    @Test fun aChainOfJabsDoesLessAndLessDamageAndStopsScoring() {
        assertEquals(1.0, BoxingRules.comboMultiplier(1), 1e-9); assertEquals(0.7, BoxingRules.comboMultiplier(2), 1e-9)
        assertEquals(0.25, BoxingRules.comboMultiplier(9), 1e-9)
        val r = rig(knockouts = false)
        var total = 0
        repeat(8) { val before = r.hp(1); r.game.play(0, BoxingAction.JAB); r.adv(300); total += before - r.hp(1) }
        assertTrue("8 chained jabs did $total", total in 14..32)
        assertEquals(2, r.s.fighters[0].points) // only the first two hits of the combo scored
    }

    @Test fun mashingRunsOutOfStaminaAndIsRefused() {
        val r = rig(knockouts = false)
        var refused = false
        repeat(80) {
            val res = r.game.play(0, BoxingAction.JAB)
            if (res == BoxingReject.EXHAUSTED) refused = true
            r.adv(300)
        }
        assertTrue(refused)
    }

    @Test fun actionsAreRefusedWithAReasonWhileBusyOrBeforeTheBell() {
        val early = rig(live = false)
        assertEquals(BoxingReject.NOT_STARTED, early.game.play(0, BoxingAction.JAB))
        val r = rig()
        r.game.play(0, BoxingAction.HOOK)
        assertEquals(BoxingReject.BUSY, r.game.play(0, BoxingAction.JAB))
        assertEquals(BoxingReject.BAD_STAGE, r.game.play(1, BoxingAction.REST))
    }

    private fun flatten(r: Rig, attacker: Int) {
        // Rest first so the attacker has stamina, then throw hooks at an idle rival until it is floored.
        r.adv(3_000)
        var guard = 0
        while (r.s.phase == "FIGHT" && guard++ < 40) {
            if (r.game.play(attacker, BoxingAction.HOOK) != BoxingReject.NONE) { r.adv(500); continue }
            r.adv(900)
        }
    }

    @Test fun sixHooksFloorAnIdleFighterAndTappingGetsThemUp() {
        val r = rig()
        flatten(r, 0)
        assertEquals("KNOCKDOWN", r.s.phase); assertEquals(1, r.s.downSeat); assertEquals(0, r.hp(1))
        assertEquals(BoxingEvent.KNOCKDOWN, r.s.event); assertEquals(1, r.s.eventSeat)
        assertEquals("DOWN", r.s.fighters[1].action)
        assertTrue(r.s.fighters[0].points >= 5) // the knockdown bonus
        assertEquals(BoxingReject.NEUTRAL_CORNER, r.game.play(0, BoxingAction.JAB))
        repeat(4) { assertEquals(BoxingReject.NONE, r.game.play(1, BoxingAction.TAP)) }
        r.adv(2_000)
        assertEquals("FIGHT", r.s.phase); assertEquals(BoxingEvent.GET_UP, r.s.event)
        assertEquals(35, r.hp(1)); assertEquals(1, r.s.fighters[1].knockdowns)
    }

    @Test fun notGettingUpByTenIsAKnockout() {
        val r = rig()
        flatten(r, 0)
        r.adv(7_000)
        assertTrue(r.s.over); assertEquals(BoxingEvent.KO, r.s.event)
        assertEquals("a", r.s.winnerPlayerId)
        assertEquals(1, r.game.sessionWins.value["a"])
        assertEquals(BoxingReject.NOT_PLAYING, r.game.play(0, BoxingAction.JAB))
    }

    @Test fun threeKnockdownsInARoundIsATechnicalKnockout() {
        val r = rig()
        var downs = 0
        var guard = 0
        while (!r.s.over && guard++ < 6) {
            flatten(r, 0)
            if (r.s.over) break
            downs++
            val needed = r.s.fighters[1].riseNeeded
            repeat(needed + 1) { r.game.play(1, BoxingAction.TAP) }
            r.adv(2_000)
        }
        assertTrue(r.s.over); assertEquals(BoxingEvent.TKO, r.s.event); assertEquals("a", r.s.winnerPlayerId)
        assertEquals(3, r.s.fighters[1].knockdowns); assertEquals(2, downs)
    }

    @Test fun withKnockoutsOffTheHealthBarStopsAtOne() {
        val r = rig(knockouts = false)
        repeat(30) { r.game.play(0, BoxingAction.HOOK); r.adv(900) }
        assertEquals("FIGHT", r.s.phase); assertTrue(r.hp(1) >= 1)
    }

    @Test fun aQuietRoundIsDecidedOnPointsAndLaterRoundsCanBeTied() {
        val r = rig()
        r.game.play(0, BoxingAction.JAB)
        r.adv(60_200)
        assertEquals(BoxingEvent.ROUND_END, r.s.event); assertEquals(0, r.s.eventSeat)
        assertEquals(1, r.s.fighters[0].roundsWon)
        assertEquals("BREAK", r.s.phase)
        assertTrue(r.hp(1) >= 94) // healed
        r.adv(4_000 + 1_500 + 60_200)
        assertEquals(2, r.s.round)
        assertEquals(BoxingEvent.ROUND_END, r.s.event); assertEquals(-1, r.s.eventSeat) // 0-0 on points and health: a drawn round
        r.adv(4_000 + 1_500 + 60_200)
        assertTrue(r.s.over); assertEquals(BoxingEvent.DECISION, r.s.event); assertEquals("a", r.s.winnerPlayerId)
    }

    @Test fun anEvenBoutIsADraw() {
        val r = rig(rounds = 1)
        r.adv(60_200)
        assertTrue(r.s.over); assertEquals(BoxingEvent.DRAW, r.s.event); assertNull(r.s.winnerPlayerId)
        assertEquals(1, r.game.sessionDraws.value)
    }

    @Test fun theSessionKeepsCountAndPlayAgainDealsAFreshBout() {
        val r = rig(rounds = 1)
        r.game.play(0, BoxingAction.JAB)
        r.adv(60_200)
        assertTrue(r.s.over)
        r.game.playAgain()
        assertFalse(r.s.over); assertEquals(100, r.hp(1)); assertEquals(1, r.game.sessionWins.value["a"])
    }

    // ---- turn-based assist ----

    @Test fun assistExchangesFollowTheSameNumbers() {
        val r = rig(assist = true)
        assertEquals("FIGHT", r.s.phase); assertEquals(0, r.s.assistActor)
        assertEquals(BoxingReject.NOT_YOUR_TURN, r.game.play(1, BoxingAction.JAB))
        assertEquals(BoxingReject.NONE, r.game.play(0, BoxingAction.HOOK))
        assertEquals("DEFEND", r.s.assistStage); assertEquals("HOOK", r.s.assistPending); assertEquals(84, r.s.fighters[0].stamina)
        assertEquals(BoxingReject.NOT_YOUR_TURN, r.game.play(0, BoxingAction.BLOCK))
        assertEquals(BoxingReject.NONE, r.game.play(1, BoxingAction.DODGE))
        assertEquals(BoxingEvent.DODGED, r.s.event); assertEquals(100, r.hp(1)); assertTrue(r.s.fighters[1].counter)
        assertEquals(1, r.s.assistActor); assertEquals(BoxingRules.ASSIST_EXCHANGES - 1, r.s.exchangesLeft)
        assertEquals(98, r.s.fighters[1].stamina); assertEquals(94, r.s.fighters[0].stamina)
        assertEquals(BoxingReject.NONE, r.game.play(1, BoxingAction.HOOK))
        assertEquals(BoxingReject.NONE, r.game.play(0, BoxingAction.REST)) // takes it
        assertEquals(100 - 27, r.hp(0)); assertEquals(BoxingEvent.HOOK_HIT, r.s.event)
        assertEquals(4, r.s.fighters[1].points)
    }

    @Test fun assistBlockingABlowCostsStamina() {
        val r = rig(assist = true)
        r.game.play(0, BoxingAction.HOOK)
        r.game.play(1, BoxingAction.BLOCK)
        assertEquals(BoxingEvent.BLOCKED, r.s.event); assertEquals(91, r.hp(1))
        assertEquals(96, r.s.fighters[1].stamina) // 100 - 14 for the block, +10 recovered after the exchange
    }

    @Test fun bestDefenceDependsOnTheAnnouncedPunchAndTheStaminaLeft() {
        assertEquals(BoxingAction.DODGE, BoxingRules.bestDefence(BoxingMove.HOOK, 20))
        assertEquals(BoxingAction.BLOCK, BoxingRules.bestDefence(BoxingMove.HOOK, 5))
        assertEquals(BoxingAction.BLOCK, BoxingRules.bestDefence(BoxingMove.JAB, 15))
        assertEquals(BoxingAction.DODGE, BoxingRules.bestDefence(BoxingMove.JAB, 25))
        assertEquals(BoxingAction.REST, BoxingRules.bestDefence(BoxingMove.HOOK, 0))
    }

    @Test fun assistRoundsEndAfterTheirExchangesAndTheBoutAfterTheLastRound() {
        val r = rig(assist = true)
        repeat(BoxingRules.ASSIST_EXCHANGES) { assertEquals(BoxingReject.NONE, r.game.play(r.s.assistActor, BoxingAction.REST)) }
        assertEquals(2, r.s.round); assertEquals(BoxingRules.ASSIST_EXCHANGES, r.s.exchangesLeft)
        repeat(BoxingRules.ASSIST_EXCHANGES * 2) { r.game.play(r.s.assistActor, BoxingAction.REST) }
        assertTrue(r.s.over); assertEquals(BoxingEvent.DRAW, r.s.event)
    }

    @Test fun aOneSidedAssistBoutIsWonOnPoints() {
        val r = rig(assist = true)
        var guard = 0
        while (!r.s.over && guard++ < 400) {
            val a = r.s.assistActor
            if (r.s.assistStage == "ATTACK") {
                if (a == 0) { if (r.game.play(0, BoxingAction.HOOK) != BoxingReject.NONE) r.game.play(0, BoxingAction.REST) } else r.game.play(1, BoxingAction.REST)
            } else {
                if (a == 0) r.game.play(1, BoxingAction.REST) else r.game.play(0, BoxingAction.REST)
            }
        }
        assertTrue(r.s.over)
        assertEquals("a", r.s.winnerPlayerId)
    }

    @Test fun assistComputerFightsFullBoutsAndAlwaysFinishes() {
        for (seed in 1..25) {
            val clock = TbClock(); val game = ToyBoxingGame(Random(seed), clock)
            game.assist = true; game.difficulty = CpuDifficulty.values()[seed % 3]
            game.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, bots, 0, TbFakeTransport())); game.startMatch()
            var n = 0
            while (!game.state.value!!.over && n++ < 20_000) { clock.advance(100); game.tick() }
            assertTrue("seed $seed", game.state.value!!.over)
        }
    }

    // ---- the computer's levels ----

    private fun cpuBout(seed: Int, level0: CpuDifficulty, level1: CpuDifficulty): Int {
        val clock = TbClock(); val game = ToyBoxingGame(Random(seed), clock)
        game.seatLevelOverride[0] = level0; game.seatLevelOverride[1] = level1
        game.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, bots, 0, TbFakeTransport())); game.startMatch()
        var n = 0
        while (!game.state.value!!.over && n++ < 30_000) { clock.advance(25); game.tick() }
        assertTrue("bout $seed did not finish", game.state.value!!.over)
        val w = game.state.value!!.winnerPlayerId
        return if (w == null) -1 else if (w == "a") 0 else 1
    }

    /** Wins of [a] minus wins of [b] over seeded bouts, alternating seats so the corner does not matter. */
    private fun margin(a: CpuDifficulty, b: CpuDifficulty, bouts: Int): Pair<Int, Int> {
        var aw = 0; var bw = 0
        for (i in 0 until bouts) {
            val aSeat = i % 2
            val w = if (aSeat == 0) cpuBout(100 + i, a, b) else cpuBout(100 + i, b, a)
            if (w == aSeat) aw++ else if (w == 1 - aSeat) bw++
        }
        return aw to bw
    }

    @Test fun harderComputersBeatEasierOnes() {
        val (hardOverEasy, easyOverHard) = margin(CpuDifficulty.HARD, CpuDifficulty.EASY, 24)
        val (hardOverMed, medOverHard) = margin(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 24)
        val (medOverEasy, easyOverMed) = margin(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 24)
        assertTrue("hard v easy $hardOverEasy-$easyOverHard", hardOverEasy >= easyOverHard + 8)
        assertTrue("hard v medium $hardOverMed-$medOverHard", hardOverMed > medOverHard)
        assertTrue("medium v easy $medOverEasy-$easyOverMed", medOverEasy > easyOverMed)
    }

    @Test fun easyIsBeatableByAPlayerWhoReadsTheTell() {
        // A scripted "reader" dodges every hook and jabs when the rival is stunned or recovering; Easy should lose most bouts.
        var readerWins = 0; var easyWins = 0
        for (seed in 1..12) {
            val clock = TbClock(); val game = ToyBoxingGame(Random(seed), clock)
            game.difficulty = CpuDifficulty.EASY
            game.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("a", "Reader"), PlayerInfo("b", "Bot", isBot = true)), 0, TbFakeTransport()))
            game.startMatch()
            var n = 0
            while (!game.state.value!!.over && n++ < 30_000) {
                clock.advance(25)
                val s = game.state.value!!
                val me = s.fighters[0]; val opp = s.fighters[1]
                when {
                    me.action == "DOWN" -> game.play(0, BoxingAction.TAP)
                    opp.action == "HOOK" && opp.actionAgeMs in 100..250 && me.action == "IDLE" -> game.play(0, BoxingAction.DODGE)
                    opp.action == "HOOK" -> {}
                    me.action == "IDLE" && (opp.action == "STAGGER" || opp.action == "DODGE") && me.stamina >= 16 -> game.play(0, BoxingAction.HOOK)
                    me.action == "IDLE" && me.stamina >= 60 && n % 40 == 0 -> game.play(0, BoxingAction.JAB)
                }
                game.tick()
            }
            val w = game.state.value!!.winnerPlayerId
            if (w == "a") readerWins++ else if (w == "b") easyWins++
        }
        assertTrue("reader $readerWins vs easy $easyWins", readerWins > easyWins)
    }

    // ---- fuzz ----

    @Test fun randomInputNeverBreaksAnInvariantAndEveryBoutTerminates() {
        for (seed in 1..60) {
            val rnd = Random(seed)
            val assist = seed % 4 == 0
            val clock = TbClock()
            val game = ToyBoxingGame(Random(seed), clock)
            game.assist = assist; game.knockouts = seed % 5 != 0; game.rounds = listOf(1, 3, 5)[seed % 3]
            val roster = if (seed % 3 == 0) listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Bot", isBot = true)) else humans
            game.difficulty = CpuDifficulty.values()[seed % 3]
            game.init(GameContext(if (roster === humans) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT, roster, 0, TbFakeTransport()))
            game.startMatch()
            var steps = 0
            while (!game.state.value!!.over && steps++ < 60_000) {
                clock.advance(rnd.nextLong(0, 140))
                if (rnd.nextInt(3) == 0) game.play(rnd.nextInt(-1, 3), BoxingAction.entries[rnd.nextInt(BoxingAction.entries.size)])
                game.tick()
                val s = game.state.value!!
                for (f in s.fighters) {
                    assertTrue(f.hp in 0..100); assertTrue(f.stamina in 0..100); assertTrue(f.points >= 0)
                    assertTrue(f.knockdowns in 0..3)
                }
                assertTrue(s.round in 1..s.rounds)
                if (steps % 97 == 0 && !s.over) {
                    val snap = game.snapshot()
                    if (snap != null) {
                        val g2 = ToyBoxingGame(Random(9), TbClock())
                        g2.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, roster, 0, TbFakeTransport()))
                        assertTrue("restore $seed", g2.restore(snap))
                        val t = g2.state.value!!
                        assertEquals(s.round, t.round); assertEquals(s.fighters.map { it.hp }, t.fighters.map { it.hp })
                        assertEquals(s.fighters.map { it.roundsWon }, t.fighters.map { it.roundsWon })
                        assertEquals(s.fighters.map { it.totalPoints }, t.fighters.map { it.totalPoints })
                        assertEquals(s.assist, t.assist)
                    }
                }
            }
            assertTrue("seed $seed never finished", game.state.value!!.over)
        }
    }

    @Test fun snapshotsAreValidatedAndRefuseGarbage() {
        val r = rig()
        r.game.play(0, BoxingAction.JAB); r.adv(200)
        val snap = r.game.snapshot()
        assertNotNull(snap)
        val g = ToyBoxingGame(Random(2), TbClock())
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans, 0, TbFakeTransport()))
        assertFalse(g.restore("{nope"))
        assertFalse(g.restore(snap!!.replace("\"hp\":[100,94]", "\"hp\":[100,940]")))
        assertFalse(g.restore(snap.replace("\"a\"", "\"zzz\"")))
        assertTrue(g.restore(snap))
        assertEquals("COUNTDOWN", g.state.value!!.phase) // a restored fight starts with a fresh countdown
        assertEquals(94, g.state.value!!.fighters[1].hp)
        // A finished bout and a networked game save nothing.
        val done = rig(rounds = 1); done.adv(61_000)
        assertNull(done.game.snapshot())
    }

    @Test fun theComputerNeverActsOnAHumanSeat() {
        val r = rig(roster = listOf(PlayerInfo("a", "Ana"), PlayerInfo("b", "Bot", isBot = true)), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        r.adv(20_000)
        // Seat 0 is a person who pressed nothing: it can only ever be idle, stunned or down, never mid-punch.
        assertTrue(r.s.fighters[0].action in setOf("IDLE", "STAGGER", "DOWN"))
        assertFalse(r.s.players[0].isBot)
    }
}

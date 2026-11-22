package com.gamesuite.games.pause

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.partypack.RdWhClock
import com.gamesuite.games.partypack.TapTugClock
import com.gamesuite.games.partypack.rdWhContext
import com.gamesuite.games.partypack.tapTugContext
import com.gamesuite.games.reactionduel.DuelPhase
import com.gamesuite.games.reactionduel.PressResult
import com.gamesuite.games.reactionduel.ReactionDuelGame
import com.gamesuite.games.speed.FakeClock
import com.gamesuite.games.speed.SmFakeTransport
import com.gamesuite.games.speed.SpeedGame
import com.gamesuite.games.speed.SpeedPhase
import com.gamesuite.games.speed.SpeedReject
import com.gamesuite.games.taprace.TapRaceGame
import com.gamesuite.games.taprace.TapReject
import com.gamesuite.games.toyboxing.BoxingAction
import com.gamesuite.games.toyboxing.BoxingReject
import com.gamesuite.games.toyboxing.TbClock
import com.gamesuite.games.toyboxing.TbFakeTransport
import com.gamesuite.games.toyboxing.ToyBoxingGame
import com.gamesuite.games.whackamole.WhackAMoleGame
import com.gamesuite.games.whackamole.WhackKind
import com.gamesuite.games.whackamole.WhackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Injected-clock proof that pause() really stops time for the five real-time party games: the countdown does not
 * run, the computer does not act, taps are refused, and after resume() the game continues from where it stopped
 * (not fast-forwarded).
 */
class PauseFreezesTimeTest {
    private fun spin(ms: Long, step: Long = 100L, advance: (Long) -> Unit, tick: () -> Unit) {
        var left = ms
        while (left > 0) { val d = minOf(step, left); advance(d); tick(); left -= d }
    }

    // ---- Whack-a-Mole ----

    @Test fun whackAMoleStandsStillWhilePausedAndContinuesAfter() {
        val clock = RdWhClock()
        val g = WhackAMoleGame(Random(3), clock)
        g.timeSeconds = 60
        g.init(rdWhContext(1, 1))
        g.startMatch()
        val e = g.engine!!
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals(WhackPhase.COUNTDOWN, e.phase)
        val frozen = g.nowMs()
        g.pause()
        spin(90_000, advance = clock::advance, tick = { assertFalse(g.tick()) })
        assertEquals("countdown must not run", WhackPhase.COUNTDOWN, e.phase)
        assertEquals("the game clock is frozen", frozen, g.nowMs())
        assertEquals(WhackKind.IGNORED, g.whack(0, 0).kind)
        assertEquals(0, e.score.sum())
        g.resume()
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals("only 2s of real play so far", WhackPhase.COUNTDOWN, e.phase)
        spin(1500, advance = clock::advance, tick = { g.tick() })
        assertEquals(WhackPhase.PLAY, e.phase)
        assertTrue(e.timeLeftMs(g.nowMs()) in 59_000L..60_000L)
    }

    @Test fun whackAMolePauseDuringPlayHoldsTimeLeftAndTheComputersScore() {
        val clock = RdWhClock()
        val g = WhackAMoleGame(Random(5), clock)
        g.timeSeconds = 60
        g.init(rdWhContext(1, 1))
        g.startMatch()
        val e = g.engine!!
        spin(3000 + 20_000, advance = clock::advance, tick = { g.tick() })
        assertEquals(WhackPhase.PLAY, e.phase)
        val left = e.timeLeftMs(g.nowMs())
        val cpuScore = e.score[1]
        g.pause()
        spin(40_000, advance = clock::advance, tick = { g.tick() })
        assertEquals(left, e.timeLeftMs(g.nowMs()))
        assertEquals(cpuScore, e.score[1])
        g.resume()
        spin(5000, advance = clock::advance, tick = { g.tick() })
        assertTrue(e.timeLeftMs(g.nowMs()) in (left - 5100)..(left - 4900))
        assertNotEquals(WhackPhase.OVER, e.phase)
    }

    // ---- Tap Race ----

    @Test fun tapRaceStandsStillWhilePaused() {
        val clock = TapTugClock()
        val g = TapRaceGame(Random(2), clock)
        g.init(tapTugContext(1, 1))
        g.startMatch()
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals("COUNTDOWN", g.state.value!!.phase)
        g.pause()
        spin(60_000, advance = clock::advance, tick = { assertFalse(g.tick()) })
        assertEquals("COUNTDOWN", g.state.value!!.phase)
        g.resume()
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals("only 2s of real countdown", "COUNTDOWN", g.state.value!!.phase)
        spin(1500, advance = clock::advance, tick = { g.tick() })
        assertEquals("RACE", g.state.value!!.phase)
    }

    @Test fun tapRaceIgnoresTapsWhilePausedAndTheComputerDoesNotRace() {
        val clock = TapTugClock()
        val g = TapRaceGame(Random(4), clock)
        g.init(tapTugContext(1, 1))
        g.startMatch()
        spin(3500, advance = clock::advance, tick = { g.tick() })
        assertEquals("RACE", g.state.value!!.phase)
        val before = g.state.value!!.seats.map { it.progress }
        g.pause()
        assertEquals(TapReject.NOT_RACING, g.press(0))
        spin(20_000, advance = clock::advance, tick = { g.tick() })
        assertEquals(before, g.state.value!!.seats.map { it.progress })
        g.resume()
        spin(500, advance = clock::advance, tick = { g.tick() })
        assertEquals("RACE", g.state.value!!.phase)
    }

    // ---- Toy Boxing ----

    private fun boxer(seed: Int, clock: TbClock): ToyBoxingGame {
        val g = ToyBoxingGame(Random(seed), clock)
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("a", "Me"), PlayerInfo("b", "Bot", isBot = true)), 0, TbFakeTransport()))
        g.startMatch()
        return g
    }

    @Test fun toyBoxingStandsStillWhilePausedAndTheComputerDoesNotPunch() {
        val clock = TbClock()
        val g = boxer(6, clock)
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals("COUNTDOWN", g.state.value!!.phase)
        g.pause()
        spin(60_000, advance = clock::advance, tick = { assertFalse(g.tick()) })
        assertEquals("COUNTDOWN", g.state.value!!.phase)
        assertEquals(BoxingReject.NOT_PLAYING, g.play(0, BoxingAction.JAB))
        g.resume()
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals("COUNTDOWN", g.state.value!!.phase)
        spin(1500, advance = clock::advance, tick = { g.tick() })
        assertEquals("FIGHT", g.state.value!!.phase)
    }

    @Test fun toyBoxingCpuDoesNotLandPunchesDuringAPauseInTheFight() {
        val clock = TbClock()
        val g = boxer(7, clock)
        spin(3500, advance = clock::advance, tick = { g.tick() })
        assertEquals("FIGHT", g.state.value!!.phase)
        g.pause()
        val frozen = g.state.value!!.fighters
        spin(30_000, advance = clock::advance, tick = { g.tick() })
        assertEquals(frozen, g.state.value!!.fighters)
        assertEquals("FIGHT", g.state.value!!.phase)
    }

    // ---- Speed ----

    private fun speed(seed: Int, clock: FakeClock): SpeedGame {
        val g = SpeedGame(Random(seed), clock)
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "Ann"), PlayerInfo("bot1", "Ben", isBot = true)), 0, SmFakeTransport()))
        g.startMatch()
        return g
    }

    @Test fun speedStandsStillWhilePausedAndTheComputerDoesNotPlay() {
        val clock = FakeClock()
        val g = speed(1, clock)
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals(SpeedPhase.COUNTDOWN, g.state.value!!.phase)
        g.pause()
        spin(60_000, advance = clock::advance, tick = { assertFalse(g.tick()) })
        assertEquals(SpeedPhase.COUNTDOWN, g.state.value!!.phase)
        g.resume()
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals(SpeedPhase.COUNTDOWN, g.state.value!!.phase)
        spin(1500, advance = clock::advance, tick = { g.tick() })
        assertEquals(SpeedPhase.PLAYING, g.state.value!!.phase)
    }

    @Test fun speedComputerMakesNoPlaysDuringAPauseInTheGame() {
        val clock = FakeClock()
        val g = speed(2, clock)
        spin(3500, advance = clock::advance, tick = { g.tick() })
        assertEquals(SpeedPhase.PLAYING, g.state.value!!.phase)
        g.pause()
        val frozen = g.state.value
        spin(120_000, advance = clock::advance, tick = { g.tick() })
        assertEquals("nothing on the table changed while paused", frozen, g.state.value)
        val card = frozen!!.hands[0].filterNotNull().first()
        assertEquals(SpeedReject.NOT_PLAYING, g.play(0, card.id, 0))
        g.resume()
        g.tick()
        assertEquals(frozen, g.state.value)
    }

    // ---- Reaction Duel ----

    @Test fun reactionDuelStandsStillWhilePausedAndUsesGameTimeAfter() {
        val clock = RdWhClock()
        val g = ReactionDuelGame(Random(8), clock)
        g.init(rdWhContext(1, 1))
        g.startMatch()
        val e = g.engine!!
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals(DuelPhase.COUNTDOWN, e.phase)
        val frozen = g.nowMs()
        g.pause()
        spin(90_000, advance = clock::advance, tick = { assertFalse(g.tick()) })
        assertEquals(DuelPhase.COUNTDOWN, e.phase)
        assertEquals(frozen, g.nowMs())
        assertEquals(PressResult.IGNORED, g.press(0))
        g.resume()
        spin(1000, advance = clock::advance, tick = { g.tick() })
        assertEquals("only 2s of the 2.8s countdown", DuelPhase.COUNTDOWN, e.phase)
        spin(1500, advance = clock::advance, tick = { g.tick() })
        assertNotEquals(DuelPhase.COUNTDOWN, e.phase)
    }
}

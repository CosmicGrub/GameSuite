package com.gamesuite.games.territorywars

import com.gamesuite.core.CpuLevel
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerritoryWarsGameTest {
    private fun roster(n: Int, bots: Int = 0) = List(n) { PlayerInfo("p$it", "P$it", isBot = it >= n - bots) }

    private fun game(n: Int = 3, bots: Int = 0, configure: TerritoryWarsGame.() -> Unit = { seedOverride = 11L }): TerritoryWarsGame {
        val g = TerritoryWarsGame()
        g.configure()
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, roster(n, bots), 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    @Test fun initRejectsFewerThanTwoOrMoreThanSixSeats() {
        for (n in listOf(1, 7)) {
            val failed = try {
                TerritoryWarsGame().init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, roster(n), 0, LocalPassAndPlayTransport())); false
            } catch (_: IllegalArgumentException) { true }
            assertTrue("$n", failed)
        }
    }

    @Test fun startMatchBuildsAConfigFromHubOptions() {
        val g = game {
            seedOverride = 3L
            options = TerritoryWarsOptions.fromExtras(mapOf("length" to "long", "fortify" to "adjacent", "autoPlace" to "true"))
        }
        val c = g.state.value!!.config
        assertEquals(WarLength.LONG, c.length)
        assertEquals(FortifyReach.ADJACENT, c.fortifyReach)
        assertTrue(c.autoPlace)
        assertEquals(WinRule.ELIMINATION, c.winRule)
        assertEquals(TurnPhase.REINFORCE, g.state.value!!.phase)
    }

    @Test fun blitzWithFiveSeatsPlaysTheFullMap() {
        val g = game(5) { seedOverride = 4L; options = TerritoryWarsOptions(length = WarLength.BLITZ) }
        assertEquals("maravel-36", g.state.value!!.mapId)
    }

    @Test fun blitzWithFewerSeatsPicksAHalfFromTheSeed() {
        assertEquals("maravel-west-18", game(3) { seedOverride = 2L; options = TerritoryWarsOptions(length = WarLength.BLITZ) }.state.value!!.mapId)
        assertEquals("maravel-east-18", game(3) { seedOverride = 3L; options = TerritoryWarsOptions(length = WarLength.BLITZ) }.state.value!!.mapId)
    }

    @Test fun snapshotRoundTripsEveryPhase() {
        val seen = HashSet<TurnPhase>()
        for (seed in 1L..8L) {
            val g = game(3) { seedOverride = seed }
            var steps = 0
            val rnd = kotlin.random.Random(seed)
            while (g.state.value!!.phase != TurnPhase.GAME_OVER && steps++ < 400) {
                val s = g.state.value!!
                val legal = g.legalActions()
                g.apply(legal[rnd.nextInt(legal.size)])
                val text = g.snapshot() ?: break
                seen += g.state.value!!.phase
                val h = game(3) { seedOverride = 99L }
                assertTrue("restore failed in ${g.state.value!!.phase}", h.restore(text))
                val a = g.state.value!!; val b = h.state.value!!
                assertEquals(a.copy(event = null, eventSerial = 0), b.copy(event = null, eventSerial = 0))
                assertNotNull(s)
            }
        }
        assertTrue(seen.containsAll(listOf(TurnPhase.PLACE_INITIAL, TurnPhase.REINFORCE, TurnPhase.ATTACK, TurnPhase.FORTIFY)))
    }

    @Test fun snapshotIsNullBeforeStartAndAfterTheWarEnds() {
        assertNull(TerritoryWarsGame().snapshot())
        val g = game(2, 2) { seedOverride = 5L; options = TerritoryWarsOptions(length = WarLength.BLITZ) }
        var n = 0
        while (g.state.value!!.phase != TurnPhase.GAME_OVER && n++ < 30000) runBlocking { g.playBotStepAsync() }
        assertEquals(TurnPhase.GAME_OVER, g.state.value!!.phase)
        g.leaveSession()
        assertNull(g.snapshot())
    }

    @Test fun restoreRejectsCorruptStaleAndForeignSnapshots() {
        val g = game(3) { seedOverride = 6L }
        val text = g.snapshot()!!
        val h = game(3) { seedOverride = 1L }
        assertFalse(h.restore("garbage"))
        assertFalse(h.restore("{}"))
        assertFalse(game(2).restore(text))
        fun mutate(f: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
            val m = Json.parseToJsonElement(text).jsonObject.toMutableMap(); f(m); return JsonObject(m).toString()
        }
        assertFalse(h.restore(mutate { it["schemaVersion"] = JsonPrimitive(9) }))
        assertFalse(h.restore(mutate { it["mapId"] = JsonPrimitive("nowhere") }))
        assertFalse(h.restore(mutate { it["currentSeat"] = JsonPrimitive(7) }))
        assertFalse(h.restore(mutate { it["phase"] = JsonPrimitive("NOPE") }))
        assertFalse(h.restore(mutate { it["diceDrawn"] = JsonPrimitive(-1) }))
        assertFalse(h.restore(mutate { it["owners"] = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(0))) }))
        val dup = Json.parseToJsonElement(text).jsonObject["deck"].toString()
        assertTrue(dup.length > 5)
        assertFalse(h.restore(mutate { it["deck"] = kotlinx.serialization.json.JsonArray(emptyList()) }))
    }

    @Test fun restoreLeavesTheGameUntouchedOnFailure() {
        val h = game(3) { seedOverride = 1L }
        val before = h.state.value
        assertFalse(h.restore("garbage"))
        assertTrue(h.state.value === before)
    }

    @Test fun restoreFastForwardsTheDiceStream() {
        val g = game(3) { seedOverride = 21L }
        val rnd = kotlin.random.Random(21)
        var guard = 0
        while (g.state.value!!.phase != TurnPhase.ATTACK && guard++ < 200) { val l = g.legalActions(); g.apply(l[rnd.nextInt(l.size)]) }
        // fight a little so the dice stream has moved
        repeat(6) {
            val atk = g.legalActions().filterIsInstance<TerritoryWarsAction.AttackUntilDone>().firstOrNull()
            if (atk != null && g.state.value!!.phase == TurnPhase.ATTACK) g.apply(atk)
            if (g.state.value!!.phase == TurnPhase.MOVE_IN) g.apply(g.legalActions().first())
        }
        val text = g.snapshot() ?: return
        val h = game(3) { seedOverride = 2L }
        assertTrue(h.restore(text))
        val a = g.legalActions().filterIsInstance<TerritoryWarsAction.Attack>().firstOrNull() ?: return
        val ra = g.apply(a) as ActionResult.Accepted
        val rb = h.apply(a) as ActionResult.Accepted
        assertEquals(ra.events, rb.events)
    }

    @Test fun redactedStateNeverContainsAnotherSeatsCards() {
        val s = game(3).state.value!!.let { it.copy(hands = listOf(it.deck.take(2), it.deck.drop(2).take(2), emptyList())) }
        val r = s.redactedFor(0)
        assertEquals(s.hands[0], r.hands[0])
        assertTrue(r.hands[1].all { it.id == "hidden" } && r.hands[1].size == 2)
        assertTrue(r.deck.all { it.id == "hidden" })
    }

    @Test fun leaveSessionRecordsWinsAndALoneLeaderWins() {
        val g = game(2, 2) { seedOverride = 5L; options = TerritoryWarsOptions(length = WarLength.BLITZ) }
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        var n = 0
        while (g.state.value!!.phase != TurnPhase.GAME_OVER && n++ < 30000) runBlocking { g.playBotStepAsync() }
        g.leaveSession()
        assertEquals(2, result!!.scores.size)
        assertTrue(g.sessionWins.value.values.sum() >= 1)
        assertFalse(result!!.wasAborted)
    }

    @Test fun abortMatchEndsWithoutScores() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }

    @Test fun botStepIsAppliedOnlyIfTheTableHasNotMoved() {
        val g = game(2, 2) { seedOverride = 8L }
        assertTrue(g.botToMove())
        val applied = runBlocking { g.playBotStepAsync() }
        assertTrue(applied)
        val h = game(3)
        assertFalse(runBlocking { h.playBotStepAsync() })
    }

    @Test fun playAgainIsBlockedWhenTheKidsTimeIsUp() {
        val g = game(2, 2) { seedOverride = 5L; options = TerritoryWarsOptions(length = WarLength.BLITZ) }
        var n = 0
        while (g.state.value!!.phase != TurnPhase.GAME_OVER && n++ < 30000) runBlocking { g.playBotStepAsync() }
        val over = g.state.value
        LaunchPolicy.timeUp = true
        try { g.playAgain(); assertTrue(g.state.value === over) } finally { LaunchPolicy.resetToFailClosed() }
        g.playAgain()
        assertFalse(g.state.value!!.phase == TurnPhase.GAME_OVER)
        g.cpuLevel = CpuLevel.HARD
    }
}

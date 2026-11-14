package com.gamesuite.core

import com.gamesuite.profiles.AgeBand
import com.gamesuite.profiles.Profile
import com.gamesuite.profiles.ProfileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAudienceTest {
    private fun spec(
        id: String,
        audience: GameAudience = GameAudience.ADULTS,
        minAge: Int = 0,
        network: Boolean = false
    ): GameHubSpec {
        val modes = mutableListOf(
            HubMode("cpu", HubModeKind.VS_CPU, "vs CPU", "", id, HubStart.IMMEDIATE)
        )
        if (network) {
            modes += HubMode("nearby", HubModeKind.NEARBY, "Nearby", "", id, HubStart.LOBBY)
            modes += HubMode("online", HubModeKind.ONLINE, "Online", "", id, HubStart.LOBBY)
        }
        return GameHubSpec(id, id, "", HubFamily.BOARD, modes, audience = audience, minAge = minAge)
    }

    private val adult = Profile("default", "Sam", kind = ProfileKind.ADULT)
    private fun kid(band: AgeBand) = Profile("k1", "Kid", kind = ProfileKind.KID, ageBand = band)

    @Test fun `adult sees everything including gambling games`() {
        val specs = listOf(spec("chess"), spec("blackjack"), spec("tic-tac-toe"), spec("new-adult-game"))
        assertEquals(specs, visibleFor(adult, specs))
    }

    @Test fun `kid never sees gambling themed games whatever the spec says`() {
        val specs = KID_BLOCKED_GAME_IDS.map { spec(it, GameAudience.KIDS, 0) }
        assertTrue(visibleFor(kid(AgeBand.AGE_10_12), specs).isEmpty())
        assertEquals(setOf("blackjack", "texas-holdem", "slot-cars", "hanafuda", "pig-dice"), KID_BLOCKED_GAME_IDS)
    }

    @Test fun `kid never sees untagged adult games`() {
        val specs = listOf(spec("some-new-game"), spec("hangman"), spec("kakuro"), spec("backgammon"))
        assertTrue(visibleFor(kid(AgeBand.AGE_10_12), specs).isEmpty())
    }

    @Test fun `table tags old games and age bands filter by min age`() {
        val specs = listOf("tap-race", "tic-tac-toe", "checkers", "game-2048", "sudoku", "chess").map { spec(it, network = true) }
        fun ids(b: AgeBand) = visibleFor(kid(b), specs).map { it.gameId }
        assertEquals(listOf("tap-race", "tic-tac-toe"), ids(AgeBand.AGE_4_6))
        assertEquals(listOf("tap-race", "tic-tac-toe", "checkers", "game-2048", "chess"), ids(AgeBand.AGE_7_9))
        assertEquals(listOf("tap-race", "tic-tac-toe", "checkers", "game-2048", "sudoku", "chess"), ids(AgeBand.AGE_10_12))
    }

    @Test fun `a spec's own audience and minAge win over the table`() {
        val own = spec("brand-new-kids-game", GameAudience.KIDS, 5)
        assertEquals(listOf("brand-new-kids-game"), visibleFor(kid(AgeBand.AGE_4_6), listOf(own)).map { it.gameId })
        val tooOld = spec("brand-new-family", GameAudience.FAMILY, 10)
        assertTrue(visibleFor(kid(AgeBand.AGE_7_9), listOf(tooOld)).isEmpty())
        // spec says KIDS for a table-listed game: still KIDS
        assertEquals(GameAudience.KIDS, effectiveAudience(spec("chess", GameAudience.KIDS)))
        assertEquals(8, effectiveMinAge(spec("chess")))
        assertEquals(3, effectiveMinAge(spec("chess", minAge = 3)))
    }

    @Test fun `kid never sees online or nearby modes`() {
        val specs = listOf(spec("tic-tac-toe", network = true), spec("connect-four", network = true))
        val seen = visibleFor(kid(AgeBand.AGE_7_9), specs)
        assertEquals(2, seen.size)
        seen.forEach { s ->
            assertFalse(s.isNetworked)
            assertTrue(s.modes.none { it.kind == HubModeKind.ONLINE || it.kind == HubModeKind.NEARBY })
            assertTrue(s.mode(s.defaultModeId) != null)
        }
    }

    @Test fun `parent hidden games are removed for that kid`() {
        val specs = listOf(spec("tic-tac-toe"), spec("connect-four"))
        val seen = visibleFor(kid(AgeBand.AGE_7_9), specs, hiddenGameIds = setOf("connect-four"))
        assertEquals(listOf("tic-tac-toe"), seen.map { it.gameId })
    }

    @Test fun `adult online switch off strips network modes but keeps games`() {
        val specs = listOf(spec("chess", network = true), spec("blackjack", network = true))
        val seen = visibleFor(adult, specs, onlineEnabled = false)
        assertEquals(2, seen.size)
        assertTrue(seen.none { it.isNetworked })
    }

    @Test fun `kid without an age band is treated as the youngest band`() {
        val p = Profile("k2", "K", kind = ProfileKind.KID, ageBand = null)
        val specs = listOf(spec("tap-race"), spec("checkers"))
        assertEquals(listOf("tap-race"), visibleFor(p, specs).map { it.gameId })
    }

    @Test fun `the audience table never lists a gambling game`() {
        assertTrue(GameAudienceTable.ids.intersect(KID_BLOCKED_GAME_IDS).isEmpty())
    }

    @Test fun `every real spec is either tagged or intentionally adult`() {
        // Guards against a typo in the table: every table id must be a real game.
        val real = GameHubSpecs.all.map { it.gameId }.toSet()
        val unknown = GameAudienceTable.ids - real
        assertTrue("table ids with no spec: $unknown", unknown.isEmpty())
    }
}

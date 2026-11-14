package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTeachesTest {
    /** Kid games that tag themselves on their own spec; some are not registered in GameHubSpecs yet (the coordinator does that). */
    private val selfTaggedKidGames = listOf(
        "chutes-race", "echo-pads", "bridge-builders", "draw-guess", "act-it-out",
        "sweet-trail", "trail-together", "find-it", "pie-detective", "stack-zoo", "flick-rink", "bump-run", "egg-garden", "life-road"
    )

    @Test fun `every game a kid could see has a teaches row`() {
        val visible = GameHubSpecs.all.filter { kidMaySee(it, 12) }.map { it.gameId }
        val missing = visible.filterNot { GameTeaches.covers(it) }
        assertTrue("kid-visible games without a teaches row: $missing", missing.isEmpty())
    }

    @Test fun `every audience table game and self tagged kid game is covered`() {
        val tableIds = GameAudienceTable.ids.filterNot { it in KID_BLOCKED_GAME_IDS }
        val missing = (tableIds + selfTaggedKidGames).filterNot { GameTeaches.covers(it) }
        assertTrue("missing teaches rows: $missing", missing.isEmpty())
    }

    @Test fun `each row has one or two distinct skills`() {
        GameTeaches.ids.forEach { id ->
            val s = GameTeaches.skillsFor(id)
            assertTrue("$id has ${s.size} skills", s.size in 1..2)
            assertEquals("$id repeats a skill", s.size, s.toSet().size)
        }
    }

    @Test fun `blocked gambling themed games are not in the table`() {
        KID_BLOCKED_GAME_IDS.forEach { assertTrue("$it must not be in teaches", !GameTeaches.covers(it)) }
    }

    @Test fun `unknown game has no skills`() {
        assertTrue(GameTeaches.skillsFor("nope").isEmpty())
    }

    @Test fun `skill ids are unique and round trip`() {
        assertEquals(9, Skill.values().size)
        Skill.values().forEach { assertEquals(it, Skill.fromId(it.id)) }
    }
}

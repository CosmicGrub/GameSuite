package com.gamesuite.games.partytoolkit

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PartyToolkitStore] (DataStore-backed, needs a real Android `Context`) has no
 * unit test here, matching this app's own established precedent — no StatsStore
 * in this codebase has a plain-JUnit test (no Robolectric), so its persistence
 * is exercised at runtime through the real screen instead. This file covers
 * [PartyToolkitLogic]'s own pure random-pick functions and [PartyToolkitGame]'s
 * token lifecycle.
 */
class PartyToolkitGameTest {

    @Test
    fun `rollDice returns exactly count values, each within 1 until sides inclusive`() {
        val random = Random(1)
        repeat(200) {
            val count = (1..6).random(random)
            val sides = listOf(4, 6, 8, 10, 12, 20).random(random)
            val roll = PartyToolkitLogic.rollDice(count, sides, random)
            assertEquals(count, roll.size)
            assertTrue("roll=$roll sides=$sides", roll.all { it in 1..sides })
        }
    }

    @Test
    fun `rollDice with a non-positive count or sides returns an empty list, not a crash`() {
        assertEquals(emptyList<Int>(), PartyToolkitLogic.rollDice(0))
        assertEquals(emptyList<Int>(), PartyToolkitLogic.rollDice(-1))
        assertEquals(emptyList<Int>(), PartyToolkitLogic.rollDice(3, sides = 0))
    }

    @Test
    fun `flipCoin returns both outcomes across many trials`() {
        val random = Random(2)
        val results = (1..200).map { PartyToolkitLogic.flipCoin(random) }.toSet()
        assertEquals("a fair coin flipped 200 times should show both heads and tails", setOf(true, false), results)
    }

    @Test
    fun `randomLetter is always an uppercase A-Z letter, and produces real variety`() {
        val random = Random(3)
        val letters = (1..200).map { PartyToolkitLogic.randomLetter(random) }
        assertTrue(letters.all { it in 'A'..'Z' })
        assertTrue("200 draws should produce more than a handful of distinct letters", letters.toSet().size > 10)
    }

    @Test
    fun `pickFirstPlayer returns null for an empty roster, and always a real name otherwise`() {
        assertNull(PartyToolkitLogic.pickFirstPlayer(emptyList()))

        val names = listOf("Alice", "Bo", "Cid")
        val random = Random(4)
        repeat(50) {
            val picked = PartyToolkitLogic.pickFirstPlayer(names, random)
            assertTrue(picked in names)
        }
    }

    @Test
    fun `pickFirstPlayer picks every name over enough trials, not just one favorite`() {
        val names = listOf("Alice", "Bo", "Cid", "Deb")
        val random = Random(5)
        val picks = (1..400).map { PartyToolkitLogic.pickFirstPlayer(names, random) }.toSet()
        assertEquals(names.toSet(), picks)
    }

    @Test
    fun `splitIntoTeams partitions every name exactly once, with sizes differing by at most 1`() {
        val names = listOf("Alice", "Bo", "Cid", "Deb", "Eve", "Fay", "Gus")
        val random = Random(6)
        for (teamCount in 1..names.size) {
            val teams = PartyToolkitLogic.splitIntoTeams(names, teamCount, random)
            assertEquals(teamCount, teams.size)
            assertEquals("every name must appear exactly once across all teams", names.toSet(), teams.flatten().toSet())
            assertEquals("no name should be duplicated or dropped", names.size, teams.sumOf { it.size })
            val sizes = teams.map { it.size }
            assertTrue("team sizes=$sizes should differ by at most 1", (sizes.max() - sizes.min()) <= 1)
        }
    }

    @Test
    fun `splitIntoTeams clamps an out-of-range team count instead of crashing`() {
        val names = listOf("Alice", "Bo", "Cid")
        val random = Random(7)
        assertEquals(1, PartyToolkitLogic.splitIntoTeams(names, teamCount = 0, random).size)
        assertEquals(names.size, PartyToolkitLogic.splitIntoTeams(names, teamCount = 999, random).size)
    }

    @Test
    fun `splitIntoTeams on an empty roster returns no teams`() {
        assertEquals(emptyList<List<String>>(), PartyToolkitLogic.splitIntoTeams(emptyList(), teamCount = 3))
    }

    private fun newGame(): PartyToolkitGame {
        val game = PartyToolkitGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    @Test
    fun `leaveToolkit reports an unscored result through the onMatchEnd listener`() {
        val game = newGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }

        game.leaveToolkit()

        assertEquals(GameResult(scores = emptyList()), reported)
    }

    @Test
    fun `startMatch, pause, and resume are genuine no-ops -- they never throw and never invoke the match-end listener`() {
        val game = newGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }

        game.startMatch()
        game.pause()
        game.resume()

        assertNull("none of startMatch/pause/resume should end the toolkit session", reported)
    }
}

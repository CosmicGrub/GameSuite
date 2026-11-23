package com.gamesuite.profiles

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P6 follow-up: a kid whose time is up must not restart a round in place. Every game's `fun playAgain()` therefore has to
 * consult [com.gamesuite.core.LaunchPolicy.requestNewRound] before it starts anything. This ratchet scans the game sources
 * so a new game cannot add an ungated "Play again".
 */
class PlayAgainGateTest {
    private val gamesDir = File("src/main/java/com/gamesuite/games")

    // Engine interface declarations / stubs without a body that starts a round.
    private val exempt = setOf("FindItEngine.kt", "StackZooEngine.kt")

    @Test fun everyGamePlayAgainAsksTheLaunchPolicyFirst() {
        assertTrue("games dir not found from ${File(".").absolutePath}", gamesDir.isDirectory)
        val offenders = mutableListOf<String>()
        var seen = 0
        gamesDir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in exempt }.forEach { f ->
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                if (Regex("""\bfun playAgain\(\)""").containsMatchIn(line)) {
                    seen++
                    val window = lines.subList(i, minOf(lines.size, i + 4)).joinToString("\n")
                    if ("requestNewRound" !in window) offenders += "${f.name}:${i + 1}"
                }
            }
        }
        assertTrue("expected to scan many playAgain functions, saw $seen", seen >= 60)
        assertTrue("playAgain without LaunchPolicy.requestNewRound(): $offenders", offenders.isEmpty())
    }
}

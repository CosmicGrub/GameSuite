package com.gamesuite.games.wordgames.crossword

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.wordgames.WordSafety
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CrosswordGenerationTest {
    private fun game(seed: Int, difficulty: CpuDifficulty, size: Int = 15): CrosswordGame {
        val g = CrosswordGame(size, Random(seed))
        g.difficulty = difficulty
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(PlayerInfo("you", "You")), 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    @Test fun everyGeneratedGridHasNoRunWithoutAClueAcrossManySeeds() {
        var total = 0
        var minEntries = Int.MAX_VALUE
        for (difficulty in CpuDifficulty.values()) for (seed in 0 until 150) {
            val s = game(seed, difficulty).state.value!!
            val problems = CrosswordGridValidator.problems(s.grid, s.entries)
            assertEquals("seed $seed $difficulty", emptyList<String>(), problems)
            total += s.entries.size
            minEntries = minOf(minEntries, s.entries.size)
        }
        assertTrue("min entries $minEntries", minEntries >= 3)
        assertTrue(total > 0)
    }

    @Test fun smallerGridsStayValidToo() {
        for (seed in 0 until 60) {
            val s = game(seed, CpuDifficulty.EASY, size = 11).state.value!!
            assertEquals(emptyList<String>(), CrosswordGridValidator.problems(s.grid, s.entries))
        }
    }

    @Test fun theValidatorCatchesAnUncluedRunAndAMismatch() {
        val cells = listOf(
            listOf(CrosswordCell('A'), CrosswordCell('B'), CrosswordCell(null)),
            listOf(CrosswordCell('C'), CrosswordCell('D'), CrosswordCell(null)),
            listOf(CrosswordCell(null), CrosswordCell(null), CrosswordCell(null))
        )
        val entry = CrosswordEntry("e0", "AB", "first", Direction.ACROSS, CrosswordPos(0, 0), 1)
        val problems = CrosswordGridValidator.problems(cells, listOf(entry))
        assertTrue(problems.any { it.contains("unclued ACROSS run 'CD'") })
        assertTrue(problems.any { it.contains("unclued DOWN run 'AC'") })
        val bad = CrosswordEntry("e1", "ZZ", "", Direction.ACROSS, CrosswordPos(0, 0), 1)
        assertTrue(CrosswordGridValidator.problems(cells, listOf(bad)).any { it.contains("no clue") })
    }

    @Test fun everyAnswerInEveryEnglishBankHasAClueAndIsSafe() {
        val banks = listOf(CrosswordClueBank.gamesAndTechTheme, CrosswordClueBank.easyEverydayTheme, CrosswordClueBank.generalKnowledgeTheme)
        for (bank in banks) for (e in bank) {
            assertTrue("clue for ${e.word}", e.clue.isNotBlank())
            assertTrue(e.word, e.word.all { it in 'A'..'Z' })
            assertFalse("unsafe ${e.word}", WordSafety.isBlocked(e.word) || WordSafety.isBlocked(e.clue))
        }
    }

    @Test fun everyEntryOfAGeneratedPuzzleHasAClue() {
        for (seed in 0 until 40) game(seed, CpuDifficulty.MEDIUM).state.value!!.entries.forEach { assertTrue(it.clue.isNotBlank()) }
    }
}

package com.gamesuite.games.wordgames.wordsearch

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Resume (snapshot/restore) and Hint for Word Search. Word Search has no undo and no best-time record. */
class WordSearchResumeHintTest {

    private val fakeDictionary: Map<Int, List<String>> = (4..15).associateWith { length ->
        val r = Random(length * 1_000L)
        List(400) { String(CharArray(length) { 'a' + r.nextInt(26) }) }.distinct()
    }

    private val onePlayer = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))
    private val twoPlayers = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))

    private fun newGame(
        players: List<PlayerInfo> = onePlayer,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    ): WordSearchGame {
        val game = WordSearchGame(wordsOfLength = { length -> fakeDictionary[length] ?: emptyList() })
        game.difficulty = difficulty
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(seed: Long? = 7L, difficulty: CpuDifficulty = CpuDifficulty.MEDIUM) =
        newGame(difficulty = difficulty).also { it.startMatch(seed) }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first puzzle, after the session ends, or in online play`() {
        val game = newGame()
        assertNull(game.snapshot())
        game.startMatch(seed = 1L)
        assertNotNull(game.snapshot())
        game.leaveSession()
        assertNull(game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch(seed = 1L) }
        assertNull(online.snapshot())
    }

    @Test
    fun `a round trip restores the identical puzzle, progress, tally, hints and daily label`() {
        val game = started(seed = 42L)
        // Solve one puzzle so the tally is non-zero, then play a fresh daily-style board part way.
        for (w in game.state.value!!.placedWords) game.attemptSelection(w.cells.first(), w.cells.last())
        assertEquals(1, game.puzzlesSolved.value)
        game.startMatch(seed = 43L)
        val words = game.state.value!!.placedWords
        game.attemptSelection(words[0].cells.first(), words[0].cells.last())
        game.hint()
        assertEquals(1, game.hintsUsed.value)

        val saved = game.snapshot()!!
        val copy = newGame(difficulty = CpuDifficulty.EASY)
        assertTrue(copy.restore(saved))

        val a = game.state.value!!
        val b = copy.state.value!!
        assertEquals(a.grid, b.grid)
        assertEquals(a.placedWords, b.placedWords)
        assertEquals(a.foundWords, b.foundWords)
        assertEquals(a.solved, b.solved)
        assertNull(b.selectionStart)
        assertEquals(1, copy.puzzlesSolved.value)
        assertEquals(1, copy.hintsUsed.value)
        assertEquals(CpuDifficulty.MEDIUM, copy.difficulty)
        assertTrue("a resumed daily board stays the daily puzzle", copy.isDailyBoard.value)
        assertNull("a hint ring is cleared by restore", copy.hintState.value)

        // The same next move leads to the same position on both.
        val next = words[1]
        assertNotNull(game.attemptSelection(next.cells.first(), next.cells.last()))
        assertNotNull(copy.attemptSelection(next.cells.first(), next.cells.last()))
        assertEquals(game.state.value!!.foundWords, copy.state.value!!.foundWords)
        assertEquals(game.snapshot(), copy.snapshot())
    }

    @Test
    fun `a solved puzzle can be snapshotted and resumes as solved`() {
        val game = started()
        for (w in game.state.value!!.placedWords) game.attemptSelection(w.cells.first(), w.cells.last())
        assertTrue(game.state.value!!.solved)
        val copy = newGame()
        assertTrue(copy.restore(game.snapshot()!!))
        assertTrue(copy.state.value!!.solved)
        assertEquals(1, copy.puzzlesSolved.value)
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a damaged grid and leaves the engine untouched`() {
        val source = started()
        val good = source.snapshot()!!

        val target = started(seed = 99L)
        val before = target.snapshot()
        assertFalse(target.restore("not json"))
        assertFalse(target.restore("{}"))
        assertFalse(target.restore(good.replace("\"difficulty\":\"MEDIUM\"", "\"difficulty\":\"BOGUS\"")))
        val shortRow = Regex("\"gridRows\":\\[\"([A-Z]+)\"").replace(good) {
            "\"gridRows\":[\"" + it.groupValues[1].dropLast(1) + "\""
        }
        assertFalse("a row of the wrong length", target.restore(shortRow))
        val firstWord = Regex("\"word\":\"([A-Z]+)\"").find(good)!!
        val brokenWord = good.replaceRange(
            firstWord.range,
            "\"word\":\"!" + firstWord.groupValues[1].drop(1) + "\""
        )
        assertFalse("a word that no longer matches the grid", target.restore(brokenWord))
        assertEquals("a rejected restore changes nothing", before, target.snapshot())

        val twoSeat = newGame(players = twoPlayers).also { it.startMatch(seed = 5L) }
        assertFalse("different roster", twoSeat.restore(good))
        val botSeat = newGame(players = listOf(PlayerInfo("p1", "Player 1", isBot = true)))
        assertFalse("different bot flag", botSeat.restore(good))
        assertNull(botSeat.state.value)
    }

    @Test
    fun `restore reopens an engine whose session had ended`() {
        val game = started()
        val saved = game.snapshot()!!
        game.leaveSession()
        assertTrue(game.matchOver.value)
        assertTrue(game.restore(saved))
        assertFalse(game.matchOver.value)
    }

    @Test
    fun `new puzzle drops the daily label and the hints`() {
        val game = started(seed = 3L)
        assertTrue(game.isDailyBoard.value)
        game.hint()
        assertEquals(1, game.hintsUsed.value)
        game.playAgain()
        assertFalse(game.isDailyBoard.value)
        assertEquals(0, game.hintsUsed.value)
        assertNull(game.hintState.value)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint rings the first letter of an unfound word and marks the puzzle assisted`() {
        val game = started()
        val s = game.state.value!!
        val h = game.hint()!!
        assertEquals(s.placedWords.first(), h.word)
        assertEquals(h.word.cells.first(), h.cell)
        assertEquals(h.word.word.first(), s.grid[h.cell.row][h.cell.col])
        assertEquals(h, game.hintState.value)
        assertEquals(1, game.hintsUsed.value)

        // Asking again for the same word costs nothing more.
        game.hint()
        assertEquals(1, game.hintsUsed.value)
    }

    @Test
    fun `finding a word clears the ring and the next hint picks a different word`() {
        val game = started()
        val first = game.hint()!!.word
        assertNotNull(game.attemptSelection(first.cells.first(), first.cells.last()))
        assertNull(game.hintState.value)
        val second = game.hint()!!
        assertTrue(second.word.id != first.id)
        assertTrue(second.word.id !in game.state.value!!.foundWords)
        assertEquals(2, game.hintsUsed.value)
    }

    @Test
    fun `hint is null once the puzzle is solved or the session has ended, and an assisted solve still counts`() {
        val game = started()
        game.hint()
        for (w in game.state.value!!.placedWords) game.attemptSelection(w.cells.first(), w.cells.last())
        assertTrue(game.state.value!!.solved)
        assertNull(game.hint())
        assertEquals(1, game.puzzlesSolved.value)
        assertEquals("hints stay on the record for the result text", 1, game.hintsUsed.value)

        val other = started()
        other.leaveSession()
        assertNull(other.hint())
        assertNull(newGame().hint())
    }
}

package com.gamesuite.games.wordgames.wordsearch

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [WordSearchGame] normally draws its words from the shared 359k-line `WordDictionary`, which
 * needs a real Android Context to load its asset (no Robolectric in this project). Its
 * `wordsOfLength` constructor seam lets these tests hand it a small, fixed fake dictionary
 * instead, which is also what makes the Daily puzzle's "same seed, same puzzle, words included"
 * promise checkable at all: it can only hold if word choice goes through the seeded Random.
 */
class WordSearchGameTest {

    /** 400 distinct, fixed lowercase "words" for every length a tier can ask for (4..15). */
    private val fakeDictionary: Map<Int, List<String>> = (4..15).associateWith { length ->
        val r = Random(length * 1_000L)
        List(400) { String(CharArray(length) { 'a' + r.nextInt(26) }) }.distinct()
    }

    private fun newGame(
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
        words: (Int) -> List<String> = { length -> fakeDictionary[length] ?: emptyList() }
    ): WordSearchGame {
        val game = WordSearchGame(wordsOfLength = words)
        game.difficulty = difficulty
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun WordSearchGame.solveEveryWord() {
        for (placed in state.value!!.placedWords) {
            assertNotNull(
                "Selecting ${placed.word} along its own cells should claim it",
                attemptSelection(placed.cells.first(), placed.cells.last())
            )
        }
    }

    @Test
    fun `every placed word is spelled out by its cells`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newGame(difficulty)
            repeat(20) { trial ->
                game.startMatch(seed = trial.toLong())
                val s = game.state.value!!
                assertTrue("$difficulty trial $trial placed no words", s.placedWords.isNotEmpty())
                for (placed in s.placedWords) {
                    val spelled = placed.cells.joinToString("") { s.grid[it.row][it.col].toString() }
                    assertEquals(placed.word, spelled)
                    assertTrue(placed.cells.all { it.row in s.grid.indices && it.col in s.grid.indices })
                }
            }
        }
    }

    @Test
    fun `grid size follows the difficulty tier`() {
        assertEquals(8, newGame(CpuDifficulty.EASY).also { it.startMatch(1L) }.state.value!!.grid.size)
        assertEquals(12, newGame(CpuDifficulty.MEDIUM).also { it.startMatch(1L) }.state.value!!.grid.size)
        assertEquals(15, newGame(CpuDifficulty.HARD).also { it.startMatch(1L) }.state.value!!.grid.size)
    }

    @Test
    fun `EASY only places words left-to-right or top-to-bottom`() {
        val game = newGame(CpuDifficulty.EASY)
        repeat(30) { trial ->
            game.startMatch(seed = trial.toLong())
            for (placed in game.state.value!!.placedWords) {
                val dr = placed.cells[1].row - placed.cells[0].row
                val dc = placed.cells[1].col - placed.cells[0].col
                assertTrue("EASY word ${placed.word} runs ($dr, $dc)", (dr == 0 && dc == 1) || (dr == 1 && dc == 0))
            }
        }
    }

    @Test
    fun `no word is placed twice in one puzzle`() {
        val game = newGame()
        repeat(100) {
            game.startMatch()
            val words = game.state.value!!.placedWords.map { it.word }
            assertEquals(words.distinct().size, words.size)
        }
    }

    @Test
    fun `the same seed reproduces the identical puzzle, words included`() {
        for (difficulty in CpuDifficulty.entries) {
            val a = newGame(difficulty).also { it.startMatch(seed = 20_000L) }
            val b = newGame(difficulty).also { it.startMatch(seed = 20_000L) }
            assertTrue(a.state.value!!.placedWords.isNotEmpty())
            assertEquals("$difficulty daily puzzle differs between two players", a.state.value, b.state.value)
        }
    }

    @Test
    fun `a seeded puzzle does not depend on earlier unseeded draws`() {
        val fresh = newGame().also { it.startMatch(seed = 7L) }
        val busy = newGame()
        repeat(10) { busy.startMatch() }
        busy.startMatch(seed = 7L)
        assertEquals(fresh.state.value, busy.state.value)
    }

    @Test
    fun `different seeds give different puzzles`() {
        val grids = (1L..3L).map { seed -> newGame().also { it.startMatch(seed) }.state.value!!.grid }
        assertTrue("Three different seeds produced the same grid", grids.distinct().size > 1)
    }

    @Test
    fun `an empty word source still yields a board without throwing`() {
        val game = newGame(words = { emptyList() })
        game.startMatch(seed = 1L)
        val s = game.state.value!!
        assertTrue(s.placedWords.isEmpty())
        assertEquals(12, s.grid.size)
        assertTrue(s.grid.all { row -> row.all { it in 'A'..'Z' } })
    }

    @Test
    fun `dragging a word's cells in either direction claims it`() {
        val forward = newGame().also { it.startMatch(seed = 3L) }
        val word = forward.state.value!!.placedWords.first()
        val forwardResult = forward.attemptSelection(word.cells.first(), word.cells.last())
        assertNotNull(forwardResult)
        assertEquals(word, forwardResult!!.word)
        assertEquals(word.cells, forwardResult.orderedCells)
        assertTrue(word.id in forward.state.value!!.foundWords)

        val backward = newGame().also { it.startMatch(seed = 3L) }
        val backwardResult = backward.attemptSelection(word.cells.last(), word.cells.first())
        assertNotNull(backwardResult)
        assertEquals(word, backwardResult!!.word)
        assertEquals(word.cells.reversed(), backwardResult.orderedCells)
    }

    @Test
    fun `a miss leaves found words alone and clears the pending start`() {
        val game = newGame().also { it.startMatch(seed = 3L) }
        game.setSelectionStart(GridPos(0, 0))
        assertEquals(GridPos(0, 0), game.state.value!!.selectionStart)

        // (0,0) to (1,2) is not a straight line.
        assertNull(game.attemptSelection(GridPos(0, 0), GridPos(1, 2)))
        assertTrue(game.state.value!!.foundWords.isEmpty())
        assertNull(game.state.value!!.selectionStart)
    }

    @Test
    fun `finding every word solves the puzzle once and counts it`() {
        val game = newGame().also { it.startMatch(seed = 11L) }
        game.solveEveryWord()
        val s = game.state.value!!
        assertTrue(s.solved)
        assertEquals(1, game.puzzlesSolved.value)
        // A solved puzzle takes no further selections.
        val first = s.placedWords.first()
        assertNull(game.attemptSelection(first.cells.first(), first.cells.last()))
        assertEquals(1, game.puzzlesSolved.value)
    }

    @Test
    fun `leaving after a solved puzzle scores the tally and is not an abort`() {
        val game = newGame().also { it.startMatch(seed = 11L) }
        var result: GameResult? = null
        game.setOnMatchEnd { result = it }
        game.solveEveryWord()
        game.leaveSession()
        val ended = result!!
        assertFalse(ended.wasAborted)
        assertEquals(1, ended.scores.size)
        assertEquals(1, ended.scores.single().score)
        assertTrue(ended.scores.single().isWinner)
    }

    @Test
    fun `abortMatch ends the session as an abort with no scores`() {
        val game = newGame().also { it.startMatch(seed = 11L) }
        var result: GameResult? = null
        game.setOnMatchEnd { result = it }
        game.abortMatch()
        val ended = result!!
        assertTrue(ended.wasAborted)
        assertTrue(ended.scores.isEmpty())
        assertTrue(game.matchOver.value)
    }

    @Test
    fun `input after the session ended is ignored and the end fires once`() {
        val game = newGame().also { it.startMatch(seed = 11L) }
        var ends = 0
        game.setOnMatchEnd { ends++ }
        val before = game.state.value
        val word = before!!.placedWords.first()

        game.leaveSession()
        game.leaveSession()
        assertEquals(1, ends)

        assertNull(game.attemptSelection(word.cells.first(), word.cells.last()))
        game.setSelectionStart(GridPos(2, 2))
        game.playAgain()
        assertSame(before, game.state.value)
        assertTrue(game.state.value!!.foundWords.isEmpty())
        assertNull(game.state.value!!.selectionStart)
    }

    @Test
    fun `pause and resume are harmless in any order`() {
        val game = newGame().also { it.startMatch(seed = 11L) }
        game.pause()
        game.pause()
        game.resume()
        game.resume()
        game.pause()
        assertFalse(game.state.value!!.solved)
    }
}

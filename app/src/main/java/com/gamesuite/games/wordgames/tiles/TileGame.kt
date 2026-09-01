package com.gamesuite.games.wordgames.tiles

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.wordgames.WordDictionary

data class BoardCell(val tile: RackTile? = null, val letterOverride: Char? = null) {
    /** The letter to score/validate with — a blank's chosen letter, or the tile's own letter. */
    val effectiveLetter: Char? get() = letterOverride ?: tile?.letter
}

data class TilePlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val rack: List<RackTile>,
    val score: Int = 0
)

data class PendingPlacement(val row: Int, val col: Int, val tile: RackTile, val chosenLetter: Char)

data class TileGameState(
    val board: List<List<BoardCell>>,
    val players: List<TilePlayerState>,
    val currentPlayerIndex: Int,
    val bagCount: Int,
    val pending: List<PendingPlacement> = emptyList(),
    val consecutivePasses: Int = 0,
    val lastAction: String = "",
    val matchOver: Boolean = false
)

/**
 * Word-with-Friends/Scrabble-style tile game: 15x15 premium-square board,
 * standard letter distribution, dictionary-validated plays. Placement is
 * tap-based (select a rack tile, tap a board cell) rather than drag, for
 * consistency with the rest of the app's input style.
 *
 * Scope note: this validates and scores real Scrabble-style moves (main
 * word + all crossing words, premium squares, bingo bonus) but the bot is a
 * bounded heuristic (see TileBot), not a full move-generator/solver.
 */
class TileGame : GameModule {
    override val gameId = "word-tiles"
    override val displayName = "Word Tiles"
    override val category = GameCategory.WORD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<TileGameState?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var bag: MutableList<RackTile> = mutableListOf()

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    fun loadDictionary(androidContext: Context) {
        WordDictionary.ensureLoaded(androidContext)
    }

    override fun startMatch() {
        bag = TileBag.freshBag()
        val board = List(BOARD_SIZE) { List(BOARD_SIZE) { BoardCell() } }
        val players = context.players.map { info ->
            TilePlayerState(
                playerId = info.playerId,
                displayName = info.displayName,
                isBot = info.isBot,
                rack = drawTiles(7)
            )
        }
        state.value = TileGameState(
            board = board,
            players = players,
            currentPlayerIndex = 0,
            bagCount = bag.size,
            lastAction = "Game started"
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value?.copy(matchOver = true)
        onMatchEnd?.invoke(result)
    }

    // ---- Placement staging (before submit) ----

    fun stageTile(row: Int, col: Int, tile: RackTile, chosenLetter: Char) {
        val s = state.value ?: return
        if (s.board[row][col].tile != null) return
        if (s.pending.any { it.row == row && it.col == col }) return
        state.value = s.copy(pending = s.pending + PendingPlacement(row, col, tile, chosenLetter))
    }

    fun unstageTile(row: Int, col: Int) {
        val s = state.value ?: return
        state.value = s.copy(pending = s.pending.filterNot { it.row == row && it.col == col })
    }

    fun clearStaged() {
        val s = state.value ?: return
        state.value = s.copy(pending = emptyList())
    }

    /** Validates the staged placement, scores it, commits it, and advances the turn. Returns an error message, or null on success. */
    fun submitMove(): String? {
        val s = state.value ?: return "No active game"
        if (s.pending.isEmpty()) return "Place at least one tile first"

        val boardHasAnyTile = s.board.any { row -> row.any { it.tile != null } }
        val words = detectWords(s) ?: return "Tiles must form a single connected line"
        if (words.isEmpty()) return "No valid word formed"
        val coveredCells = words.flatMap { it.cells }.toSet()
        if (s.pending.any { (it.row to it.col) !in coveredCells }) {
            return "All placed tiles must form part of a word"
        }
        if (boardHasAnyTile && words.none { it.touchesExisting }) return "Must connect to an existing word"
        if (!boardHasAnyTile && words.none { w -> w.cells.any { it.first == CENTER && it.second == CENTER } }) {
            return "First word must cover the center square"
        }

        for (w in words) {
            if (!WordDictionary.isValidWord(w.text)) return "\"${w.text}\" is not a valid word"
        }

        val gained = words.sumOf { scoreWord(it, s) } + if (s.pending.size == 7) 50 else 0

        var newBoard = s.board.map { it.toMutableList() }
        s.pending.forEach { p ->
            newBoard[p.row][p.col] = BoardCell(tile = p.tile, letterOverride = if (p.tile.isBlank) p.chosenLetter else null)
        }

        val player = s.players[s.currentPlayerIndex]
        val usedIds = s.pending.map { it.tile.instanceId }.toSet()
        val newRack = player.rack.filterNot { it.instanceId in usedIds } + drawTiles(s.pending.size)
        val updatedPlayers = s.players.toMutableList()
        updatedPlayers[s.currentPlayerIndex] = player.copy(rack = newRack, score = player.score + gained)

        val nextIndex = (s.currentPlayerIndex + 1) % s.players.size
        val matchOver = newRack.isEmpty() && bag.isEmpty()

        state.value = s.copy(
            board = newBoard,
            players = updatedPlayers,
            currentPlayerIndex = nextIndex,
            bagCount = bag.size,
            pending = emptyList(),
            consecutivePasses = 0,
            lastAction = "${player.displayName} played ${words.joinToString(", ") { it.text }} for $gained points",
            matchOver = matchOver
        )

        if (matchOver) finishGame()
        return null
    }

    fun pass() {
        val s = state.value ?: return
        val player = s.players[s.currentPlayerIndex]
        val consecutivePasses = s.consecutivePasses + 1
        val allPassed = consecutivePasses >= s.players.size * 2

        state.value = s.copy(
            pending = emptyList(),
            currentPlayerIndex = (s.currentPlayerIndex + 1) % s.players.size,
            consecutivePasses = consecutivePasses,
            lastAction = "${player.displayName} passed",
            matchOver = allPassed
        )
        if (allPassed) finishGame()
    }

    fun swapTiles(tileIds: Set<Int>) {
        val s = state.value ?: return
        if (bag.size < tileIds.size) return
        val player = s.players[s.currentPlayerIndex]
        val kept = player.rack.filterNot { it.instanceId in tileIds }
        val returning = player.rack.filter { it.instanceId in tileIds }
        bag.addAll(returning)
        bag.shuffle()
        val drawn = drawTiles(returning.size)

        val updatedPlayers = s.players.toMutableList()
        updatedPlayers[s.currentPlayerIndex] = player.copy(rack = kept + drawn)

        state.value = s.copy(
            players = updatedPlayers,
            currentPlayerIndex = (s.currentPlayerIndex + 1) % s.players.size,
            bagCount = bag.size,
            lastAction = "${player.displayName} swapped ${tileIds.size} tile(s)"
        )
    }

    // ---- Bot driver ----

    fun playBotTurn() {
        val s = state.value ?: return
        if (s.matchOver) return
        val current = s.players[s.currentPlayerIndex]
        if (!current.isBot) return

        val move = TileBot.findMove(s, current)
        if (move == null) {
            pass()
            return
        }
        move.forEach { (row, col, tile, letter) -> stageTile(row, col, tile, letter) }
        val error = submitMove()
        if (error != null) {
            // Bot proposed an invalid move (shouldn't normally happen) — bail out safely.
            clearStaged()
            pass()
        }
    }

    // ---- Word detection & scoring ----

    data class DetectedWord(val text: String, val cells: List<Pair<Int, Int>>, val touchesExisting: Boolean)

    private fun detectWords(s: TileGameState): List<DetectedWord>? {
        val pending = s.pending
        val rows = pending.map { it.row }.distinct()
        val cols = pending.map { it.col }.distinct()
        val horizontal = rows.size == 1
        val vertical = cols.size == 1
        if (!horizontal && !vertical) return null
        if (pending.size == 1) {
            // Single tile: direction is ambiguous, try both and take whichever yields words.
            val h = buildWordsAssumingDirection(s, isHorizontalMain = true)
            val v = buildWordsAssumingDirection(s, isHorizontalMain = false)
            return (h + v).distinctBy { it.cells }
        }
        return buildWordsAssumingDirection(s, isHorizontalMain = horizontal)
    }

    private fun buildWordsAssumingDirection(s: TileGameState, isHorizontalMain: Boolean): List<DetectedWord> {
        val merged = mergedBoard(s)
        val results = mutableListOf<DetectedWord>()

        // Main word: extend through each pending tile's row/col to full contiguous run.
        val anyPending = s.pending.firstOrNull() ?: return emptyList()
        val mainWord = extendWord(merged, anyPending.row, anyPending.col, horizontal = isHorizontalMain)
            ?: return emptyList()
        if (mainWord.text.length < 2 && s.pending.size > 1) return emptyList()
        if (mainWord.text.length >= 2) results.add(mainWord)

        // Crossing words: for every pending tile, check the perpendicular direction.
        for (p in s.pending) {
            val cross = extendWord(merged, p.row, p.col, horizontal = !isHorizontalMain)
            if (cross != null && cross.text.length >= 2) results.add(cross)
        }

        return results.map { it.copy(touchesExisting = it.touchesExisting || hasExistingNeighbor(s)) }
    }

    private fun mergedBoard(s: TileGameState): Array<Array<Char?>> {
        val grid = Array(BOARD_SIZE) { r -> Array<Char?>(BOARD_SIZE) { c -> s.board[r][c].effectiveLetter } }
        s.pending.forEach { p -> grid[p.row][p.col] = p.chosenLetter }
        return grid
    }

    private fun extendWord(grid: Array<Array<Char?>>, row: Int, col: Int, horizontal: Boolean): DetectedWord? {
        var startRow = row; var startCol = col
        while (true) {
            val pr = if (horizontal) startRow else startRow - 1
            val pc = if (horizontal) startCol - 1 else startCol
            if (pr < 0 || pc < 0 || grid[pr][pc] == null) break
            startRow = pr; startCol = pc
        }
        val sb = StringBuilder()
        val cells = mutableListOf<Pair<Int, Int>>()
        var r = startRow; var c = startCol
        while (r < BOARD_SIZE && c < BOARD_SIZE && grid[r][c] != null) {
            sb.append(grid[r][c])
            cells.add(r to c)
            if (horizontal) c++ else r++
        }
        if (sb.length < 2) return null
        return DetectedWord(sb.toString(), cells, touchesExisting = false)
    }

    private fun hasExistingNeighbor(s: TileGameState): Boolean {
        return s.pending.any { p ->
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dr, dc) ->
                val r = p.row + dr; val c = p.col + dc
                r in 0 until BOARD_SIZE && c in 0 until BOARD_SIZE && s.board[r][c].tile != null
            }
        }
    }

    private fun scoreWord(word: DetectedWord, s: TileGameState): Int {
        var wordMultiplier = 1
        var total = 0
        word.cells.forEachIndexed { i, (r, c) ->
            val pendingHere = s.pending.firstOrNull { it.row == r && it.col == c }
            val existing = s.board[r][c].tile
            val letterValue = when {
                pendingHere != null -> TileBag.valueOf(pendingHere.tile)
                existing != null -> TileBag.valueOf(existing)
                else -> 0
            }
            val squareType = TileBoardLayout.typeAt(r, c)
            val isNewTile = pendingHere != null
            var cellScore = letterValue
            if (isNewTile) {
                when (squareType) {
                    SquareType.DOUBLE_LETTER -> cellScore *= 2
                    SquareType.TRIPLE_LETTER -> cellScore *= 3
                    SquareType.DOUBLE_WORD, SquareType.CENTER -> wordMultiplier *= 2
                    SquareType.TRIPLE_WORD -> wordMultiplier *= 3
                    else -> {}
                }
            }
            total += cellScore
        }
        return total * wordMultiplier
    }

    private fun finishGame() {
        val s = state.value ?: return

        // Standard Scrabble/WWF end-game adjustment: when the match ended because a
        // player emptied their rack with the bag empty, that finisher is credited with
        // the sum of every opponent's remaining rack value, and each opponent is
        // debited their own remaining rack value. A match ended by consecutive passes
        // gets no such adjustment (no player finished their rack).
        val finisher = if (bag.isEmpty()) s.players.firstOrNull { it.rack.isEmpty() } else null
        val adjustedPlayers = if (finisher != null) {
            val players = s.players.toMutableList()
            var bonus = 0
            for (i in players.indices) {
                val p = players[i]
                if (p.playerId != finisher.playerId) {
                    val rackValue = p.rack.sumOf { TileBag.valueOf(it) }
                    bonus += rackValue
                    players[i] = p.copy(score = p.score - rackValue)
                }
            }
            val finisherIndex = players.indexOfFirst { it.playerId == finisher.playerId }
            if (finisherIndex >= 0) {
                players[finisherIndex] = players[finisherIndex].copy(score = players[finisherIndex].score + bonus)
            }
            players
        } else {
            s.players
        }

        val maxScore = adjustedPlayers.maxOf { it.score }
        val result = GameResult(
            scores = adjustedPlayers.map { PlayerScore(playerId = it.playerId, score = it.score, isWinner = it.score == maxScore) }
        )
        state.value = s.copy(players = adjustedPlayers)
        endMatch(result)
    }

    private fun drawTiles(count: Int): List<RackTile> {
        val n = minOf(count, bag.size)
        val drawn = bag.take(n)
        bag = bag.drop(n).toMutableList()
        return drawn
    }
}

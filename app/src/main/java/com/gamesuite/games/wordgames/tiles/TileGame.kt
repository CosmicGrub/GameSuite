package com.gamesuite.games.wordgames.tiles

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.wordgames.WordDictionary
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a Word Tiles match back exactly as it was: the board, both racks, the bag
 * IN ORDER (so the same tiles are drawn next), scores, pass count, whose turn it is and the bot
 * difficulty. Tiles staged on the board but not yet submitted are NOT saved: they never left the
 * player's rack in the engine, so a resumed game simply shows them back in the rack. The dictionary
 * is not part of the snapshot (it is loaded from assets by the screen).
 */
@Serializable
private data class TileGameSnapshot(
    val boardSize: Int,
    val cells: List<TileGameSnapshotCell?>,
    val players: List<TileGameSnapshotPlayer>,
    val bag: List<TileGameSnapshotTile>,
    val currentPlayerIndex: Int,
    val consecutivePasses: Int,
    val lastAction: String,
    val lastPlayedWords: List<String>,
    val difficulty: String
)

@Serializable
private data class TileGameSnapshotTile(val letter: String, val isBlank: Boolean, val instanceId: Int)

/** A tile on the board plus a blank's chosen letter (null for a normal tile). */
@Serializable
private data class TileGameSnapshotCell(val tile: TileGameSnapshotTile, val letterOverride: String? = null)

@Serializable
private data class TileGameSnapshotPlayer(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val rack: List<TileGameSnapshotTile>,
    val score: Int
)

private val tileGameSnapshotJson = Json { ignoreUnknownKeys = true }

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

/**
 * How escalated a completed play's own visual/haptic flourish should read —
 * see TileGameScreen.kt's word-completion flourish for what each tier
 * actually draws. Computed from the exact same information [scoreWord] and
 * [TileBoardLayout] already track internally (premium squares touched, and
 * submitMove()'s own existing 7-tile Bingo check), just surfaced to the UI
 * instead of only feeding the point total.
 */
enum class WordPlayTier { ORDINARY, MULTIPLIER, BINGO }

/**
 * [TileGame.setOnWordPlayed]'s payload — fired once per successful [TileGame.submitMove], mirroring the "engine reports the event, screen owns the animation" split games/solitaire/SolitaireGame.kt's CardMove/setOnCardMoved already established.
 *
 * [playerIndex] / [byBot] say WHO played, so the screen can celebrate only the human's own plays
 * (a CPU word used to fire the human's success haptic, chime and sparkle, and a CPU Bingo fired the
 * match-win celebration). The defaults exist only so older call sites that build one by hand still compile.
 */
data class WordPlayResult(
    val words: List<String>,
    val pointsGained: Int,
    val tier: WordPlayTier,
    val playerIndex: Int = -1,
    val byBot: Boolean = false
)

data class TileGameState(
    val board: List<List<BoardCell>>,
    val players: List<TilePlayerState>,
    val currentPlayerIndex: Int,
    val bagCount: Int,
    val pending: List<PendingPlacement> = emptyList(),
    val consecutivePasses: Int = 0,
    val lastAction: String = "",
    /**
     * Word(s) formed by the most recently *committed* play (submitMove already
     * dictionary-checks every word before it's ever applied to the board, so
     * anything landing here is guaranteed valid — see submitMove). Exists purely
     * so the UI can offer a "tap to confirm this is a real word" affordance for
     * an opponent's obscure-looking play; it is not a scoring or challenge
     * mechanic. Left untouched by pass()/swapTiles() since no word was formed.
     */
    val lastPlayedWords: List<String> = emptyList(),
    val matchOver: Boolean = false
)

/**
 * The player(s) holding the top score: one entry for a sole winner, several for a tie (mirrors
 * [TileGame]'s own `isWinner = score == maxScore` result), empty only for a state with no players.
 * Lives here, not in the screen, so the "who won / is it a tie" rule has one plain-JVM-testable home.
 */
fun TileGameState.leaders(): List<TilePlayerState> {
    val top = players.maxOfOrNull { it.score } ?: return emptyList()
    return players.filter { it.score == top }
}

/**
 * Word-with-Friends/Scrabble-style tile game: 15x15 premium-square board,
 * standard letter distribution, dictionary-validated plays. Placement is
 * tap-based (select a rack tile, tap a board cell), with drag-and-drop on
 * top of it in TileGameScreen.
 *
 * Once the match is over (a rack emptied with the bag empty, every player
 * passing twice in a row, or an abort) every input function is a no-op and
 * [endMatch] reports exactly once, so a late tap or the bot's delayed turn
 * cannot touch a finished or abandoned game.
 *
 * Scope note: this validates and scores real Scrabble-style moves (main
 * word + all crossing words, premium squares, bingo bonus) but the bot is a
 * bounded heuristic (see TileBot), not a full move-generator/solver.
 */
class TileGame : GameModule, ResumableGame {
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

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * The roster seat that takes the first turn of the NEXT [startMatch] (Game Hub "who goes first").
     * Out-of-range values are clamped. Consumed (reset to 0) by [startMatch]; a restored game never
     * reads it, since its snapshot already knows whose turn it is.
     */
    var firstMoverIndex: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var onWordPlayed: ((WordPlayResult) -> Unit)? = null
    private var bag: MutableList<RackTile> = mutableListOf()

    /** True once [endMatch] has reported; makes a second finish/abort a no-op. Reset by [startMatch]. */
    private var matchEnded = false

    override fun init(context: GameContext) {
        this.context = context
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun tileToSnapshot(t: RackTile) = TileGameSnapshotTile(t.letter.toString(), t.isBlank, t.instanceId)

    /** A letter field is exactly one character; a normal tile is A-Z, a blank tile's own letter is a space. */
    private fun tileFromSnapshot(t: TileGameSnapshotTile): RackTile? {
        if (t.letter.length != 1) return null
        val ch = t.letter[0]
        if (t.isBlank) {
            if (ch != ' ') return null
        } else if (ch !in 'A'..'Z') return null
        return RackTile(ch, t.isBlank, t.instanceId)
    }

    /** Null when there is nothing to save: no match yet, the match already over, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        return try {
            val s = state.value ?: return null
            if (s.matchOver || matchEnded) return null
            if (!this::context.isInitialized) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
                context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
            ) return null
            val cells = ArrayList<TileGameSnapshotCell?>(BOARD_SIZE * BOARD_SIZE)
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
                val cell = s.board[r][c]
                val tile = cell.tile
                cells.add(
                    if (tile == null) null
                    else TileGameSnapshotCell(tileToSnapshot(tile), cell.letterOverride?.toString())
                )
            }
            tileGameSnapshotJson.encodeToString(
                TileGameSnapshot.serializer(),
                TileGameSnapshot(
                    boardSize = BOARD_SIZE,
                    cells = cells,
                    players = s.players.map { p ->
                        TileGameSnapshotPlayer(p.playerId, p.displayName, p.isBot, p.rack.map { tileToSnapshot(it) }, p.score)
                    },
                    bag = bag.map { tileToSnapshot(it) },
                    currentPlayerIndex = s.currentPlayerIndex,
                    consecutivePasses = s.consecutivePasses,
                    lastAction = s.lastAction,
                    lastPlayedWords = s.lastPlayedWords,
                    difficulty = difficulty.name
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    /** All-or-nothing: the whole new state is built and checked first, then assigned. */
    override fun restore(snapshot: String): Boolean {
        try {
            if (!this::context.isInitialized) return false
            val snap = tileGameSnapshotJson.decodeFromString(TileGameSnapshot.serializer(), snapshot)
            val roster = context.players
            if (snap.boardSize != BOARD_SIZE || snap.cells.size != BOARD_SIZE * BOARD_SIZE) return false
            if (snap.players.size != roster.size) return false
            for (i in roster.indices) {
                if (snap.players[i].playerId != roster[i].playerId || snap.players[i].isBot != roster[i].isBot) return false
            }
            if (snap.currentPlayerIndex !in snap.players.indices) return false
            if (snap.consecutivePasses < 0) return false

            val seenIds = HashSet<Int>()
            fun tile(t: TileGameSnapshotTile): RackTile? {
                val rt = tileFromSnapshot(t) ?: return null
                return if (seenIds.add(rt.instanceId)) rt else null
            }

            val rows = List(BOARD_SIZE) { r ->
                List(BOARD_SIZE) { c ->
                    val sc = snap.cells[r * BOARD_SIZE + c]
                    if (sc == null) BoardCell() else {
                        val rt = tile(sc.tile) ?: return false
                        val chosen = sc.letterOverride
                        if (rt.isBlank) {
                            if (chosen == null || chosen.length != 1 || chosen[0] !in 'A'..'Z') return false
                        } else if (chosen != null) return false
                        BoardCell(rt, chosen?.get(0))
                    }
                }
            }
            val players = ArrayList<TilePlayerState>(snap.players.size)
            for (sp in snap.players) {
                val rack = sp.rack.map { tile(it) ?: return false }
                if (rack.size > 7) return false
                players.add(TilePlayerState(sp.playerId, sp.displayName, sp.isBot, rack, sp.score))
            }
            val newBag = snap.bag.map { tile(it) ?: return false }.toMutableList()
            val newDifficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty

            matchEnded = false
            bag = newBag
            difficulty = newDifficulty
            state.value = TileGameState(
                board = rows,
                players = players,
                currentPlayerIndex = snap.currentPlayerIndex,
                bagCount = newBag.size,
                pending = emptyList(),
                consecutivePasses = snap.consecutivePasses,
                lastAction = snap.lastAction,
                lastPlayedWords = snap.lastPlayedWords,
                matchOver = false
            )
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /** See [WordPlayResult]'s KDoc — registered once by TileGameScreen, same precedent as [setOnMatchEnd]. */
    fun setOnWordPlayed(listener: (WordPlayResult) -> Unit) {
        onWordPlayed = listener
    }

    fun loadDictionary(androidContext: Context) {
        WordDictionary.ensureLoaded(androidContext)
    }

    override fun startMatch() {
        matchEnded = false
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
        val first = firstMoverIndex.coerceIn(0, maxOf(0, players.size - 1))
        firstMoverIndex = 0
        state.value = TileGameState(
            board = board,
            players = players,
            currentPlayerIndex = first,
            bagCount = bag.size,
            lastAction = "Game started"
        )
    }

    // No timers or per-frame work to stop, so both are trivially idempotent no-ops.
    override fun pause() {}
    override fun resume() {}

    /**
     * Ends the match (finished or aborted) and reports it once. A second call, for example an abort
     * that lands just after the last tile was played, is ignored instead of firing [onMatchEnd] again.
     */
    override fun endMatch(result: GameResult) {
        if (matchEnded) return
        matchEnded = true
        state.value = state.value?.copy(matchOver = true)
        onMatchEnd?.invoke(result)
    }

    // ---- Placement staging (before submit) ----

    fun stageTile(row: Int, col: Int, tile: RackTile, chosenLetter: Char) {
        val s = state.value ?: return
        if (s.matchOver) return
        if (s.board[row][col].tile != null) return
        if (s.pending.any { it.row == row && it.col == col }) return
        state.value = s.copy(pending = s.pending + PendingPlacement(row, col, tile, chosenLetter))
    }

    fun unstageTile(row: Int, col: Int) {
        val s = state.value ?: return
        if (s.matchOver) return
        state.value = s.copy(pending = s.pending.filterNot { it.row == row && it.col == col })
    }

    fun clearStaged() {
        val s = state.value ?: return
        if (s.matchOver) return
        state.value = s.copy(pending = emptyList())
    }

    /** Validates the staged placement, scores it, commits it, and advances the turn. Returns an error message, or null on success. */
    fun submitMove(): String? {
        val s = state.value ?: return "No active game"
        if (s.matchOver) return "The game is over"
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

        val isBingo = s.pending.size == 7
        val gained = words.sumOf { scoreWord(it, s) } + if (isBingo) 50 else 0
        // Surfaced to WordPlayResult below (item: word-completion flourish) -- the exact
        // same premium-square check scoreWord() already makes per-cell internally, just
        // read here at the pending-placement level to classify the WHOLE play rather than
        // accumulate into a point total.
        val usedPremiumSquare = s.pending.any { p -> TileBoardLayout.typeAt(p.row, p.col) != SquareType.NORMAL }
        val tier = when {
            isBingo -> WordPlayTier.BINGO
            usedPremiumSquare -> WordPlayTier.MULTIPLIER
            else -> WordPlayTier.ORDINARY
        }

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
            lastPlayedWords = words.map { it.text }.distinct(),
            matchOver = matchOver
        )

        onWordPlayed?.invoke(
            WordPlayResult(
                words = words.map { it.text }.distinct(),
                pointsGained = gained,
                tier = tier,
                playerIndex = s.currentPlayerIndex,
                byBot = player.isBot
            )
        )
        if (matchOver) finishGame()
        return null
    }

    /**
     * Non-mutating: would [submitMove] currently succeed if called right now? Mirrors that
     * function's own validation chain exactly but commits nothing -- used by
     * TileGameScreen's idle Submit-button pulse, which needs to know a staged placement is
     * a REAL, submittable word before nudging the player, not just "something is staged"
     * (an illegal or incomplete placement shouldn't get an inviting pulse).
     */
    fun currentStagedWordIsValid(): Boolean {
        val s = state.value ?: return false
        if (s.pending.isEmpty()) return false
        val boardHasAnyTile = s.board.any { row -> row.any { it.tile != null } }
        val words = detectWords(s) ?: return false
        if (words.isEmpty()) return false
        val coveredCells = words.flatMap { it.cells }.toSet()
        if (s.pending.any { (it.row to it.col) !in coveredCells }) return false
        if (boardHasAnyTile && words.none { it.touchesExisting }) return false
        if (!boardHasAnyTile && words.none { w -> w.cells.any { it.first == CENTER && it.second == CENTER } }) return false
        return words.all { WordDictionary.isValidWord(it.text) }
    }

    fun pass() {
        val s = state.value ?: return
        if (s.matchOver) return
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
        if (s.matchOver) return
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

        val move = TileBot.findMove(s, current, difficulty)
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

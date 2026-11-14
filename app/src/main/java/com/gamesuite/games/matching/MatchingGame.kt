package com.gamesuite.games.matching

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.core.SnapshotHistory
import com.gamesuite.core.UndoableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** What the last change to the table was; the screen words, animates and announces it. */
object MatchingEvent {
    const val START = "start"
    const val FLIP = "flip"
    const val MATCH = "match"
    const val MISS = "miss"
    const val WIN = "win"
    const val TIE = "tie"
}

enum class MatchingReject { NONE, NOT_PLAYING, NOT_YOUR_TURN, RESOLVING, NOT_FACE_DOWN, OUT_OF_RANGE }

data class MatchingPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/** A suggestion: flip these cards ([known] true = a pair already seen; false = a card nobody has seen). */
data class MatchingHint(val cards: List<Int>, val known: Boolean)

/**
 * [faces] is the picture number on each card, or -1 where THIS device does not know it (a networked
 * guest never learns a face-down card's picture). [status] is a [MatchingStatus] per card; [owner]
 * is the seat that matched it (-1 if not matched). [resolving] is true while two cards are face up
 * waiting for [MatchingGame.resolve]. The three serials only grow and key the animations.
 */
data class MatchingState(
    val players: List<MatchingPlayerState>,
    val cardCount: Int,
    val faces: List<Int>,
    val status: List<Int>,
    val owner: List<Int>,
    val currentPlayerIndex: Int,
    val scores: List<Int>,
    val moves: List<Int>,
    val resolving: Boolean,
    val event: String = MatchingEvent.START,
    val eventCards: List<Int> = emptyList(),
    val eventSeat: Int? = null,
    val flipSerial: Int = 0,
    val resolveSerial: Int = 0,
    val dealSerial: Int = 0,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val tie: Boolean = false
) {
    val pairs: Int get() = cardCount / 2
}

@Serializable
private data class MatchingSnapshot(
    val players: List<MatchingSnapshotPlayer>,
    val cardCount: Int,
    val faces: List<Int>,
    val status: List<Int>,
    val owner: List<Int>,
    val seen: List<Boolean>,
    val current: Int,
    val scores: List<Int>,
    val moves: List<Int>,
    val memory: List<Map<Int, Int>>,
    val hintsUsed: Int,
    val undosUsed: Int,
    val elapsedMs: Long,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val tie: Boolean,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStarter: Int,
    val difficulty: String,
    val flipSerial: Int = 0,
    val resolveSerial: Int = 0,
    val dealSerial: Int = 0
)

@Serializable
private data class MatchingSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val matchingJson = Json { ignoreUnknownKeys = true }

/**
 * Matching for 1 to 4 players (see [MatchingRules]). Solo play counts moves (one move = two cards
 * turned) and time, and can Undo and ask for a Hint; with several seats a pair keeps the turn.
 *
 * TIME comes only from the injected [clock] (milliseconds), SHUFFLES and the computer's lapses only
 * from [random], so a seeded game replays exactly. The solo clock starts at the first card turned.
 *
 * FLOW: [flipCard] turns a card; the second flip of an attempt leaves both face up
 * ([MatchingState.resolving]) until [resolve] scores them or turns them back. The screen calls
 * [resolve] after a short look ([MatchingRules.RESOLVE_DELAY_MS]).
 *
 * THE COMPUTER remembers per level (see [MatchingBot]): it sees every card turned up, keeps each
 * with a level-dependent chance and loses stored cards over time. [botFlipAsync] chooses its next
 * card on a background thread from a copy of what it remembers and applies it only if the table is
 * unchanged.
 *
 * HINT shows only what the player could know: a pair of cards that have both already been turned
 * up (or the partner of the card now face up); with none, an unseen card to try. UNDO (solo,
 * offline only) takes back the last attempt; a "clean" run, eligible for a personal best, uses
 * neither.
 *
 * HIDDEN INFORMATION: the host holds every picture. A guest's state has faces only for cards
 * face up or matched.
 */
class MatchingGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "matching"
    override val displayName = "Matching"
    override val category = GameCategory.CARD
    override val minPlayers = 1
    override val maxPlayers = MatchingRules.MAX_PLAYERS
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<MatchingState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)
    val hint = mutableStateOf<MatchingHint?>(null)

    /** Hints and undos used on this board (a "clean" run uses none). */
    val hintsUsed = mutableStateOf(0)
    val undosUsed = mutableStateOf(0)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Cards for the NEXT board (12, 20, 30 or 40; anything else is 20). A restore or host sync overrides it. */
    var cardCount: Int = MatchingRules.DEFAULT_COUNT
        set(value) { field = MatchingRules.normalizeCount(value) }

    /** The seat that opens the first board. */
    var firstOpenerIndex: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this // one monitor for @Synchronized entry points and network callbacks alike
    private val history = SnapshotHistory()

    // ---- truth (host / offline) ----
    private var faces = IntArray(0)
    private var status = IntArray(0)
    private var owner = IntArray(0)
    private var seen = BooleanArray(0)
    private val flipped = ArrayList<Int>(2)
    private var memory: List<MutableMap<Int, Int>> = emptyList()
    private var current = 0
    private var scores = IntArray(0)
    private var moves = IntArray(0)
    private var resolving = false
    private var event = MatchingEvent.START
    private var eventCards: List<Int> = emptyList()
    private var eventSeat: Int? = null
    private var flipSerial = 0
    private var resolveSerial = 0
    private var dealSerial = 0
    private var boardOver = false
    private var winnerPlayerId: String? = null
    private var tie = false
    private var nextStarter = 0
    private var currentCount = MatchingRules.DEFAULT_COUNT

    // solo timer
    private var accumMs = 0L
    private var runningSince: Long? = null

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    /** True for one human alone: the mode with moves, time, undo and personal bests. */
    val isSolo: Boolean get() = this::context.isInitialized && context.players.size == 1

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            botSeats.clear()
            matchOver.value = false
            opponentLeft.value = false
            sessionWins.value = context.players.associate { it.playerId to 0 }
            sessionDraws.value = 0
            hint.value = null
            history.clear()
            state.value = null
            boardOver = false
            nextStarter = if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
            stateVersion = com.gamesuite.core.SyncEpoch.base()
            lastAppliedRemoteVersion = 0
            if (isNetworked) registerNetworkListeners()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        history.clear()
        dealBoard()
        commit()
    }

    private fun dealBoard() {
        val n = context.players.size
        currentCount = cardCount
        val dealt = MatchingRules.deal(currentCount, random)
        faces = dealt.toIntArray()
        status = IntArray(currentCount)
        owner = IntArray(currentCount) { -1 }
        seen = BooleanArray(currentCount)
        flipped.clear()
        memory = List(n) { mutableMapOf() }
        scores = IntArray(n)
        moves = IntArray(n)
        resolving = false
        boardOver = false
        winnerPlayerId = null
        tie = false
        current = nextStarter % n
        nextStarter = (nextStarter + 1) % n
        accumMs = 0L
        runningSince = null
        hintsUsed.value = 0
        undosUsed.value = 0
        hint.value = null
        dealSerial++
        event = MatchingEvent.START
        eventCards = emptyList()
        eventSeat = null
    }

    override fun pause() {
        synchronized(lock) {
            runningSince?.let { accumMs += clock() - it }
            runningSince = null
        }
    }

    override fun resume() {
        synchronized(lock) {
            if (isSolo && accumMs > 0L && !boardOver && state.value != null && runningSince == null) runningSince = clock()
        }
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session, reporting boards won (only a strict leader wins; a solo player wins by finishing). */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = best > 0 && w == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(MatchingNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    /** Milliseconds the solo clock has run (stops when the board is done). */
    fun elapsedMs(): Long = synchronized(lock) { accumMs + (runningSince?.let { clock() - it } ?: 0L) }

    /** True when the finished board used no hint and no undo (eligible for a personal best). */
    val isCleanRun: Boolean get() = hintsUsed.value == 0 && undosUsed.value == 0

    // ---- flipping ------------------------------------------------------------------------

    /**
     * A local player turns card [index]. Offline the player on turn must be a person (the screen
     * only offers taps then); networked only this device's own seat may flip, and a guest sends a
     * request and waits for the host's state. Returns [MatchingReject.NONE] when it happened/was sent.
     */
    @Synchronized
    fun flipCard(index: Int): MatchingReject {
        val s = state.value ?: return MatchingReject.NOT_PLAYING
        if (matchOver.value || s.boardOver) return MatchingReject.NOT_PLAYING
        if (index !in 0 until s.cardCount) return MatchingReject.OUT_OF_RANGE
        if (s.resolving) return MatchingReject.RESOLVING
        if (s.status[index] != MatchingStatus.DOWN) return MatchingReject.NOT_FACE_DOWN
        if (isNetworked) {
            if (opponentLeft.value || s.currentPlayerIndex != context.localPlayerIndex) return MatchingReject.NOT_YOUR_TURN
            if (!isHost) {
                sendToHost(MatchingNetMessage.FlipRequest(index))
                return MatchingReject.NONE
            }
        } else if (s.players[s.currentPlayerIndex].isBot) {
            return MatchingReject.NOT_YOUR_TURN
        }
        return applyFlip(index)
    }

    private fun applyFlip(index: Int): MatchingReject {
        if (boardOver) return MatchingReject.NOT_PLAYING
        if (index !in status.indices) return MatchingReject.OUT_OF_RANGE
        if (resolving) return MatchingReject.RESOLVING
        if (status[index] != MatchingStatus.DOWN) return MatchingReject.NOT_FACE_DOWN
        // Undo point: before the first card of a human's attempt (solo, offline).
        if (flipped.isEmpty() && isSolo && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hint.value = null
        status[index] = MatchingStatus.UP
        seen[index] = true
        flipped += index
        flipSerial++
        event = MatchingEvent.FLIP
        eventCards = listOf(index)
        eventSeat = current
        if (isSolo && runningSince == null) runningSince = clock()
        observe(index)
        if (flipped.size == 2) {
            resolving = true
            moves[current]++
        }
        commit()
        return MatchingReject.NONE
    }

    /** Every computer seat sees the card and keeps it with its own chance. */
    private fun observe(index: Int) {
        for (seat in context.players.indices) {
            if (!isBotSeat(seat)) continue
            if (random.nextDouble() < MatchingRules.botProfile(difficulty).rememberChance) memory[seat][index] = faces[index]
        }
    }

    /**
     * Scores or turns back the two face-up cards. A match goes to the player on turn, who goes again;
     * a miss passes the turn. A networked guest never resolves (the host does). Returns true if it did.
     */
    @Synchronized
    fun resolve(): Boolean {
        if (isNetworked && !isHost) return false
        if (!resolving || flipped.size != 2 || matchOver.value) return false
        val a = flipped[0]
        val b = flipped[1]
        resolveSerial++
        eventSeat = current
        eventCards = listOf(a, b)
        hint.value = null
        if (faces[a] == faces[b]) {
            status[a] = MatchingStatus.MATCHED
            status[b] = MatchingStatus.MATCHED
            owner[a] = current
            owner[b] = current
            scores[current]++
            memory.forEach { it.remove(a); it.remove(b) }
            event = MatchingEvent.MATCH
            flipped.clear()
            resolving = false
            if (status.all { it == MatchingStatus.MATCHED }) finishBoard()
        } else {
            status[a] = MatchingStatus.DOWN
            status[b] = MatchingStatus.DOWN
            event = MatchingEvent.MISS
            flipped.clear()
            resolving = false
            forgetSome()
            current = (current + 1) % context.players.size
        }
        commit()
        return true
    }

    private fun forgetSome() {
        val p = MatchingRules.botProfile(difficulty)
        if (p.forgetChance <= 0.0) return
        for (seat in memory.indices) {
            val it = memory[seat].keys.toList()
            for (k in it) if (random.nextDouble() < p.forgetChance) memory[seat].remove(k)
        }
    }

    private fun finishBoard() {
        boardOver = true
        runningSince?.let { accumMs += clock() - it }
        runningSince = null
        val n = context.players.size
        if (n == 1) {
            winnerPlayerId = context.players[0].playerId
            tie = false
        } else {
            val best = scores.max()
            val leaders = scores.indices.filter { scores[it] == best }
            tie = leaders.size > 1
            winnerPlayerId = if (tie) null else context.players[leaders[0]].playerId
        }
        event = if (tie) MatchingEvent.TIE else MatchingEvent.WIN
        val id = winnerPlayerId
        if (id != null) sessionWins.value = sessionWins.value + (id to (sessionWins.value[id] ?: 0) + 1)
        else sessionDraws.value += 1
    }

    // ---- computer ------------------------------------------------------------------------

    /** True when the player on turn is a computer and the table is waiting for it to flip. */
    fun botToMove(s: MatchingState? = state.value): Boolean {
        if (s == null || matchOver.value || s.boardOver || s.resolving) return false
        if (isNetworked && !isHost) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    private fun botInput(): MatchingBotInput = MatchingBotInput(
        status = status.copyOf(),
        flipped = flipped.toList(),
        flippedFace = if (flipped.size == 1) faces[flipped[0]] else -1,
        memory = HashMap(memory[current])
    )

    /** The computer flips its next card. The choice is made off the main thread; dropped if the table changed. */
    suspend fun botFlipAsync(): Boolean {
        val s: MatchingState
        val input: MatchingBotInput
        val derived: Random
        synchronized(lock) {
            s = state.value ?: return false
            if (!botToMove(s)) return false
            input = botInput()
            derived = Random(random.nextLong())
        }
        val profile = MatchingRules.botProfile(difficulty)
        val choice = withContext(Dispatchers.Default) { MatchingBot.choose(input, profile, derived) } ?: return false
        return synchronized(lock) {
            if (state.value !== s || matchOver.value) false else applyFlip(choice) == MatchingReject.NONE
        }
    }

    /** One whole computer attempt (two flips and the resolve), synchronously: for tests and tools. */
    @Synchronized
    fun playBotAttempt(): Boolean {
        val s = state.value ?: return false
        if (!botToMove(s)) return false
        repeat(2) {
            val choice = MatchingBot.choose(botInput(), MatchingRules.botProfile(difficulty), random) ?: return false
            if (applyFlip(choice) != MatchingReject.NONE) return false
        }
        return resolve()
    }

    // ---- hint ----------------------------------------------------------------------------

    /** Suggests a move from what has been seen (see the class doc). Offline only; counts as a hint used. */
    @Synchronized
    fun requestHint(): MatchingHint? {
        val s = state.value ?: return null
        if (isNetworked || matchOver.value || s.boardOver || s.resolving) return null
        if (s.players[s.currentPlayerIndex].isBot) return null
        val down = status.indices.filter { status[it] == MatchingStatus.DOWN }
        val knownDown = down.filter { seen[it] }
        val result: MatchingHint? = if (flipped.size == 1) {
            val partner = knownDown.firstOrNull { faces[it] == faces[flipped[0]] }
            if (partner != null) MatchingHint(listOf(partner), true)
            else down.filter { !seen[it] }.takeIf { it.isNotEmpty() }?.let { MatchingHint(listOf(it[random.nextInt(it.size)]), false) }
        } else {
            val pair = knownDown.groupBy { faces[it] }.values.firstOrNull { it.size >= 2 }
            if (pair != null) MatchingHint(pair.take(2), true)
            else down.filter { !seen[it] }.takeIf { it.isNotEmpty() }?.let { MatchingHint(listOf(it[random.nextInt(it.size)]), false) }
        }
        if (result != null) {
            hintsUsed.value += 1
            hint.value = result
        }
        return result
    }

    // ---- undo ----------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked && isSolo && state.value?.boardOver != true

    @Synchronized
    override fun undo() {
        if (!canUndo) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        val hints = hintsUsed.value
        val undos = undosUsed.value + 1
        applySnapshot(snap, restoreDifficulty = false)
        hintsUsed.value = hints
        undosUsed.value = undos
        commit()
    }

    // ---- state ---------------------------------------------------------------------------

    private fun buildState(): MatchingState = MatchingState(
        players = context.players.mapIndexed { i, it -> MatchingPlayerState(it.playerId, it.displayName, isBotSeat(i)) },
        cardCount = currentCount,
        faces = faces.toList(),
        status = status.toList(),
        owner = owner.toList(),
        currentPlayerIndex = current,
        scores = scores.toList(),
        moves = moves.toList(),
        resolving = resolving,
        event = event, eventCards = eventCards, eventSeat = eventSeat,
        flipSerial = flipSerial, resolveSerial = resolveSerial, dealSerial = dealSerial,
        boardOver = boardOver, winnerPlayerId = winnerPlayerId, tie = tie
    )

    private fun commit() {
        state.value = buildState()
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    /** Picture numbers still hidden and card totals: for conservation checks in tests. */
    internal fun truthFaces(): List<Int> = synchronized(lock) { faces.toList() }

    // ---- resume --------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) { captureSnapshot() }

    private fun captureSnapshot(): String? {
        return try {
            if (matchOver.value || state.value == null || boardOver) return null
            if (isNetworked) return null
            matchingJson.encodeToString(
                MatchingSnapshot.serializer(),
                MatchingSnapshot(
                    players = context.players.map { MatchingSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                    cardCount = currentCount, faces = faces.toList(), status = status.toList(), owner = owner.toList(),
                    seen = seen.toList(), current = current, scores = scores.toList(), moves = moves.toList(),
                    memory = memory.map { it.toMap() },
                    hintsUsed = hintsUsed.value, undosUsed = undosUsed.value, elapsedMs = accumMs + (runningSince?.let { clock() - it } ?: 0L),
                    boardOver = boardOver, winnerPlayerId = winnerPlayerId, tie = tie,
                    sessionWins = sessionWins.value, sessionDraws = sessionDraws.value,
                    nextStarter = nextStarter, difficulty = difficulty.name,
                    flipSerial = flipSerial, resolveSerial = resolveSerial, dealSerial = dealSerial
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshot(text: String): MatchingSnapshot? {
        return try {
            val snap = matchingJson.decodeFromString(MatchingSnapshot.serializer(), text)
            val roster = context.players
            val n = roster.size
            val c = snap.cardCount
            val up = snap.status.indices.filter { snap.status[it] == MatchingStatus.UP }
            val valid = c in MatchingRules.CARD_COUNTS &&
                snap.players.size == n &&
                snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot } &&
                snap.faces.size == c && snap.status.size == c && snap.owner.size == c && snap.seen.size == c &&
                snap.faces.all { it in 0 until MatchingRules.FACE_POOL } &&
                snap.faces.groupBy { it }.values.all { it.size == 2 } &&
                snap.status.all { it in 0..2 } &&
                (0 until c).all { (snap.status[it] == MatchingStatus.MATCHED) == (snap.owner[it] in 0 until n) } &&
                snap.owner.all { it == -1 || it in 0 until n } &&
                snap.current in 0 until n && snap.nextStarter in 0 until n &&
                snap.scores.size == n && snap.moves.size == n && snap.memory.size == n &&
                snap.scores.indices.all { seat -> snap.scores[seat] * 2 == snap.owner.count { it == seat } } &&
                snap.moves.all { it >= 0 } && up.size <= 2 &&
                up.all { snap.seen[it] } &&
                snap.memory.all { m -> m.all { (i, f) -> i in 0 until c && snap.faces[i] == f } } &&
                snap.hintsUsed >= 0 && snap.undosUsed >= 0 && snap.elapsedMs >= 0 && snap.sessionDraws >= 0 &&
                snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
                !snap.boardOver && snap.status.any { it != MatchingStatus.MATCHED }
            if (valid) snap else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: MatchingSnapshot, restoreDifficulty: Boolean) {
        currentCount = snap.cardCount
        cardCount = snap.cardCount
        faces = snap.faces.toIntArray()
        status = snap.status.toIntArray()
        owner = snap.owner.toIntArray()
        seen = snap.seen.toBooleanArray()
        flipped.clear()
        snap.status.indices.filter { snap.status[it] == MatchingStatus.UP }.forEach { flipped += it }
        memory = snap.memory.map { it.toMutableMap() }
        current = snap.current
        scores = snap.scores.toIntArray()
        moves = snap.moves.toIntArray()
        resolving = flipped.size == 2
        boardOver = false
        winnerPlayerId = null
        tie = false
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextStarter = snap.nextStarter
        if (restoreDifficulty) difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintsUsed.value = snap.hintsUsed
        undosUsed.value = snap.undosUsed
        flipSerial = snap.flipSerial; resolveSerial = snap.resolveSerial; dealSerial = maxOf(dealSerial, snap.dealSerial) + 1
        accumMs = snap.elapsedMs
        runningSince = if (isSolo && accumMs > 0L) clock() else null
        event = MatchingEvent.START
        eventCards = emptyList()
        eventSeat = null
        hint.value = null
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(snap, restoreDifficulty = true)
        history.clear()
        state.value = buildState()
        return true
    }

    // ---- networking ----------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(MatchingNetMessage.RequestState)
        transport.onReconnected { synchronized(lock) { if (isHost) resendState() else sendToHost(MatchingNetMessage.RequestState) } }
        transport.onPlayerLeft { id -> synchronized(lock) { handlePlayerLeft(id) } }
        transport.onPlayerJoined { id -> synchronized(lock) { handlePlayerJoined(id) } }
    }

    private fun resendState() {
        if (state.value == null) return
        stateVersion++
        broadcastState()
    }

    /** Seats of guests who left mid-match; the host plays them as computers so the table never freezes. */
    private val botSeats = HashSet<Int>()

    private fun isBotSeat(seat: Int): Boolean = context.players.getOrNull(seat)?.isBot == true || seat in botSeats

    // The host's leaving ends the shared table for a guest. A guest leaving is covered by the host: that seat
    // becomes a computer (the screen's bot loop flips for it), so the other players are never left waiting on it.
    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0 || seat == context.localPlayerIndex) return
        if (seat == 0) { opponentLeft.value = true; return }
        if (!isHost || context.players[seat].isBot) return
        botSeats += seat
        if (state.value != null) commit() // players[seat].isBot is now true in the state, and guests are told
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || context.players.none { it.playerId == playerId }) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (isHost && seat in botSeats) { // the guest came back: hand the seat back to them
            botSeats -= seat
            if (state.value != null) commit()
            return
        }
        if (!opponentLeft.value) return
        opponentLeft.value = false
        if (isHost) resendState()
    }

    private fun toNetState(): MatchingNetState {
        val s = state.value!!
        return MatchingNetState(
            players = s.players.map { MatchingNetPlayer(it.playerId, it.displayName) },
            cardCount = s.cardCount,
            faces = s.faces.mapIndexed { i, f -> if (s.status[i] == MatchingStatus.DOWN) MatchingNetState.HIDDEN else f },
            status = s.status, owner = s.owner, currentPlayerIndex = s.currentPlayerIndex,
            scores = s.scores, moves = s.moves, resolving = s.resolving,
            event = s.event, eventCards = s.eventCards, eventSeat = s.eventSeat,
            flipSerial = s.flipSerial, resolveSerial = s.resolveSerial, dealSerial = s.dealSerial,
            boardOver = s.boardOver, winnerPlayerId = s.winnerPlayerId, tie = s.tie,
            sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
        )
    }

    private fun broadcastState() {
        if (state.value == null) return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = matchingJson.encodeToString(MatchingNetMessage.serializer(), MatchingNetMessage.StateSync(stateVersion, toNetState()))
            .toByteArray(Charsets.UTF_8)
        for (seat in 1 until context.players.size) {
            val p = context.players[seat]
            if (isBotSeat(seat)) continue // a computer seat, or a guest who left and was replaced by one
            context.transport.send(fromPlayerId = hostId, toPlayerId = p.playerId, payload = payload)
        }
    }

    private fun sendToHost(message: MatchingNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = matchingJson.encodeToString(MatchingNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            matchingJson.decodeFromString(MatchingNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        synchronized(lock) {
            val hostId = context.players.getOrNull(0)?.playerId
            val senderSeat = context.players.withIndex().firstOrNull { (i, p) -> p.playerId == fromPlayerId && !isBotSeat(i) }?.index ?: -1
            when (message) {
                is MatchingNetMessage.StateSync ->
                    if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
                is MatchingNetMessage.RequestState ->
                    if (isHost && senderSeat > 0) resendState()
                is MatchingNetMessage.FlipRequest ->
                    // The seat is the transport's, never the payload's: only the player on turn may flip.
                    if (isHost && senderSeat > 0 && state.value != null && !matchOver.value &&
                        state.value!!.currentPlayerIndex == senderSeat
                    ) applyFlip(message.index)
                is MatchingNetMessage.RequestPlayAgain ->
                    if (isHost && senderSeat > 0 && state.value?.boardOver == true && !matchOver.value && !opponentLeft.value) startMatch()
            }
        }
    }

    private fun applyRemoteState(version: Int, net: MatchingNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val n = roster.size
        val c = net.cardCount
        val valid = c in MatchingRules.CARD_COUNTS &&
            net.players.size == n && net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.faces.size == c && net.status.size == c && net.owner.size == c &&
            net.status.all { it in 0..2 } &&
            net.faces.indices.all { i ->
                if (net.status[i] == MatchingStatus.DOWN) net.faces[i] == MatchingNetState.HIDDEN
                else net.faces[i] in 0 until MatchingRules.FACE_POOL
            } &&
            net.owner.all { it == -1 || it in 0 until n } &&
            net.currentPlayerIndex in 0 until n && net.scores.size == n && net.moves.size == n &&
            net.scores.all { it in 0..c / 2 } &&
            (net.winnerPlayerId == null || net.players.any { it.playerId == net.winnerPlayerId }) &&
            net.eventCards.all { it in 0 until c } && (net.eventSeat == null || net.eventSeat in 0 until n)
        if (!valid) return
        lastAppliedRemoteVersion = version
        currentCount = c
        cardCount = c
        state.value = MatchingState(
            players = net.players.mapIndexed { i, p -> MatchingPlayerState(p.playerId, p.displayName, roster[i].isBot) },
            cardCount = c, faces = net.faces, status = net.status, owner = net.owner,
            currentPlayerIndex = net.currentPlayerIndex, scores = net.scores, moves = net.moves, resolving = net.resolving,
            event = net.event, eventCards = net.eventCards, eventSeat = net.eventSeat,
            flipSerial = net.flipSerial, resolveSerial = net.resolveSerial, dealSerial = net.dealSerial,
            boardOver = net.boardOver, winnerPlayerId = net.winnerPlayerId, tie = net.tie
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hint.value = null
    }
}

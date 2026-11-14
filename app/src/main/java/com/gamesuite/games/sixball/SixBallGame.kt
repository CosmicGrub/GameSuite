package com.gamesuite.games.sixball

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.core.UndoableGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a racer stands in a race. */
object SixBallStatus {
    const val WAITING = "waiting"
    const val PLAYING = "playing"
    const val SOLVED = "solved"
    const val GAVE_UP = "gave_up"
    const val LEFT = "left"
    val ALL = setOf(WAITING, PLAYING, SOLVED, GAVE_UP, LEFT)
    fun finished(s: String) = s == SOLVED || s == GAVE_UP || s == LEFT
}

enum class SixBallReject { NONE, SOLVED, NO_MOVE, NOT_PLAYING, HANDOFF, OVER, WAITING_HOST }

/**
 * The tray as the player sees it. [moves] are the tilt codes made so far and [positions] the cell of
 * each ball; the position is ALWAYS the replay of the moves, which is what makes undo unlimited and a
 * save trivially checkable. [previous] and [kind] tell the screen what to animate; [serial] only grows.
 */
data class SixBallBoard(
    val level: SixBallLevel,
    val moves: List<Int>,
    val positions: List<Int>,
    val previous: List<Int>? = null,
    val kind: String = KIND_LOAD,
    val serial: Int = 0
) {
    val solved: Boolean get() = SixBallRules.isSolved(level, positions.toIntArray())
    val moveCount: Int get() = moves.size

    companion object {
        const val KIND_LOAD = "load"
        const val KIND_MOVE = "move"
        const val KIND_UNDO = "undo"
        const val KIND_RESTART = "restart"
    }
}

data class SixBallRacer(
    val playerId: String,
    val displayName: String,
    val status: String,
    val moves: Int = 0,
    /** The host's clock when the solution arrived (a tie-break only; 0 in pass and play). */
    val finishedAt: Long = 0L
)

/** A race of 2 to 4 people over one level. In pass and play they take turns; over a network they run together. */
data class SixBallRace(
    val levelId: String,
    val round: Int,
    val racers: List<SixBallRacer>,
    val currentPlayerIndex: Int,
    val awaitingHandoff: Boolean,
    val raceOver: Boolean,
    val winnerIds: List<String>
)

object SixBallRaceRules {
    /** Fewest tilts wins; in a networked race the host's clock breaks a tie, in pass and play a tie is shared. */
    fun winners(racers: List<SixBallRacer>, byTime: Boolean): List<String> {
        val solved = racers.filter { it.status == SixBallStatus.SOLVED }
        if (solved.isEmpty()) return emptyList()
        val best = solved.minOf { it.moves }
        val tied = solved.filter { it.moves == best }
        return if (byTime) listOf(tied.minByOrNull { it.finishedAt }!!.playerId) else tied.map { it.playerId }
    }

    /** Finishing order for display: solved by tilts then time, then those who gave up, then those who left. */
    fun ranking(racers: List<SixBallRacer>): List<SixBallRacer> =
        racers.withIndex().sortedWith(
            compareBy<IndexedValue<SixBallRacer>>(
                { when (it.value.status) { SixBallStatus.SOLVED -> 0; SixBallStatus.GAVE_UP -> 1; SixBallStatus.LEFT -> 2; else -> 3 } },
                { if (it.value.status == SixBallStatus.SOLVED) it.value.moves else 0 },
                { if (it.value.status == SixBallStatus.SOLVED) it.value.finishedAt else 0L },
                { it.index }
            )
        ).map { it.value }
}

@Serializable
private data class SixBallSnapshot(
    val levelId: String,
    val moves: List<Int>,
    val solvedLevels: List<String> = emptyList(),
    val hintsUsed: Int = 0,
    val playerIds: List<String>,
    val racers: List<SixBallSnapRacer> = emptyList(),
    val currentPlayerIndex: Int = 0,
    val awaitingHandoff: Boolean = false,
    val raceOver: Boolean = false,
    val round: Int = 1,
    val winnerIds: List<String> = emptyList()
)

@Serializable
private data class SixBallSnapRacer(val playerId: String, val status: String, val moves: Int)

private val sixBallJson = Json { ignoreUnknownKeys = true }

/**
 * Six-Ball Puzzle for one player, or a "race the same level" for 2 to 4.
 *
 *  - SOLO: pick a campaign level or take the daily one; tilt the tray; undo is unlimited (the board is
 *    just the replay of the move list, so undo drops the last tilt); a hint is the first tilt of the
 *    shortest solution from where you stand, or a warning that you are in a dead end.
 *  - PASS AND PLAY: the racers take turns on the same device and the fewest tilts wins (a tie is shared).
 *  - NEARBY / ONLINE: everyone races at once on their own device. The host owns the results: a guest
 *    sends its list of tilts and the host replays it, so nobody can claim a solution they do not have.
 *
 * There is nothing random during play, so the only randomness is level generation, which is a pure
 * function of the level id (see [SixBallLevels]). The clock is injected only for the host's tie-break.
 */
class SixBallGame(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }) :
    GameModule, ResumableGame, UndoableGame {

    override val gameId = "six-ball-puzzle"
    override val displayName = "Six-Ball Puzzle"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val board = mutableStateOf<SixBallBoard?>(null)
    val race = mutableStateOf<SixBallRace?>(null)
    val solvedLevels = mutableStateOf<Set<String>>(emptySet())
    val hintMove = mutableStateOf<Tilt?>(null)
    val hintDeadEnd = mutableStateOf(false)
    val hintsUsed = mutableStateOf(0)
    val hostLeft = mutableStateOf(false)
    val matchOver = mutableStateOf(false)

    /** The level the next [startMatch] plays (the Hub's "start level", or the daily). */
    var levelId: String = SixBallLevels.campaignId(1)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0
    private var serial = 0

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    val isRace: Boolean get() = this::context.isInitialized && context.players.size > 1
    val isPassAndPlay: Boolean get() = isRace && !isNetworked
    val localSeat: Int get() = if (this::context.isInitialized) context.localPlayerIndex else 0

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    override fun init(context: GameContext) {
        require(context.players.size in 1..4) { "Six-Ball Puzzle seats 1 to 4" }
        this.context = context
        board.value = null; race.value = null
        solvedLevels.value = emptySet()
        hintMove.value = null; hintDeadEnd.value = false; hintsUsed.value = 0
        hostLeft.value = false; matchOver.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base(); lastAppliedRemoteVersion = 0; serial = 0
        if (isNetworked) registerNetworkListeners()
    }

    // ---- queries -----------------------------------------------------------------------------

    /** Whether the person at THIS device may tilt right now, and if not why. */
    fun explainTilt(dir: Tilt): SixBallReject {
        val b = board.value ?: return SixBallReject.OVER
        if (matchOver.value) return SixBallReject.OVER
        val gate = playGate()
        if (gate != SixBallReject.NONE) return gate
        if (b.solved) return SixBallReject.SOLVED
        val p = b.positions.toIntArray()
        return if (SixBallRules.tilt(b.level, p, dir).contentEquals(p)) SixBallReject.NO_MOVE else SixBallReject.NONE
    }

    private fun playGate(): SixBallReject {
        if (!isRace) return SixBallReject.NONE
        val r = race.value ?: return SixBallReject.WAITING_HOST
        if (r.raceOver) return SixBallReject.OVER
        if (isNetworked) {
            if (hostLeft.value) return SixBallReject.OVER
            return if (r.racers.getOrNull(localSeat)?.status == SixBallStatus.PLAYING) SixBallReject.NONE else SixBallReject.NOT_PLAYING
        }
        if (r.awaitingHandoff) return SixBallReject.HANDOFF
        return if (r.racers.getOrNull(r.currentPlayerIndex)?.status == SixBallStatus.PLAYING) SixBallReject.NONE else SixBallReject.NOT_PLAYING
    }

    /** True while the local player's board accepts tilts. */
    fun canPlay(): Boolean = board.value?.let { !it.solved } == true && !matchOver.value && playGate() == SixBallReject.NONE

    /** Name of the racer whose board is on screen in pass and play (or the local player). */
    fun activeRacerIndex(): Int = if (isPassAndPlay) race.value?.currentPlayerIndex ?: 0 else localSeat

    // ---- starting ----------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) { sendToHost(SixBallNetMessage.RequestState); return }
        val level = SixBallLevels.byId(levelId) ?: SixBallLevels.campaign(1).also { levelId = it.id }
        matchOver.value = false
        hintMove.value = null; hintDeadEnd.value = false; hintsUsed.value = 0
        loadBoard(level)
        if (!isRace) { race.value = null; return }
        val racers = context.players.mapIndexed { i, p ->
            SixBallRacer(
                p.playerId, p.displayName,
                if (isNetworked || i == 0) SixBallStatus.PLAYING else SixBallStatus.WAITING
            )
        }
        val round = (race.value?.round ?: 0) + 1
        commitRace(SixBallRace(level.id, round, racers, 0, false, false, emptyList()))
    }

    private fun loadBoard(level: SixBallLevel) {
        board.value = SixBallBoard(level, emptyList(), level.start, null, SixBallBoard.KIND_LOAD, ++serial)
        hintMove.value = null; hintDeadEnd.value = false
    }

    /** Solo: jump to another level (campaign or daily). Ignored in a race. */
    fun startLevel(id: String): Boolean {
        if (isRace || matchOver.value) return false
        val level = SixBallLevels.byId(id) ?: return false
        levelId = id
        hintsUsed.value = 0
        loadBoard(level)
        return true
    }

    /** The id of the campaign level after the current one, or null (the last level, or a daily). */
    fun nextLevelId(): String? {
        val n = SixBallLevels.campaignNumber(board.value?.level?.id ?: levelId) ?: return null
        return if (n < SixBallLevels.CAMPAIGN_COUNT) SixBallLevels.campaignId(n + 1) else null
    }

    // ---- tilting -----------------------------------------------------------------------------

    /** The local player's tilt. True when it moved the balls. */
    fun tilt(dir: Tilt): Boolean {
        if (explainTilt(dir) != SixBallReject.NONE) return false
        val b = board.value ?: return false
        val next = SixBallRules.tilt(b.level, b.positions.toIntArray(), dir)
        val moves = b.moves + dir.ordinal
        board.value = SixBallBoard(b.level, moves, next.toList(), b.positions, SixBallBoard.KIND_MOVE, ++serial)
        hintMove.value = null; hintDeadEnd.value = false
        if (SixBallRules.isSolved(b.level, next)) onLocalSolved(b.level, moves)
        return true
    }

    private fun onLocalSolved(level: SixBallLevel, moves: List<Int>) {
        if (!isRace) { solvedLevels.value = solvedLevels.value + level.id; return }
        if (isNetworked) {
            if (isHost) recordFinish(0, moves) else sendToHost(SixBallNetMessage.Finish(moves))
        } else {
            finishPassAndPlay(SixBallStatus.SOLVED, moves.size)
        }
    }

    override val canUndo: Boolean
        get() = board.value?.let { it.moves.isNotEmpty() && !it.solved } == true && !matchOver.value && playGate() == SixBallReject.NONE

    override fun undo() {
        if (!canUndo) return
        val b = board.value ?: return
        val moves = b.moves.dropLast(1)
        val p = SixBallRules.replay(b.level, moves) ?: return
        board.value = SixBallBoard(b.level, moves, p.toList(), b.positions, SixBallBoard.KIND_UNDO, ++serial)
        hintMove.value = null; hintDeadEnd.value = false
    }

    /** Back to the start of the level (solo may also replay a solved one). */
    fun restartLevel(): Boolean {
        val b = board.value ?: return false
        if (matchOver.value || b.moves.isEmpty()) return false
        if (isRace) { if (playGate() != SixBallReject.NONE || b.solved) return false }
        board.value = SixBallBoard(b.level, emptyList(), b.level.start, b.positions, SixBallBoard.KIND_RESTART, ++serial)
        hintMove.value = null; hintDeadEnd.value = false
        return true
    }

    // ---- hints -------------------------------------------------------------------------------

    private fun hintAllowed(): Boolean = !isRace && !matchOver.value && board.value?.solved == false

    /** The first tilt of a shortest solution from here, or a dead-end warning; computed off the main thread. */
    suspend fun requestHint(): Tilt? {
        if (!hintAllowed()) return null
        val b = board.value ?: return null
        val result = withContext(Dispatchers.Default) {
            val from = b.positions.toIntArray()
            val ex = SixBallRules.explore(b.level, from, SixBallRules.pack(b.level.target.toIntArray()), maxNodes = 1_500_000)
            if (ex.found >= 0) ex.path(ex.found).firstOrNull() to false else null to ex.complete
        }
        if (board.value !== b || matchOver.value) return null
        hintsUsed.value = hintsUsed.value + 1
        hintMove.value = result.first
        hintDeadEnd.value = result.first == null && result.second
        return result.first
    }

    /** Stars for the current board if it is solved now (a hint caps it at two). */
    fun starsNow(): Int {
        val b = board.value ?: return 0
        if (!b.solved) return 0
        val s = SixBallRules.stars(b.moveCount, b.level.par)
        return if (hintsUsed.value > 0) minOf(s, 2) else s
    }

    // ---- race: pass and play -----------------------------------------------------------------

    fun giveUp(): Boolean {
        val r = race.value
        if (!isRace || r == null || matchOver.value || r.raceOver) return false
        if (playGate() != SixBallReject.NONE) return false
        if (isNetworked) {
            if (isHost) recordGiveUp(0) else sendToHost(SixBallNetMessage.GiveUp)
        } else {
            finishPassAndPlay(SixBallStatus.GAVE_UP, 0)
        }
        return true
    }

    private fun finishPassAndPlay(status: String, moves: Int) {
        val r = race.value ?: return
        val racers = r.racers.mapIndexed { i, x -> if (i == r.currentPlayerIndex) x.copy(status = status, moves = moves) else x }
        val over = racers.none { it.status == SixBallStatus.WAITING || it.status == SixBallStatus.PLAYING }
        commitRace(
            r.copy(
                racers = racers, awaitingHandoff = !over, raceOver = over,
                winnerIds = if (over) SixBallRaceRules.winners(racers, byTime = false) else emptyList()
            )
        )
    }

    /** Pass and play: hand the device to the next racer, who starts from the beginning of the level. */
    fun beginNextRacer(): Boolean {
        val r = race.value ?: return false
        if (!isPassAndPlay || !r.awaitingHandoff || r.raceOver || matchOver.value) return false
        val next = r.racers.indices.firstOrNull { it > r.currentPlayerIndex && r.racers[it].status == SixBallStatus.WAITING }
            ?: r.racers.indexOfFirst { it.status == SixBallStatus.WAITING }
        if (next < 0) return false
        val level = SixBallLevels.byId(r.levelId) ?: return false
        loadBoard(level)
        commitRace(
            r.copy(
                currentPlayerIndex = next, awaitingHandoff = false,
                racers = r.racers.mapIndexed { i, x -> if (i == next) x.copy(status = SixBallStatus.PLAYING) else x }
            )
        )
        return true
    }

    // ---- race: host side ---------------------------------------------------------------------

    private fun recordFinish(seat: Int, moves: List<Int>): Boolean {
        val r = race.value ?: return false
        val racer = r.racers.getOrNull(seat) ?: return false
        if (r.raceOver || racer.status != SixBallStatus.PLAYING) return false
        if (moves.size > MAX_MOVES) return false
        val level = SixBallLevels.byId(r.levelId) ?: return false
        val end = SixBallRules.replay(level, moves) ?: return false
        if (!SixBallRules.isSolved(level, end)) return false
        updateRacer(seat) { it.copy(status = SixBallStatus.SOLVED, moves = moves.size, finishedAt = nowMillis()) }
        return true
    }

    private fun recordGiveUp(seat: Int) {
        val r = race.value ?: return
        if (r.raceOver || r.racers.getOrNull(seat)?.status != SixBallStatus.PLAYING) return
        updateRacer(seat) { it.copy(status = SixBallStatus.GAVE_UP) }
    }

    private fun updateRacer(seat: Int, change: (SixBallRacer) -> SixBallRacer) {
        val r = race.value ?: return
        val racers = r.racers.mapIndexed { i, x -> if (i == seat) change(x) else x }
        val over = racers.all { SixBallStatus.finished(it.status) }
        commitRace(
            r.copy(
                racers = racers, raceOver = over,
                winnerIds = if (over) SixBallRaceRules.winners(racers, byTime = true) else emptyList()
            )
        )
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) { if (seat == 0) hostLeft.value = true; return }
        if (race.value?.racers?.getOrNull(seat)?.status == SixBallStatus.PLAYING) updateRacer(seat) { it.copy(status = SixBallStatus.LEFT) }
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || isHost) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(SixBallNetMessage.RequestState) }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session: solo scores the levels solved this session, a race its finished results; unfinished play is an abort. */
    fun leaveSession() {
        if (matchOver.value) return
        val r = race.value
        if (!isRace) {
            val n = solvedLevels.value.size
            if (n == 0) { abortMatch(); return }
            val me = context.players[0]
            endMatch(GameResult(scores = listOf(PlayerScore(me.playerId, n, isWinner = true))))
            return
        }
        if (r == null || !r.raceOver) { abortMatch(); return }
        endMatch(
            GameResult(
                scores = r.racers.map { PlayerScore(it.playerId, if (it.status == SixBallStatus.SOLVED) it.moves else 0, it.playerId in r.winnerIds) }
            )
        )
    }

    /** Next level: solo moves on; a race (host only over a network) starts the following campaign level. */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (!isRace) {
            nextLevelId()?.let { startLevel(it) } ?: restartLevel()
            return
        }
        val r = race.value ?: return
        if (!r.raceOver) return
        if (isNetworked) {
            if (hostLeft.value) return
            if (!isHost) { sendToHost(SixBallNetMessage.RequestPlayAgain); return }
        }
        SixBallLevels.campaignNumber(r.levelId)?.let { n ->
            levelId = SixBallLevels.campaignId(if (n < SixBallLevels.CAMPAIGN_COUNT) n + 1 else 1)
        } ?: run { levelId = r.levelId }
        startMatch()
    }

    // ---- resume ------------------------------------------------------------------------------

    override fun snapshot(): String? = try {
        val b = board.value
        if (b == null || matchOver.value) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) null
        else {
            val r = race.value
            sixBallJson.encodeToString(
                SixBallSnapshot.serializer(),
                SixBallSnapshot(
                    levelId = b.level.id, moves = b.moves, solvedLevels = solvedLevels.value.toList().sorted(),
                    hintsUsed = hintsUsed.value, playerIds = context.players.map { it.playerId },
                    racers = r?.racers?.map { SixBallSnapRacer(it.playerId, it.status, it.moves) } ?: emptyList(),
                    currentPlayerIndex = r?.currentPlayerIndex ?: 0, awaitingHandoff = r?.awaitingHandoff ?: false,
                    raceOver = r?.raceOver ?: false, round = r?.round ?: 1, winnerIds = r?.winnerIds ?: emptyList()
                )
            )
        }
    } catch (_: Exception) {
        null
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val s = sixBallJson.decodeFromString(SixBallSnapshot.serializer(), snapshot)
            val roster = context.players
            if (s.playerIds != roster.map { it.playerId }) return false
            if (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE) return false
            val level = SixBallLevels.byId(s.levelId) ?: return false
            if (!level.isWellFormed()) return false
            if (s.moves.size > MAX_MOVES) return false
            val positions = SixBallRules.replay(level, s.moves) ?: return false
            if (s.hintsUsed < 0 || s.solvedLevels.any { SixBallLevels.byId(it) == null }) return false
            var restoredRace: SixBallRace? = null
            if (roster.size > 1) {
                if (s.racers.size != roster.size) return false
                if (s.racers.indices.any { s.racers[it].playerId != roster[it].playerId || s.racers[it].status !in SixBallStatus.ALL || s.racers[it].moves !in 0..MAX_MOVES }) return false
                if (s.currentPlayerIndex !in roster.indices || s.round < 1) return false
                val racers = s.racers.mapIndexed { i, x -> SixBallRacer(x.playerId, roster[i].displayName, x.status, x.moves) }
                val over = racers.none { it.status == SixBallStatus.WAITING || it.status == SixBallStatus.PLAYING }
                if (s.raceOver != over) return false
                if (!over && !s.awaitingHandoff && racers[s.currentPlayerIndex].status != SixBallStatus.PLAYING) return false
                if (s.winnerIds != (if (over) SixBallRaceRules.winners(racers, byTime = false) else emptyList())) return false
                // a finished tray belongs to a racer who has already been marked solved
                if (SixBallRules.isSolved(level, positions) && !(s.awaitingHandoff || over)) return false
                restoredRace = SixBallRace(level.id, s.round, racers, s.currentPlayerIndex, s.awaitingHandoff, over, s.winnerIds)
            } else if (s.racers.isNotEmpty()) return false
            matchOver.value = false
            levelId = level.id
            solvedLevels.value = s.solvedLevels.toSet()
            hintsUsed.value = s.hintsUsed
            board.value = SixBallBoard(level, s.moves, positions.toList(), null, SixBallBoard.KIND_LOAD, ++serial)
            race.value = restoredRace
            hintMove.value = null; hintDeadEnd.value = false
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---- networking --------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(SixBallNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(SixBallNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun commitRace(r: SixBallRace) {
        race.value = r
        if (isNetworked && isHost) { stateVersion++; broadcastState() }
    }

    private fun toNet(r: SixBallRace) = SixBallNetRace(
        r.levelId, r.round, r.racers.map { SixBallNetRacer(it.playerId, it.status, it.moves) }, r.raceOver, r.winnerIds
    )

    private fun syncPayload(): ByteArray? {
        val r = race.value ?: return null
        return Json.encodeToString(SixBallNetMessage.serializer(), SixBallNetMessage.StateSync(stateVersion, toNet(r))).toByteArray(Charsets.UTF_8)
    }

    private fun broadcastState() {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.players.drop(1).filter { !it.isBot }.forEach { context.transport.send(hostId, it.playerId, payload) }
    }

    private fun sendStateTo(playerId: String) {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.transport.send(hostId, playerId, payload)
    }

    private fun sendToHost(message: SixBallNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(SixBallNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(localId, hostId, payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD) return
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(SixBallNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        when (message) {
            is SixBallNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemote(message.version, message.race)
            is SixBallNetMessage.RequestState -> if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is SixBallNetMessage.Finish -> if (isHost && senderSeat > 0) recordFinish(senderSeat, message.moves)
            is SixBallNetMessage.GiveUp -> if (isHost && senderSeat > 0) recordGiveUp(senderSeat)
            is SixBallNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && race.value?.raceOver == true && !matchOver.value) playAgain()
        }
    }

    private fun applyRemote(version: Int, net: SixBallNetRace) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val level = SixBallLevels.byId(net.levelId) ?: return
        val valid = level.isWellFormed() &&
            net.racers.size == roster.size &&
            net.racers.indices.all {
                val x = net.racers[it]
                x.playerId == roster[it].playerId && x.status in SixBallStatus.ALL && x.status != SixBallStatus.WAITING &&
                    x.moves in 0..MAX_MOVES &&
                    // nobody can have solved the tray in fewer tilts than the true optimum
                    (x.status != SixBallStatus.SOLVED || x.moves >= level.par) &&
                    (x.status == SixBallStatus.SOLVED || x.moves == 0)
            } &&
            net.round >= 1 &&
            net.raceOver == net.racers.all { SixBallStatus.finished(it.status) } &&
            net.winnerIds.all { id -> net.racers.any { it.playerId == id && it.status == SixBallStatus.SOLVED } } &&
            (net.raceOver || net.winnerIds.isEmpty())
        if (!valid) return
        lastAppliedRemoteVersion = version
        val previous = race.value
        val racers = net.racers.mapIndexed { i, x -> SixBallRacer(x.playerId, roster[i].displayName, x.status, x.moves) }
        race.value = SixBallRace(net.levelId, net.round, racers, 0, false, net.raceOver, net.winnerIds)
        if (previous == null || previous.round != net.round || previous.levelId != net.levelId || board.value == null) {
            levelId = net.levelId
            matchOver.value = false
            loadBoard(level)
        }
    }

    companion object {
        /** No honest solution or save is longer than this; it bounds what a peer or a file can make us replay. */
        const val MAX_MOVES = 1000
        private const val MAX_PAYLOAD = 64 * 1024
    }
}

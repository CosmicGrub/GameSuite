package com.gamesuite.games.curling

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class CurlingSnapshot(val net: CurlingNetState, val nextStarterTeam: Int, val difficulty: String)

private val curlingJson = Json { ignoreUnknownKeys = true }

/**
 * Toy curling for two teams (two seats, or four with two throwers a side). Each end every team slides 4 or 8 stones
 * toward the house; the team without the hammer throws first and the hammer team throws last. After the last stone the
 * team with the stone nearest the button scores a point for each of its stones nearer than the other team's best. The
 * team that did not score gets the hammer next end. The match is 4 to 6 ends, with up to three extra ends if tied.
 *
 * Choose the line (aim), the weight and which way the handle turns, then sweep while the stone slides: sweeping
 * carries it further and keeps it straighter. The physics is deterministic ([CurlingPhysics]); the injected [random] is
 * only the computer's wobble and the opening toss, and [clock] stamps each delivery. A networked host re-simulates every delivery.
 */
class CurlingGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "curling"
    override val displayName = "Curling"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE
    )

    val state = mutableStateOf<CurlingState?>(null)

    var totalEnds: Int = 4
    var stonesPerTeam: Int = 4
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** The roster seat that throws the first stone of the first end (its team will not hold the hammer). */
    var firstOpenerIndex: Int = 0

    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)
    val hintThrow = mutableStateOf<CurlingThrow?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextOpener = 0
    private val history = SnapshotHistory()
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private var cachedReplay: Pair<Int, CurlingSimResult>? = null

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    override fun init(context: GameContext) {
        require(CurlingRules.validSeatCount(context.players.size)) { "Curling needs 2 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextOpener = if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        history.clear()
        hintThrow.value = null
        hostLeft.value = false
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        cachedReplay = null
        if (isNetworked) registerNetworkListeners()
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: CurlingState): Boolean {
        if (matchOver.value || s.phase != CurlingPhase.THROWING) return false
        val seat = s.seatToThrow
        if (s.players[seat].isBot) return false
        return if (isNetworked) !hostLeft.value && seat == context.localPlayerIndex else true
    }

    /** True when this device may press "Next end" (an end has just been scored). */
    fun canAdvance(): Boolean {
        val s = state.value ?: return false
        return !matchOver.value && s.phase == CurlingPhase.END_SCORED && !hostLeft.value
    }

    fun explainThrow(t: CurlingThrow): CurlingReject {
        val s = state.value ?: return CurlingReject.NOT_THROWING
        if (matchOver.value || s.phase != CurlingPhase.THROWING) return CurlingReject.NOT_THROWING
        if (!isMyTurn(s)) return CurlingReject.NOT_YOUR_TURN
        return CurlingRules.explain(s, s.seatToThrow, t)
    }

    fun replayOf(s: CurlingState): CurlingSimResult? {
        val rec = s.last ?: return null
        cachedReplay?.let { if (it.first == s.throwSerial) return it.second }
        val sim = CurlingPhysics.simulate(rec.before, rec.team, rec.stoneId, rec.throwing, record = true)
        cachedReplay = s.throwSerial to sim
        return sim
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun ns(list: List<CurlingStone>) = list.map { CurlingNetStone(it.id, it.team, it.x, it.y) }
    private fun fromNs(list: List<CurlingNetStone>) = list.map { CurlingStone(it.id, it.team, it.x, it.y) }

    private fun toNet(s: CurlingState) = CurlingNetState(
        players = s.players.map { CurlingNetPlayer(it.playerId, it.displayName, it.isBot) }, stones = ns(s.stones),
        end = s.end, totalEnds = s.totalEnds, stonesPerTeam = s.stonesPerTeam, thrown = s.thrown, hammer = s.hammer,
        scores = s.scores, endScores = s.endScores, phase = s.phase.name, extraEnds = s.extraEnds, flags = s.flags,
        lastSeat = s.last?.seat, lastTeam = s.last?.team, lastStoneId = s.last?.stoneId, lastThrow = s.last?.throwing,
        before = s.last?.before?.let { ns(it) } ?: emptyList(), throwSerial = s.throwSerial, endScorer = s.endScorer,
        endPoints = s.endPoints, winnerTeam = s.winnerTeam, sessionWins = sessionWins.value
    )

    private fun validStones(list: List<CurlingNetStone>, perTeam: Int): Boolean {
        if (list.size > 2 * perTeam) return false
        if (list.map { it.id }.toSet().size != list.size) return false
        return list.all {
            it.team in 0..1 && it.id / 8 == it.team && it.id % 8 < perTeam && it.x.isFinite() && it.y.isFinite() &&
                kotlin.math.abs(it.x) <= CurlingSheet.HALF_WIDTH + 1 && it.y in -1.0..(CurlingSheet.BACK_Y + 40)
        }
    }

    private fun validNet(net: CurlingNetState): Boolean {
        val roster = context.players
        val n = roster.size
        if (net.players.size != n || !net.players.indices.all { net.players[it].playerId == roster[it].playerId }) return false
        if (net.totalEnds !in 1..12 || net.stonesPerTeam !in 1..8 || net.end < 1 || net.end > net.totalEnds + CurlingRules.MAX_EXTRA_ENDS) return false
        if (!validStones(net.stones, net.stonesPerTeam) || !validStones(net.before, net.stonesPerTeam)) return false
        if (net.thrown.size != 2 || net.thrown.any { it !in 0..net.stonesPerTeam }) return false
        for (tm in 0..1) if (net.stones.count { it.team == tm } > net.thrown[tm]) return false
        if (net.hammer !in 0..1 || net.scores.size != 2 || net.scores.any { it < 0 }) return false
        if (net.endScores.size % 2 != 0 || net.endScores.any { it < 0 }) return false
        val phase = CurlingPhase.values().firstOrNull { it.name == net.phase } ?: return false
        val complete = net.thrown.all { it == net.stonesPerTeam }
        if ((phase == CurlingPhase.THROWING) == complete) return false
        if (net.extraEnds !in 0..CurlingRules.MAX_EXTRA_ENDS || net.flags < 0 || net.throwSerial < 0) return false
        if (net.lastThrow != null) {
            if (!CurlingSheet.inRange(net.lastThrow) || net.lastSeat == null || net.lastSeat !in 0 until n ||
                net.lastTeam !in 0..1 || net.lastStoneId == null || net.lastStoneId / 8 != net.lastTeam
            ) return false
        } else if (net.lastSeat != null || net.before.isNotEmpty()) return false
        if (net.endScorer !in -1..1 || net.endPoints < 0 || net.winnerTeam !in -1..1) return false
        if (phase != CurlingPhase.MATCH_OVER && net.winnerTeam != -1) return false
        if (net.sessionWins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) return false
        return true
    }

    private fun fromNet(net: CurlingNetState): CurlingState {
        val players = net.players.mapIndexed { i, p -> CurlingPlayerState(p.playerId, p.displayName, p.isBot, CurlingRules.teamOf(i)) }
        return CurlingState(
            players = players, stones = fromNs(net.stones), end = net.end, totalEnds = net.totalEnds, stonesPerTeam = net.stonesPerTeam,
            thrown = net.thrown, hammer = net.hammer, scores = net.scores, endScores = net.endScores,
            phase = CurlingPhase.valueOf(net.phase), extraEnds = net.extraEnds, flags = net.flags,
            last = if (net.lastThrow != null && net.lastSeat != null && net.lastTeam != null && net.lastStoneId != null)
                CurlingThrowRecord(net.lastSeat, net.lastTeam, net.lastStoneId, net.lastThrow, fromNs(net.before)) else null,
            throwSerial = net.throwSerial, endScorer = net.endScorer, endPoints = net.endPoints, winnerTeam = net.winnerTeam
        )
    }

    private fun capture(): String? {
        val s = state.value ?: return null
        return curlingJson.encodeToString(CurlingSnapshot.serializer(), CurlingSnapshot(toNet(s), nextOpener, difficulty.name))
    }

    private fun decode(text: String): CurlingSnapshot? = try {
        val snap = curlingJson.decodeFromString(CurlingSnapshot.serializer(), text)
        if (validNet(snap.net) && snap.nextStarterTeam in 0 until context.players.size) snap else null
    } catch (_: Exception) {
        null
    }

    private fun apply(snap: CurlingSnapshot, restoreDifficulty: Boolean) {
        state.value = fromNet(snap.net)
        sessionWins.value = snap.net.sessionWins
        nextOpener = snap.nextStarterTeam
        totalEnds = snap.net.totalEnds
        stonesPerTeam = snap.net.stonesPerTeam
        if (restoreDifficulty) difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintThrow.value = null
        cachedReplay = null
    }

    override fun snapshot(): String? = try {
        if (matchOver.value || state.value == null) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) null
        else capture()
    } catch (_: Exception) {
        null
    }

    override fun restore(snapshot: String): Boolean {
        val snap = decode(snapshot) ?: return false
        matchOver.value = false
        apply(snap, restoreDifficulty = true)
        history.clear()
        return true
    }

    // --- Undo / hint -----------------------------------------------------------------------

    private val undoAllowed: Boolean
        get() = this::context.isInitialized && context.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT

    override val canUndo: Boolean get() = undoAllowed && history.depth > 0 && !matchOver.value

    override fun undo() {
        if (!undoAllowed || matchOver.value) return
        val previous = history.pop() ?: return
        val snap = decode(previous) ?: return
        apply(snap, restoreDifficulty = false)
    }

    private fun hintAllowed(s: CurlingState) =
        !isNetworked && !matchOver.value && s.phase == CurlingPhase.THROWING && !s.players[s.seatToThrow].isBot

    suspend fun requestHint(): CurlingThrow? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = withContext(Dispatchers.Default) { CurlingBot.search(s, Random(s.throwSerial.toLong() * 17 + 3))?.first }
        if (state.value !== s || matchOver.value) return null
        hintThrow.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        history.clear()
        hintThrow.value = null
        cachedReplay = null
        val opener = nextOpener % context.players.size
        val players = context.players.mapIndexed { i, p -> CurlingPlayerState(p.playerId, p.displayName, p.isBot, CurlingRules.teamOf(i)) }
        commit(CurlingRules.newMatch(players, totalEnds, stonesPerTeam, CurlingRules.teamOf(opener), clock()))
        nextOpener = (nextOpener + 1) % context.players.size
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val teamWins = IntArray(2)
        context.players.forEachIndexed { i, p -> teamWins[i % 2] += wins[p.playerId] ?: 0 }
        val best = teamWins.max()
        val leaders = teamWins.count { it == best }
        val scores = context.players.mapIndexed { i, p ->
            PlayerScore(p.playerId, wins[p.playerId] ?: 0, isWinner = best > 0 && teamWins[i % 2] == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    fun playAgain() {
        if (matchOver.value) return
        if (isNetworked) {
            if (hostLeft.value || state.value?.phase != CurlingPhase.MATCH_OVER) return
            if (isHost) startMatch() else sendToHost(CurlingNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    /** Moves on from a scored end. */
    fun nextEnd() {
        val s = state.value ?: return
        if (matchOver.value || s.phase != CurlingPhase.END_SCORED) return
        if (isNetworked && !isHost) { sendToHost(CurlingNetMessage.RequestNextEnd); return }
        hintThrow.value = null
        commit(CurlingRules.nextEnd(s))
    }

    // --- Delivering ------------------------------------------------------------------------

    /** The local player's delivery. True when accepted (played here, or asked of the host). */
    fun throwStone(t: CurlingThrow): Boolean {
        if (explainThrow(t) != CurlingReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(CurlingNetMessage.ThrowRequest(t))
            return true
        }
        return doThrow(t)
    }

    private fun doThrow(t: CurlingThrow): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || CurlingRules.explain(s, s.seatToThrow, t) != CurlingReject.NONE) return false
        val thrower = s.players[s.seatToThrow]
        if (!thrower.isBot && !isNetworked && undoAllowed) capture()?.let { history.push(it) }
        hintThrow.value = null
        val out = CurlingRules.play(s, t, clock(), record = true)
        cachedReplay = out.state.throwSerial to out.sim
        if (out.state.phase == CurlingPhase.MATCH_OVER && out.state.winnerTeam != -1) {
            val winners = context.players.filterIndexed { i, _ -> i % 2 == out.state.winnerTeam }
            sessionWins.value = sessionWins.value + winners.associate { it.playerId to (sessionWins.value[it.playerId] ?: 0) + 1 }
        }
        commit(out.state)
        return true
    }

    // --- Computer --------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: CurlingState): Boolean {
        if (matchOver.value || s.phase != CurlingPhase.THROWING) return false
        if (isNetworked && !isHost) return false
        return s.players[s.seatToThrow].isBot
    }

    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val t = withContext(Dispatchers.Default) { CurlingBot.choose(s, difficulty, random) }
        if (state.value !== s || matchOver.value) return
        doThrow(t)
    }

    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        doThrow(CurlingBot.choose(s, difficulty, random))
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(CurlingNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(CurlingNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) { if (seat == 0) hostLeft.value = true; return }
        val s = state.value ?: return
        if (seat == 0 || s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = true) else p }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(CurlingNetMessage.RequestState) }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    private fun commit(newState: CurlingState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(CurlingNetMessage.serializer(), CurlingNetMessage.StateSync(stateVersion, toNet(s))).toByteArray(Charsets.UTF_8)
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

    private fun sendToHost(message: CurlingNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(CurlingNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(localId, hostId, payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString(CurlingNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is CurlingNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemote(message.version, message.state)
            is CurlingNetMessage.RequestState -> if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is CurlingNetMessage.ThrowRequest ->
                if (isHost && s != null && senderSeat > 0 && s.seatToThrow == senderSeat && !s.players[senderSeat].isBot) doThrow(message.throwing)
            is CurlingNetMessage.RequestNextEnd ->
                if (isHost && senderSeat > 0 && s?.phase == CurlingPhase.END_SCORED && !matchOver.value) commit(CurlingRules.nextEnd(s))
            is CurlingNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && s?.phase == CurlingPhase.MATCH_OVER && !matchOver.value) startMatch()
        }
    }

    private fun applyRemote(version: Int, net: CurlingNetState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!validNet(net)) return
        lastAppliedRemoteVersion = version
        state.value = fromNet(net)
        sessionWins.value = net.sessionWins
        totalEnds = net.totalEnds
        stonesPerTeam = net.stonesPerTeam
        hintThrow.value = null
        cachedReplay = null
    }
}

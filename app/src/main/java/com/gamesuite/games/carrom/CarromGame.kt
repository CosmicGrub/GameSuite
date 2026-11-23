package com.gamesuite.games.carrom

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class CarromSnapshot(val net: CarromNetState, val nextStarter: Int, val difficulty: String)

private val carromJson = Json { ignoreUnknownKeys = true }

/**
 * Carrom for two players, or four in two partnerships (seats 0 and 2 play white, seats 1 and 3 black). Flick the
 * striker from your baseline; pocket your own colour to shoot again; pocket the queen and cover her with one of
 * your own coins on the same turn (or the very next flick); a pocketed striker is a foul. Clear all nine of your coins
 * (queen settled) to win the board, scoring a point per coin the other side has left (+3 with the queen). A match
 * can run to a target of points across boards.
 *
 * Physics is deterministic (see [CarromPhysics]); the only randomness is the computer's wobble and tie-breaks,
 * from the injected [random]. [clock] stamps each shot. In a networked game only the host simulates; a guest
 * sends a [CarromNetMessage.ShotRequest] and replays what the host broadcasts.
 *
 * Undo (against the computer only) goes back to before your last flick, so the computer's replies are undone with it.
 */
class CarromGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "carrom"
    override val displayName = "Carrom"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE
    )

    val state = mutableStateOf<CarromState?>(null)

    /** Points a match is played to (0 = a single board); set from the Hub before [init]. */
    var targetPoints: Int = 0
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)
    val hintShot = mutableStateOf<CarromShot?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStarter = 0
    private val history = SnapshotHistory()
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0
    private var cachedReplay: Pair<Int, CarromSimResult>? = null

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    override fun init(context: GameContext) {
        require(CarromRules.validSeatCount(context.players.size)) { "Carrom needs 2 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStarter = if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        history.clear()
        hintShot.value = null
        hostLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        cachedReplay = null
        if (isNetworked) registerNetworkListeners()
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: CarromState): Boolean {
        if (matchOver.value || s.boardOver) return false
        if (s.players[s.currentPlayerIndex].isBot) return false
        return if (isNetworked) !hostLeft.value && s.currentPlayerIndex == context.localPlayerIndex else true
    }

    /** The seat this device draws at the bottom of the screen. */
    fun viewSeat(): Int = if (isNetworked) context.localPlayerIndex.coerceAtLeast(0) else 0

    fun explainShot(shot: CarromShot): CarromReject {
        val s = state.value ?: return CarromReject.BOARD_OVER
        if (matchOver.value) return CarromReject.BOARD_OVER
        if (!isMyTurn(s)) return if (s.boardOver) CarromReject.BOARD_OVER else CarromReject.NOT_YOUR_TURN
        return CarromRules.explain(s, s.currentPlayerIndex, shot)
    }

    /** The motion of the last shot of [s] (cached; a guest computes it from the board the host sent). */
    fun replayOf(s: CarromState): CarromSimResult? {
        val rec = s.last ?: return null
        cachedReplay?.let { if (it.first == s.shotSerial) return it.second }
        val side = s.players[rec.seat].side
        val l = CarromRules.launch(side, rec.shot)
        val sim = CarromPhysics.simulate(rec.before, l[0], l[1], l[2], l[3], record = true)
        cachedReplay = s.shotSerial to sim
        return sim
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun toNet(s: CarromState) = CarromNetState(
        players = s.players.map { CarromNetPlayer(it.playerId, it.displayName, it.isBot) },
        coins = s.coins.map { CarromNetCoin(it.id, it.x, it.y) },
        currentPlayerIndex = s.currentPlayerIndex, queenOwner = s.queenOwner, queenPending = s.queenPending,
        shotsTaken = s.shotsTaken, flags = s.flags, eventSeat = s.eventSeat, potted = s.pottedThisShot,
        lastSeat = s.last?.seat, lastShot = s.last?.shot, before = s.last?.before?.map { CarromNetCoin(it.id, it.x, it.y) } ?: emptyList(),
        shotSerial = s.shotSerial, boardOver = s.boardOver, winnerTeam = s.winnerTeam, winPoints = s.winPoints,
        teamPoints = s.teamPoints, targetPoints = s.targetPoints, sessionWins = sessionWins.value
    )

    private fun validCoins(list: List<CarromNetCoin>): Boolean {
        if (list.size > CarromBoard.COIN_COUNT) return false
        if (list.map { it.id }.toSet().size != list.size) return false
        val lo = CarromBoard.COIN_R - 0.5
        val hi = CarromBoard.SIZE - CarromBoard.COIN_R + 0.5
        return list.all { it.id in 0 until CarromBoard.COIN_COUNT && it.x.isFinite() && it.y.isFinite() && it.x in lo..hi && it.y in lo..hi }
    }

    private fun validNet(net: CarromNetState): Boolean {
        val roster = context.players
        val n = roster.size
        if (net.players.size != n) return false
        if (!net.players.indices.all { net.players[it].playerId == roster[it].playerId }) return false
        if (!validCoins(net.coins) || !validCoins(net.before)) return false
        if (net.currentPlayerIndex !in 0 until n) return false
        if (net.queenOwner !in -1..1 || net.queenPending !in -1..1) return false
        val queenOn = net.coins.any { it.id == CarromBoard.QUEEN_ID }
        val queenSettled = (net.queenOwner >= 0) || (net.queenPending >= 0)
        if (queenOn == queenSettled) return false
        if (net.queenOwner >= 0 && net.queenPending >= 0) return false
        if (net.shotsTaken < 0 || net.shotsTaken > CarromBoard.MAX_SHOTS || net.shotSerial < 0 || net.flags < 0) return false
        if (net.eventSeat != null && net.eventSeat !in 0 until n) return false
        if (net.potted.any { it !in 0 until CarromBoard.COIN_COUNT }) return false
        if (net.lastShot != null) {
            if (!CarromRules.shotInRange(net.lastShot) || net.lastSeat == null || net.lastSeat !in 0 until n) return false
        } else if (net.lastSeat != null || net.before.isNotEmpty()) return false
        if (net.winnerTeam !in -1..1 || net.winPoints < 0) return false
        if (net.teamPoints.size != 2 || net.teamPoints.any { it < 0 }) return false
        if (net.targetPoints < 0) return false
        if (!net.boardOver && net.winnerTeam != -1) return false
        if (net.sessionWins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) return false
        return true
    }

    private fun fromNet(net: CarromNetState): CarromState {
        val n = net.players.size
        val players = net.players.mapIndexed { i, p -> CarromPlayerState(p.playerId, p.displayName, p.isBot, i % 2, CarromRules.sideFor(i, n)) }
        return CarromState(
            players = players, coins = net.coins.map { CarromCoin(it.id, it.x, it.y) }.sortedBy { it.id },
            currentPlayerIndex = net.currentPlayerIndex, queenOwner = net.queenOwner, queenPending = net.queenPending,
            shotsTaken = net.shotsTaken, flags = net.flags, eventSeat = net.eventSeat, pottedThisShot = net.potted,
            last = if (net.lastShot != null && net.lastSeat != null) CarromShotRecord(net.lastSeat, net.lastShot, net.before.map { CarromCoin(it.id, it.x, it.y) }) else null,
            shotSerial = net.shotSerial, boardOver = net.boardOver, winnerTeam = net.winnerTeam, winPoints = net.winPoints,
            teamPoints = net.teamPoints, targetPoints = net.targetPoints
        )
    }

    private fun capture(): String? {
        val s = state.value ?: return null
        return carromJson.encodeToString(CarromSnapshot.serializer(), CarromSnapshot(toNet(s), nextStarter, difficulty.name))
    }

    private fun decode(text: String): CarromSnapshot? = try {
        val snap = carromJson.decodeFromString(CarromSnapshot.serializer(), text)
        if (validNet(snap.net) && snap.nextStarter in 0 until context.players.size) snap else null
    } catch (_: Exception) {
        null
    }

    private fun apply(snap: CarromSnapshot, restoreDifficulty: Boolean) {
        state.value = fromNet(snap.net)
        sessionWins.value = snap.net.sessionWins
        nextStarter = snap.nextStarter
        targetPoints = snap.net.targetPoints
        if (restoreDifficulty) difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintShot.value = null
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

    /** Undo exists only against the computer: a pass-and-play flick is the other person's to live with. */
    private val undoAllowed: Boolean
        get() = this::context.isInitialized && context.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT

    override val canUndo: Boolean get() = undoAllowed && history.depth > 0 && !matchOver.value

    override fun undo() {
        if (!undoAllowed || matchOver.value) return
        val previous = history.pop() ?: return
        val snap = decode(previous) ?: return
        apply(snap, restoreDifficulty = false)
    }

    private fun hintAllowed(s: CarromState) =
        !isNetworked && !matchOver.value && !s.boardOver && !s.players[s.currentPlayerIndex].isBot

    private fun searchHint(s: CarromState): CarromShot? =
        CarromBot.search(s, Random(s.shotSerial.toLong() * 31 + 7))?.first

    suspend fun requestHint(): CarromShot? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = withContext(Dispatchers.Default) { searchHint(s) }
        if (state.value !== s || matchOver.value) return null
        hintShot.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    // --- Lifecycle -------------------------------------------------------------------------

    private fun playersFor(): List<CarromPlayerState> {
        val n = context.players.size
        return context.players.mapIndexed { i, p -> CarromPlayerState(p.playerId, p.displayName, p.isBot, i % 2, CarromRules.sideFor(i, n)) }
    }

    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        history.clear()
        hintShot.value = null
        cachedReplay = null
        val prior = state.value
        val carry = if (prior != null && prior.boardOver && prior.targetPoints > 0 && prior.teamPoints.none { it >= prior.targetPoints }) prior.teamPoints else listOf(0, 0)
        val starter = nextStarter % context.players.size
        commit(CarromRules.newBoard(playersFor(), starter, carry, targetPoints, clock()))
        nextStarter = (nextStarter + 1) % context.players.size
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session scoring the boards each side won. */
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

    /** True when the match has a target and a team has reached it (the next board starts a new match). */
    fun matchDecided(): Boolean {
        val s = state.value ?: return false
        return s.boardOver && s.targetPoints > 0 && s.teamPoints.any { it >= s.targetPoints }
    }

    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            if (hostLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(CarromNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // --- Shooting --------------------------------------------------------------------------

    /** The local player's flick. True when accepted (played here, or asked of the host). */
    fun shoot(shot: CarromShot): Boolean {
        if (explainShot(shot) != CarromReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(CarromNetMessage.ShotRequest(shot))
            return true
        }
        return doShot(shot)
    }

    private fun doShot(shot: CarromShot): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.boardOver) return false
        if (CarromRules.explain(s, s.currentPlayerIndex, shot) != CarromReject.NONE) return false
        val shooter = s.players[s.currentPlayerIndex]
        if (!shooter.isBot && !isNetworked && undoAllowed) capture()?.let { history.push(it) }
        hintShot.value = null
        val res = CarromRules.play(s, shot, clock(), record = true)
        cachedReplay = res.state.shotSerial to res.sim
        if (res.state.boardOver && res.state.winnerTeam != -1) {
            val winners = context.players.filterIndexed { i, _ -> i % 2 == res.state.winnerTeam }
            sessionWins.value = sessionWins.value + winners.associate { it.playerId to (sessionWins.value[it.playerId] ?: 0) + 1 }
        }
        commit(res.state)
        return true
    }

    // --- Computer --------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: CarromState): Boolean {
        if (matchOver.value || s.boardOver) return false
        if (isNetworked && !isHost) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    /** One computer flick; the search runs off the main thread. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val shot = withContext(Dispatchers.Default) { CarromBot.choose(s, difficulty, random) }
        if (state.value !== s || matchOver.value) return
        doShot(shot)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        doShot(CarromBot.choose(s, difficulty, random))
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(CarromNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(CarromNetMessage.RequestState) }
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
            if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(CarromNetMessage.RequestState) }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    private fun commit(newState: CarromState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(CarromNetMessage.serializer(), CarromNetMessage.StateSync(stateVersion, toNet(s))).toByteArray(Charsets.UTF_8)
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

    private fun sendToHost(message: CarromNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(CarromNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(localId, hostId, payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(CarromNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is CarromNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemote(message.version, message.state)
            is CarromNetMessage.RequestState -> if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is CarromNetMessage.ShotRequest ->
                if (isHost && s != null && senderSeat > 0 && s.currentPlayerIndex == senderSeat && !s.players[senderSeat].isBot) doShot(message.shot)
            is CarromNetMessage.RequestPlayAgain -> if (isHost && senderSeat > 0 && s?.boardOver == true && !matchOver.value) startMatch()
        }
    }

    private fun applyRemote(version: Int, net: CarromNetState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!validNet(net)) return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, 0, context.players)) return
        lastAppliedRemoteVersion = version
        state.value = fromNet(net)
        sessionWins.value = net.sessionWins
        targetPoints = net.targetPoints
        hintShot.value = null
        cachedReplay = null
    }
}

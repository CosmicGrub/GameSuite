package com.gamesuite.games.tankbattle

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

object TankEvent {
    const val START = "start"
    const val SHOT = "shot"
    const val HIT = "hit"
    const val KILL = "kill"
    const val MISS = "miss"
    const val WIN = "win"
    const val DRAW = "draw"
    val ALL = setOf(START, SHOT, HIT, KILL, MISS, WIN, DRAW)
}

enum class TankReject { NONE, OVER, NOT_YOUR_TURN, NO_AMMO, BAD_AIM }

@Serializable
data class Tank(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    /** The terrain column the tank stands on; it never moves. */
    val x: Int,
    val hp: Int = TankWorld.START_HP,
    val angle: Int = 90,
    val power: Int = 55,
    val weapon: String = TankWeapons.SHELL,
    val bigLeft: Int = TankWeapons.startAmmo(TankWeapons.BIG),
    val bouncersLeft: Int = TankWeapons.startAmmo(TankWeapons.BOUNCER),
    val damageDealt: Int = 0,
    val hits: Int = 0,
    val shots: Int = 0
) {
    val alive: Boolean get() = hp > 0

    fun ammoOf(weapon: String): Int = when (weapon) {
        TankWeapons.BIG -> bigLeft
        TankWeapons.BOUNCER -> bouncersLeft
        else -> -1
    }

    fun canFire(weapon: String): Boolean = weapon in TankWeapons.ALL && (ammoOf(weapon) != 0)
}

/** The whole battlefield. [shotSerial] only ever grows and keys the screen's animation of [lastShot]. */
@Serializable
data class TankState(
    val seed: Long,
    val terrain: List<Int>,
    val tanks: List<Tank>,
    val current: Int,
    val wind: Int,
    val turn: Int = 0,
    val phase: String = TankGame.PHASE_AIM,
    val event: String = TankEvent.START,
    val shotSerial: Int = 0,
    val lastShot: TankShot? = null,
    val winnerPlayerId: String? = null,
    /** True when the match is played in calm air (no wind). */
    val calm: Boolean = false
) {
    val aliveCount: Int get() = tanks.count { it.alive }
}

@Serializable
private data class TankSnapshot(
    val state: TankState,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val activeMs: Long = 0
)

private val tankJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Tank Battle for 2 to 4 tanks: a turn-based artillery duel on destructible terrain. On your turn
 * set an angle, a power and a shell, then fire; the wind changes every turn. Last tank standing wins.
 *
 * DETERMINISM: terrain, tank spots and the wind of every turn come from the match seed (one
 * `nextLong()` of the injected [random], kept in the snapshot); the shot itself has no randomness at
 * all. The computer's choices derive from the seed and the turn number, so undoing a shot and playing
 * it again changes nothing. In a networked match only the host simulates.
 */
class TankGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "tank-battle"
    override val displayName = "Tank Battle"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<TankState?>(null)
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    /** Rules for the NEXT match (the Hub's options): wind on or off, and whether the big shells and bouncers are in. */
    var windy: Boolean = true
    var specials: Boolean = true

    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)
    val hintPlan = mutableStateOf<TankPlan?>(null)
    val durationMs = mutableStateOf(0L)

    private val history = SnapshotHistory(limit = 60)
    override val canUndo: Boolean
        get() = history.depth > 0 && !isNetworked && !matchOver.value && (state.value?.phase == PHASE_AIM)

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private var startedAt = 0L
    private var carriedMs = 0L

    override fun init(context: GameContext) {
        require(context.players.size in 2..4) { "Tank Battle needs 2 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex =
            if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        hintPlan.value = null
        hostLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        history.clear()
        if (isNetworked) registerNetworkListeners()
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(TankNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(TankNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0) hostLeft.value = true
            return
        }
        val s = state.value ?: return
        if (seat == 0 || s.tanks[seat].isBot) return
        commit(s.copy(tanks = s.tanks.mapIndexed { i, t -> if (i == seat) t.copy(isBot = true) else t }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) {
                hostLeft.value = false
                sendToHost(TankNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.tanks[seat].isBot) return
        commit(s.copy(tanks = s.tanks.mapIndexed { i, t -> if (i == seat) t.copy(isBot = false) else t }))
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: TankState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER) return false
        if (s.tanks[s.current].isBot) return false
        return if (isNetworked) !hostLeft.value && s.current == context.localPlayerIndex else true
    }

    fun explainFire(weapon: String? = null): TankReject {
        val s = state.value ?: return TankReject.OVER
        return when {
            matchOver.value || s.phase == PHASE_OVER -> TankReject.OVER
            !isMyTurn(s) -> TankReject.NOT_YOUR_TURN
            weapon != null && !s.tanks[s.current].canFire(weapon) -> TankReject.NO_AMMO
            else -> TankReject.NONE
        }
    }

    // --- Resume / undo ---------------------------------------------------------------------

    private fun activeMs(): Long = carriedMs + (if (startedAt > 0) (clock() - startedAt).coerceAtLeast(0) else 0)

    private fun snapshotInternal(): String? {
        val s = state.value ?: return null
        return try {
            tankJson.encodeToString(
                TankSnapshot.serializer(),
                TankSnapshot(s, sessionWins.value, nextStartingPlayerIndex, difficulty.name, activeMs())
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun snapshot(): String? {
        val s = state.value
        if (matchOver.value || s == null || s.phase == PHASE_OVER) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
            context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        ) return null
        return snapshotInternal()
    }

    override fun restore(snapshot: String): Boolean {
        if (context.activeMode == com.gamesuite.core.PlayMode.LOCAL_AD_HOC || context.activeMode == com.gamesuite.core.PlayMode.ONLINE) return false // a networked table is the host's, never a saved game
        val snap = try {
            tankJson.decodeFromString(TankSnapshot.serializer(), snapshot)
        } catch (_: Exception) {
            return false
        }
        val roster = context.players
        val valid = validState(snap.state, roster.map { it.playerId }, roster.map { it.isBot }) &&
            snap.nextStartingPlayerIndex in roster.indices &&
            snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
            snap.activeMs >= 0 && snap.state.phase != PHASE_OVER
        if (!valid) return false
        matchOver.value = false
        state.value = snap.state
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        carriedMs = snap.activeMs
        startedAt = clock()
        hintPlan.value = null
        return true
    }

    override fun undo() {
        if (!canUndo) return
        val snap = history.pop() ?: return
        if (!restore(snap)) history.clear()
    }

    // --- Hint ------------------------------------------------------------------------------

    private fun hintAllowed(s: TankState) =
        !isNetworked && !matchOver.value && s.phase == PHASE_AIM && !s.tanks[s.current].isBot

    suspend fun requestHint(): TankPlan? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val plan = withContext(Dispatchers.Default) { TankBot.hint(s, s.current) }
        if (state.value !== s || matchOver.value) return null
        hintPlan.value = plan
        return plan
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val seed = random.nextLong()
        val (terrain, columns) = TankTerrain.generate(seed, context.players.size)
        val tanks = context.players.mapIndexed { i, p ->
            Tank(
                p.playerId, p.displayName, p.isBot, x = columns[i],
                angle = if (columns[i] < TankWorld.W / 2) 55 else 125,
                bigLeft = if (specials) TankWeapons.startAmmo(TankWeapons.BIG) else 0,
                bouncersLeft = if (specials) TankWeapons.startAmmo(TankWeapons.BOUNCER) else 0
            )
        }
        matchOver.value = false
        hintPlan.value = null
        carriedMs = 0
        startedAt = clock()
        durationMs.value = 0
        history.clear()
        val starter = nextStartingPlayerIndex % tanks.size
        commit(
            TankState(
                seed = seed, terrain = terrain, tanks = tanks, current = starter,
                wind = if (windy) TankWorld.windFor(seed, 0) else 0, calm = !windy
            )
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % tanks.size
    }

    override fun pause() {
        if (startedAt > 0) { carriedMs = activeMs(); startedAt = 0 }
    }

    override fun resume() {
        if (startedAt == 0L && state.value != null) startedAt = clock()
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val bestWins = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == bestWins }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = bestWins > 0 && w == bestWins && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            if (hostLeft.value || state.value?.phase != PHASE_OVER) return
            if (isHost) startMatch() else sendToHost(TankNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // --- Firing ----------------------------------------------------------------------------

    /** The local player's shot. True when accepted (for a guest: when sent to the host). */
    fun fire(angle: Int, power: Int, weapon: String): Boolean {
        if (!validAim(angle, power)) return false
        if (explainFire(weapon) != TankReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(TankNetMessage.FireRequest(angle, power, weapon))
            return true
        }
        if (!isNetworked) snapshotInternal()?.let { history.push(it) }
        return doFire(angle, power, weapon)
    }

    private fun doFire(angle: Int, power: Int, weapon: String): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != PHASE_AIM) return false
        if (!validAim(angle, power)) return false
        val seat = s.current
        val me = s.tanks[seat]
        if (!me.canFire(weapon)) return false
        hintPlan.value = null
        val terrain = s.terrain.toIntArray()
        val hpBefore = IntArray(s.tanks.size) { s.tanks[it].hp }
        val r = TankSim.simulate(
            terrain, s.tanks.map { TankBody(it.x, it.alive) }, hpBefore, seat, weapon, angle, power, s.wind
        )
        val dealt = s.tanks.indices.sumOf { if (it == seat) 0 else minOf(r.damage[it], hpBefore[it]) }
        val tanks = s.tanks.mapIndexed { i, t ->
            var n = t.copy(hp = (t.hp - r.damage[i]).coerceAtLeast(0))
            if (i == seat) {
                n = n.copy(
                    angle = angle, power = power, weapon = weapon, shots = t.shots + 1,
                    bigLeft = if (weapon == TankWeapons.BIG) t.bigLeft - 1 else t.bigLeft,
                    bouncersLeft = if (weapon == TankWeapons.BOUNCER) t.bouncersLeft - 1 else t.bouncersLeft,
                    damageDealt = t.damageDealt + dealt, hits = t.hits + (if (dealt > 0) 1 else 0)
                )
            }
            n
        }
        val alive = tanks.count { it.alive }
        val over = alive <= 1
        var next = seat
        if (!over) {
            do { next = (next + 1) % tanks.size } while (!tanks[next].alive)
        }
        val winner = if (over) tanks.firstOrNull { it.alive }?.playerId else null
        val event = when {
            over && winner != null -> TankEvent.WIN
            over -> TankEvent.DRAW
            r.killed.isNotEmpty() -> TankEvent.KILL
            dealt > 0 || r.damage.any { it > 0 } -> TankEvent.HIT
            else -> TankEvent.MISS
        }
        val shot = TankShot(seat, weapon, angle, power, s.wind, r.path.toList(), r.impacts, r.damage.toList(), r.killed)
        if (over) {
            if (winner != null) sessionWins.value = sessionWins.value + (winner to ((sessionWins.value[winner] ?: 0) + 1))
            durationMs.value = activeMs()
        }
        commit(
            s.copy(
                terrain = terrain.toList(), tanks = tanks, current = next, turn = s.turn + 1,
                wind = if (s.calm) 0 else TankWorld.windFor(s.seed, s.turn + 1), phase = if (over) PHASE_OVER else PHASE_AIM,
                event = event, shotSerial = s.shotSerial + 1, lastShot = shot, winnerPlayerId = winner
            )
        )
        return true
    }

    // --- Bot -------------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: TankState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER) return false
        if (isNetworked && !isHost) return false
        return s.tanks[s.current].isBot
    }

    private fun botRandom(s: TankState) = Random(s.seed xor (s.turn.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor 0x5DEECE66DL)

    /** One bot shot (the planning runs off the main thread). */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val plan = withContext(Dispatchers.Default) { TankBot.plan(s, s.current, difficulty, botRandom(s)) }
        if (state.value !== s || matchOver.value) return
        doFire(plan.angle, plan.power, plan.weapon)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val plan = TankBot.plan(s, s.current, difficulty, botRandom(s))
        doFire(plan.angle, plan.power, plan.weapon)
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun commit(newState: TankState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(
            TankNetMessage.serializer(), TankNetMessage.StateSync(stateVersion, s, sessionWins.value)
        ).toByteArray(Charsets.UTF_8)
    }

    private fun broadcastState() {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.players.drop(1).filter { !it.isBot }.forEach {
            context.transport.send(fromPlayerId = hostId, toPlayerId = it.playerId, payload = payload)
        }
    }

    private fun sendStateTo(playerId: String) {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.transport.send(fromPlayerId = hostId, toPlayerId = playerId, payload = payload)
    }

    private fun sendToHost(message: TankNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(TankNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(TankNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is TankNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message)
            is TankNetMessage.RequestState ->
                if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is TankNetMessage.FireRequest ->
                if (isHost && s != null && senderSeat > 0 && s.current == senderSeat &&
                    !s.tanks[senderSeat].isBot && s.phase == PHASE_AIM
                ) doFire(message.angle, message.power, message.weapon)
            is TankNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && s?.phase == PHASE_OVER && !matchOver.value) startMatch()
        }
    }

    private fun applyRemoteState(msg: TankNetMessage.StateSync) {
        if (msg.version <= lastAppliedRemoteVersion) return
        val roster = context.players
        if (!validState(msg.state, roster.map { it.playerId }, null)) return
        if (!msg.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }) return
        lastAppliedRemoteVersion = msg.version
        state.value = msg.state
        sessionWins.value = msg.sessionWins
        hintPlan.value = null
    }

    companion object {
        const val PHASE_AIM = "aim"
        const val PHASE_OVER = "over"

        private fun validAim(angle: Int, power: Int): Boolean =
            angle in 0..TankWorld.ANGLE_MAX && power in TankWorld.POWER_MIN..TankWorld.POWER_MAX

        /**
         * Whether [s] could be a real battlefield for a table of [ids] (and, when known, [bots]). Used for
         * saves and for syncs from the host: anything impossible is refused.
         */
        fun validState(s: TankState, ids: List<String>, bots: List<Boolean>?): Boolean {
            val n = ids.size
            if (n !in 2..4 || s.tanks.size != n || s.current !in 0 until n) return false
            if (s.terrain.size != TankWorld.W || s.terrain.any { it !in 0..TankWorld.H }) return false
            if (s.tanks.indices.any { s.tanks[it].playerId != ids[it] }) return false
            if (bots != null && s.tanks.indices.any { s.tanks[it].isBot != bots[it] }) return false
            if (s.phase != PHASE_AIM && s.phase != PHASE_OVER) return false
            if (s.event !in TankEvent.ALL || s.turn < 0 || s.shotSerial < 0) return false
            if (s.wind !in -TankWorld.WIND_MAX..TankWorld.WIND_MAX || (s.calm && s.wind != 0)) return false
            for (t in s.tanks) {
                if (t.x !in 0 until TankWorld.W || t.hp !in 0..TankWorld.START_HP) return false
                if (t.angle !in 0..TankWorld.ANGLE_MAX || t.power !in TankWorld.POWER_MIN..TankWorld.POWER_MAX) return false
                if (t.weapon !in TankWeapons.ALL) return false
                if (t.bigLeft !in 0..TankWeapons.startAmmo(TankWeapons.BIG)) return false
                if (t.bouncersLeft !in 0..TankWeapons.startAmmo(TankWeapons.BOUNCER)) return false
                if (t.damageDealt < 0 || t.hits < 0 || t.shots < 0) return false
            }
            val alive = s.tanks.count { it.alive }
            if ((s.phase == PHASE_OVER) != (alive <= 1)) return false
            if (s.phase == PHASE_AIM && !s.tanks[s.current].alive) return false
            val expectedWinner = if (s.phase == PHASE_OVER) s.tanks.firstOrNull { it.alive }?.playerId else null
            if (s.winnerPlayerId != expectedWinner) return false
            val shot = s.lastShot
            if (shot != null) {
                if (shot.seat !in 0 until n || shot.weapon !in TankWeapons.ALL) return false
                if (shot.angle !in 0..TankWorld.ANGLE_MAX || shot.power !in TankWorld.POWER_MIN..TankWorld.POWER_MAX) return false
                if (shot.path.size % 2 != 0 || shot.path.size > TankWorld.MAX_PATH_POINTS * 2 + 4) return false
                if (shot.impacts.size > TankWorld.MAX_IMPACTS || shot.impacts.any { it.kind !in TankImpact.KINDS || it.radius !in 0..40 }) return false
                if (shot.damage.size != n || shot.damage.any { it < 0 || it > 1000 }) return false
                if (shot.killed.any { it !in 0 until n }) return false
            } else if (s.shotSerial != 0) return false
            return true
        }
    }
}

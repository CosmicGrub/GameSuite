package com.gamesuite.games.botbastion

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame

/** The few numbers the HUD shows, as one comparable value so the screen recomposes only when one changes. */
data class BbHud(
    val phase: Phase,
    val wave: Int,
    val goal: Int,
    val scrap: List<Int>,
    val score: Int,
    val scoreMult: Int,
    val scrapMult: Int,
    val coreHp: Int,
    val shield: Int,
    val specials: List<Int>,
    val buildSeconds: Int,
    val enemies: Int,
    val bots: Int,
    val aegisPrice: Int,
    val preview: WavePreview
)

/**
 * Bot Bastion as a [GameModule]: one device, one to four players, one [BbEngine]. The screen calls the action
 * methods and [tick]; it draws straight from [engine] inside a Canvas (the engine is never read from composition)
 * and recomposes only when [hud] changes. [paused] is a plain flag: pausing takes effect on the very next frame
 * and building, upgrading, selling and specials still work while paused.
 */
class BotBastionGame : GameModule, ResumableGame {
    override val gameId = "bot-bastion"
    override val displayName = "Bot Bastion"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = BbRules.MAX_PLAYERS
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    var maps: List<MapDef> = emptyList(); private set
    var mapId: String = "hearth"
    var tier: Tier = Tier.CAPTAIN
    var playerCount: Int = 1

    var engine: BbEngine? = null; private set
    val hud = mutableStateOf<BbHud?>(null)
    val paused = mutableStateOf(false)
    val speed = mutableStateOf(1)
    val matchOver = mutableStateOf(false)
    /** Bumped on every simulation batch; read only inside draw lambdas. */
    val frame = mutableLongStateOf(0L)
    /** Set when the run ends. */
    val outcome = mutableStateOf<BbOutcome?>(null)

    private var context: GameContext? = null
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var hudClock = 0.0

    /** Reads the launch extras: `map`, `tier`, `players`. Unknown values fall back to the first map, Captain, one player. */
    fun configure(extras: Map<String, String>, available: List<MapDef>) {
        maps = available
        mapId = extras["map"]?.takeIf { id -> available.any { it.id == id } } ?: available.firstOrNull()?.id ?: "hearth"
        tier = Tier.fromId(extras["tier"]) ?: Tier.CAPTAIN
        playerCount = (extras["players"]?.toIntOrNull() ?: 1).coerceIn(1, BbRules.MAX_PLAYERS)
    }

    fun setOnMatchEnd(l: (GameResult) -> Unit) { onMatchEnd = l }

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
    }

    override fun startMatch() {
        val map = maps.firstOrNull { it.id == mapId } ?: maps.firstOrNull() ?: return
        matchOver.value = false
        outcome.value = null
        manualPause = false
        paused.value = overlayPause
        engine = BbEngine(map, tier, playerCount, System.nanoTime() xor 0x5DEECE66DL)
        publish(force = true)
    }

    /** Back to the map picker: forgets the run (a finished one was already recorded by the screen). */
    fun toMenu() {
        engine = null
        outcome.value = null
        hud.value = null
        manualPause = false
        paused.value = overlayPause
    }

    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        startMatch()
    }

    private var manualPause = false
    private var overlayPause = false

    /** The shell's pause (menus, backgrounding) and the player's own Pause button are separate, so closing a menu never undoes the button. */
    override fun pause() { overlayPause = true; paused.value = true }
    override fun resume() { overlayPause = false; paused.value = manualPause }
    fun togglePause() { manualPause = !manualPause; paused.value = manualPause || overlayPause }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** The player leaves after the run; leaving mid-run is an abort and records nothing. */
    fun finish() {
        val e = engine
        if (e == null || !e.over) return endMatch(GameResult(wasAborted = true))
        val id = context?.players?.firstOrNull()?.playerId ?: "p1"
        endMatch(GameResult(scores = listOf(PlayerScore(id, e.score, isWinner = e.phase == Phase.WON))))
    }

    /** One frame's [dtSeconds] of real time. Does nothing while paused or over. */
    fun tick(dtSeconds: Float) {
        val e = engine ?: return
        if (paused.value || matchOver.value || e.over || dtSeconds <= 0f) return
        e.advance(dtSeconds.toDouble().coerceAtMost(0.1) * speed.value)
        frame.longValue = frame.longValue + 1
        hudClock += dtSeconds
        if (e.over) {
            outcome.value = e.outcome()
            publish(force = true)
        } else if (hudClock >= 0.1) {
            hudClock = 0.0
            publish(force = false)
        }
    }

    /** Re-publishes the HUD after a player action (so a purchase shows at once, even while paused). */
    fun touched() {
        frame.longValue = frame.longValue + 1
        publish(force = true)
    }

    private fun publish(force: Boolean) {
        val e = engine ?: return
        val h = BbHud(
            e.phase, e.wave, e.goalWave, e.players.map { it.scrap }, e.score, e.scoreMult, e.scrapMult, e.coreHp, e.shield,
            e.specials.toList(), if (e.phase == Phase.BUILD) kotlin.math.ceil(e.buildLeft.coerceAtLeast(0.0)).toInt() else 0,
            e.enemies.size, e.bots.size, e.aegisPrice(), e.preview()
        )
        if (force || h != hud.value) hud.value = h
    }

    fun place(pid: Int, type: BotType, node: Int) = engine?.place(pid, type, node).also { touched() }
    fun buySkiff(pid: Int) = engine?.buySkiff(pid).also { touched() }
    fun upgrade(pid: Int, botId: Int) = engine?.upgrade(pid, botId).also { touched() }
    fun sell(pid: Int, botId: Int) = engine?.sell(pid, botId).also { touched() }
    fun buyAegis(pid: Int) = engine?.buyAegis(pid).also { touched() }
    fun useSpecial(pid: Int, k: SpecialKind) = engine?.useSpecial(pid, k).also { touched() }
    fun callEarly(pid: Int) = engine?.callEarly(pid).also { touched() }
    fun setPriority(botId: Int, p: TargetPriority) = engine?.setPriority(botId, p).also { touched() }

    override fun snapshot(): String? = try {
        val e = engine
        if (e == null || matchOver.value) null else BbSnapshot.encode(e)
    } catch (_: Exception) {
        null
    }

    override fun restore(snapshot: String): Boolean {
        val e = BbSnapshot.decode(snapshot, maps) ?: return false
        engine = e
        manualPause = false
        paused.value = overlayPause
        mapId = e.map.id
        tier = e.tier
        playerCount = e.players.size
        matchOver.value = false
        outcome.value = null
        publish(force = true)
        return true
    }
}

package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/*
 * The Game Hub model (docs/design/game-hub.md).
 *
 * Every game in the menu is ONE card. Tapping it opens that game's Hub, which is generated from a
 * [GameHubSpec]: the modes the game offers (vs CPU, pass and play, nearby, online, daily, ...) and
 * each mode's options. Nothing here touches Compose or Android, so the launch rules are unit
 * tested. The Hub UI renders a spec; [buildHubLaunch] turns the player's choices into the roster,
 * play mode and options handed to GameSessionManager.launchGame.
 */

/** Which menu section a game lives in (each maps to an existing section title string). */
enum class HubFamily { BOARD, CARD, WORD, ARCADE_PUZZLE, TOOLKIT }

enum class HubModeKind { SOLO, VS_CPU, PASS_AND_PLAY, NEARBY, ONLINE, DAILY }

/** Option keys every game shares. Anything else is a game-specific key passed through as an extra. */
object HubKeys {
    const val DIFFICULTY = "difficulty"
    const val FIRST = "first"
    const val HUMANS = "humans"
    const val CPUS = "cpus"
    const val TEAMS = "teams"

    /** The keys [buildHubLaunch] consumes itself; every other resolved choice becomes a [LaunchOptions.extras] entry. */
    val standard: Set<String> = setOf(DIFFICULTY, FIRST, HUMANS, CPUS, TEAMS)

    const val DIFFICULTY_DEFAULT = "default"
    const val FIRST_YOU = "you"
    const val FIRST_OPPONENT = "opponent"
    const val FIRST_RANDOM = "random"
}

/**
 * One answer to a [HubOption.Choice]. [applies] makes it a preset: picking it also sets those other
 * keys (UNO's "Party" preset switches on several house rules at once); the player can still tweak
 * them afterwards.
 */
data class HubChoice(
    val id: String,
    val label: String,
    val help: String = "",
    val applies: Map<String, String> = emptyMap()
)

/** One adjustable setting of a mode. [visibleWhen] hides it unless the current choices allow it. */
sealed class HubOption {
    abstract val key: String
    abstract val label: String
    abstract val help: String
    abstract val visibleWhen: ((HubChoices) -> Boolean)?

    data class Choice(
        override val key: String,
        override val label: String,
        val choices: List<HubChoice>,
        val default: String,
        override val help: String = "",
        override val visibleWhen: ((HubChoices) -> Boolean)? = null
    ) : HubOption()

    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean,
        override val help: String = "",
        override val visibleWhen: ((HubChoices) -> Boolean)? = null
    ) : HubOption()

    data class Stepper(
        override val key: String,
        override val label: String,
        val min: Int,
        val max: Int,
        val default: Int,
        override val help: String = "",
        override val visibleWhen: ((HubChoices) -> Boolean)? = null
    ) : HubOption()
}

/** The player's current values for a mode's options, as strings (so they persist as-is). */
class HubChoices(private val raw: Map<String, String> = emptyMap()) {
    fun string(key: String, default: String): String = raw[key] ?: default
    fun int(key: String, default: Int): Int = raw[key]?.toIntOrNull() ?: default
    fun bool(key: String, default: Boolean): Boolean = raw[key]?.let { it == "true" } ?: default

    fun with(key: String, value: String) = HubChoices(raw + (key to value))
    fun with(key: String, value: Int) = with(key, value.toString())
    fun with(key: String, value: Boolean) = with(key, value.toString())

    fun asMap(): Map<String, String> = raw

    override fun equals(other: Any?) = other is HubChoices && other.raw == raw
    override fun hashCode() = raw.hashCode()
    override fun toString() = "HubChoices($raw)"
}

/** How a mode begins. */
enum class HubStart {
    /** Straight into the board (solo, vs CPU, daily). */
    IMMEDIATE,

    /** A names-and-Start step first: the device is about to be handed to another person. */
    READY_SCREEN,

    /** The Nearby / Online lobby, where the host presses Start once everyone has joined. */
    LOBBY
}

data class HubMode(
    val id: String,
    val kind: HubModeKind,
    val title: String,
    val blurb: String,
    /** The nav route the game's screen lives at. */
    val route: String,
    val start: HubStart,
    val options: List<HubOption> = emptyList(),
    /** CPU seats when the player has not changed the CPUs stepper (vs CPU modes). */
    val defaultCpus: Int = 1,
    /** Fewest / most total seats. A 2-seat game leaves both at 2. */
    val minSeats: Int = 2,
    val maxSeats: Int = 2,
    /** A game whose screen route depends on a choice (Tic-Tac-Toe's Misere/Wild) maps it here. */
    val routeFor: (HubChoices) -> String = { route },
    /** Other routes [routeFor] can return, so the Hub can find every save a mode may have produced. */
    val extraRoutes: List<String> = emptyList()
) {
    /** Every option's default, so a mode always has a complete set of values. */
    fun defaults(): HubChoices {
        var c = HubChoices()
        options.forEach { o ->
            c = when (o) {
                is HubOption.Choice -> c.with(o.key, o.default)
                is HubOption.Toggle -> c.with(o.key, o.default)
                is HubOption.Stepper -> c.with(o.key, o.default)
            }
        }
        return c
    }

    /**
     * [choices] made whole and valid: every missing option gets its default, a stepper is clamped
     * to its range and a choice that is no longer on offer falls back to its default. This is what
     * a stale saved value (from an older app version) goes through before it is used.
     */
    fun resolve(choices: HubChoices): HubChoices {
        var c = defaults()
        options.forEach { o ->
            val given = choices.asMap()[o.key] ?: return@forEach
            c = when (o) {
                is HubOption.Choice ->
                    if (o.choices.any { it.id == given }) c.with(o.key, given) else c
                is HubOption.Toggle -> c.with(o.key, given == "true")
                is HubOption.Stepper -> c.with(o.key, (given.toIntOrNull() ?: o.default).coerceIn(o.min, o.max))
            }
        }
        return c
    }
}

data class GameHubSpec(
    val gameId: String,
    val title: String,
    /** One line under the title on the Hub and the menu card. */
    val tagline: String,
    val family: HubFamily,
    val modes: List<HubMode>,
    /** The mode the Play button uses the first time (before the player has chosen any). */
    val defaultModeId: String = modes.first().id,
    /** Whether the game saves and resumes (real-time games and utilities do not). */
    val resumable: Boolean = true
) {
    val hasDaily: Boolean get() = modes.any { it.kind == HubModeKind.DAILY }
    val isNetworked: Boolean get() = modes.any { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE }

    /**
     * A game with a single, option-less, immediate mode and nothing to resume has nothing to
     * choose: its card launches it directly instead of opening a Hub. (A resumable game always
     * gets a Hub, because the Resume card lives there.)
     */
    val skipsHub: Boolean
        get() = !resumable && modes.size == 1 && modes[0].options.isEmpty() && modes[0].start == HubStart.IMMEDIATE

    fun mode(id: String): HubMode? = modes.firstOrNull { it.id == id }

    /** Every nav route any of this game's modes can open (the Hub matches saves against these). */
    fun routes(): Set<String> = modes.flatMap { listOf(it.route) + it.extraRoutes }.toSet()
}

/** Per-launch choices a game screen reads through [GameSessionManager.launchOptions] when it starts. */
data class LaunchOptions(
    /** Overrides Settings' default CPU difficulty for this match only (null = no override). */
    val cpuDifficulty: CpuDifficulty? = null,
    /** The roster seat that takes the first turn; 0 is the classic default. A game that cannot start elsewhere ignores it. */
    val firstMoverIndex: Int = 0,
    /** Every game-specific resolved choice (rules, variant, tier ...), keyed by its option key. */
    val extras: Map<String, String> = emptyMap()
)

/** A fully built local launch: pass these to [GameSessionManager.launchGame]. */
data class MatchLaunch(
    val mode: PlayMode,
    val players: List<PlayerInfo>,
    val localPlayerIndex: Int,
    val options: LaunchOptions
)

/** What [buildHubLaunch] decided. */
sealed interface HubLaunch {
    /** Start a local game: launch [launch], then navigate to [route]. */
    data class Local(val launch: MatchLaunch, val route: String) : HubLaunch

    /** Open the lobby for a networked mode; the lobby launches the game itself. */
    data class Lobby(val kind: HubModeKind, val gameId: String, val route: String) : HubLaunch
}

/**
 * Turns a mode and the player's [choices] into what to launch. [names] are the typed names of the
 * human seats in order (blank entries fall back to "Player n", or "You" for a lone human).
 * [random] resolves "Random" first mover; injectable for tests.
 */
fun buildHubLaunch(
    spec: GameHubSpec,
    mode: HubMode,
    choices: HubChoices,
    names: List<String> = emptyList(),
    random: Random = Random.Default
): HubLaunch {
    if (mode.kind == HubModeKind.NEARBY || mode.kind == HubModeKind.ONLINE) {
        return HubLaunch.Lobby(mode.kind, spec.gameId, mode.route)
    }
    val c = mode.resolve(choices)

    val humans: Int
    val cpus: Int
    when (mode.kind) {
        HubModeKind.SOLO, HubModeKind.DAILY -> { humans = 1; cpus = 0 }
        HubModeKind.VS_CPU -> { humans = 1; cpus = c.int(HubKeys.CPUS, mode.defaultCpus) }
        else -> { humans = c.int(HubKeys.HUMANS, 2).coerceAtLeast(1); cpus = c.int(HubKeys.CPUS, 0).coerceAtLeast(0) }
    }
    // Keep the table inside the game's seat range: pad with CPUs when short, trim CPUs (never
    // humans) when over.
    var cpuSeats = cpus
    if (mode.kind != HubModeKind.SOLO && mode.kind != HubModeKind.DAILY) {
        if (humans + cpuSeats < mode.minSeats) cpuSeats = mode.minSeats - humans
        if (humans + cpuSeats > mode.maxSeats) cpuSeats = (mode.maxSeats - humans).coerceAtLeast(0)
    }
    // Team play needs an even table of at least four.
    var teams = c.bool(HubKeys.TEAMS, false)
    if (teams) {
        if ((humans + cpuSeats) % 2 != 0 && humans + cpuSeats < mode.maxSeats) cpuSeats += 1
        teams = (humans + cpuSeats) % 2 == 0 && humans + cpuSeats >= 4
    }
    val seats = humans + cpuSeats

    val humanPlayers = List(humans) { i ->
        val typed = names.getOrNull(i) ?: ""
        val fallback = if (humans == 1) "You" else "Player ${i + 1}"
        PlayerInfo(
            playerId = "p${i + 1}",
            displayName = cleanPlayerName(typed, fallback),
            teamId = if (teams) i % 2 else -1
        )
    }
    val cpuPlayers = List(cpuSeats) { i ->
        PlayerInfo(
            playerId = "bot${i + 1}",
            displayName = if (cpuSeats == 1) "CPU" else "CPU ${i + 1}",
            isBot = true,
            teamId = if (teams) (humans + i) % 2 else -1
        )
    }
    val players = humanPlayers + cpuPlayers

    val firstMoverIndex = when (c.string(HubKeys.FIRST, HubKeys.FIRST_YOU)) {
        HubKeys.FIRST_OPPONENT -> if (seats > 1) 1 else 0
        HubKeys.FIRST_RANDOM -> random.nextInt(seats.coerceAtLeast(1))
        else -> 0
    }
    val difficulty = c.string(HubKeys.DIFFICULTY, HubKeys.DIFFICULTY_DEFAULT).let { id ->
        CpuDifficulty.values().firstOrNull { it.name == id }
    }
    // A hidden option (visibleWhen false) may still hold a stale value; it must not reach the game.
    val hiddenKeys = mode.options.filter { it.visibleWhen?.invoke(c) == false }.map { it.key }.toSet()
    val extras = c.asMap().filterKeys { it !in HubKeys.standard && it !in hiddenKeys }

    return HubLaunch.Local(
        launch = MatchLaunch(
            mode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
            players = players,
            localPlayerIndex = 0,
            options = LaunchOptions(
                cpuDifficulty = if (cpuSeats > 0) difficulty else null,
                firstMoverIndex = firstMoverIndex,
                extras = extras
            )
        ),
        route = mode.routeFor(c)
    )
}

/** Option builders for the settings almost every game shares, so specs read as data. */
object HubOptions {
    fun difficulty() = HubOption.Choice(
        key = HubKeys.DIFFICULTY,
        label = "Computer level",
        choices = listOf(
            HubChoice(HubKeys.DIFFICULTY_DEFAULT, "Settings default", "Uses Settings > Default CPU difficulty."),
            HubChoice("EASY", "Easy"),
            HubChoice("MEDIUM", "Medium"),
            HubChoice("HARD", "Hard")
        ),
        default = HubKeys.DIFFICULTY_DEFAULT
    )

    /** "Who moves first". [opponentLabel] reads "The computer" in a CPU mode and "Player 2" in pass and play. */
    fun firstMover(opponentLabel: String, youLabel: String = "You") = HubOption.Choice(
        key = HubKeys.FIRST,
        label = "Who moves first",
        choices = listOf(
            HubChoice(HubKeys.FIRST_YOU, youLabel),
            HubChoice(HubKeys.FIRST_OPPONENT, opponentLabel),
            HubChoice(HubKeys.FIRST_RANDOM, "Random")
        ),
        default = HubKeys.FIRST_YOU
    )

    fun humans(min: Int, max: Int, default: Int) = HubOption.Stepper(
        key = HubKeys.HUMANS, label = "People playing", min = min, max = max, default = default,
        help = "Players who share this device and pass it between turns."
    )

    fun cpus(min: Int, max: Int, default: Int) = HubOption.Stepper(
        key = HubKeys.CPUS, label = "Computer players", min = min, max = max, default = default
    )
}

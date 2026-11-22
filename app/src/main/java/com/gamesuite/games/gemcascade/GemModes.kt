package com.gamesuite.games.gemcascade

import kotlinx.serialization.Serializable

/** The game modes. Each is a [ModeRules] data object over the one shared engine. */
enum class GemMode(val id: String) {
    CLASSIC("classic"), ZEN("zen"), LIGHTNING("lightning"), QUEST("quest"),
    TWIST("twist"), MINE("mine"), BUTTERFLIES("butterflies"), COUNTDOWN("countdown");

    companion object {
        fun fromId(id: String?): GemMode? = values().firstOrNull { it.id == id }
    }
}

/**
 * A mode as pure data. The engine reads these numbers; it has no per-mode code paths beyond them.
 *  [moveLimit] -1 = unlimited. [timeLimitMs] 0 = no timer. [spawn*] rules run after every accepted move.
 */
data class ModeRules(
    val mode: GemMode,
    val kinds: Int = 7,
    val allowSwap: Boolean = true,
    val allowRotate: Boolean = false,
    val moveLimit: Int = -1,
    val timeLimitMs: Long = 0,
    /** When no move is left: shuffle (true) or end the game (false). */
    val shuffleOnNoMoves: Boolean = true,
    /** Classic level thresholds (score 500 * level * (level + 1) -> next level). */
    val classicLevels: Boolean = false,
    /** Lightning time rules. */
    val timePerSpecialMs: Long = 0,
    val timePerChainMs: Long = 0,
    val speedWindowMs: Long = 0,
    val speedStep: Int = 0,
    val speedBonusMs: Long = 0,
    val speedBurstMs: Long = 0,
    /** Butterflies: a butterfly appears every [butterflyEvery] moves, a spider every [spiderEvery]; spiders rise. */
    val butterflyEvery: Int = 0,
    val spiderEvery: Int = 0,
    val spidersRise: Boolean = false,
    /** Countdown: a bomb gem appears every [bombEvery] moves with [bombTimer] moves to live (shrinks to [minBombTimer]). */
    val bombEvery: Int = 0,
    val bombTimer: Int = 0,
    val minBombTimer: Int = 0,
    /** Mine: a treasure appears at the top every [treasureEvery] moves; each collected treasure gives [movesPerTreasure] moves. */
    val treasureEvery: Int = 0,
    val movesPerTreasure: Int = 0
) {
    val timed: Boolean get() = timeLimitMs > 0
    val limitedMoves: Boolean get() = moveLimit >= 0

    companion object {
        const val SCORE_PER_GEM = 10
        const val MAX_MULTIPLIER = 8

        fun of(mode: GemMode): ModeRules = when (mode) {
            GemMode.CLASSIC -> ModeRules(mode, classicLevels = true, shuffleOnNoMoves = false)
            GemMode.ZEN -> ModeRules(mode)
            GemMode.LIGHTNING -> ModeRules(
                mode, timeLimitMs = 75_000, timePerSpecialMs = 2_000, timePerChainMs = 1_000,
                speedWindowMs = 2_500, speedStep = 25, speedBonusMs = 3_000, speedBurstMs = 8_000
            )
            GemMode.QUEST -> ModeRules(mode, kinds = 6)
            GemMode.TWIST -> ModeRules(mode, allowSwap = false, allowRotate = true, shuffleOnNoMoves = false)
            GemMode.MINE -> ModeRules(mode, moveLimit = 35, treasureEvery = 6, movesPerTreasure = 3, shuffleOnNoMoves = true)
            GemMode.BUTTERFLIES -> ModeRules(mode, butterflyEvery = 3, spiderEvery = 9, spidersRise = true)
            GemMode.COUNTDOWN -> ModeRules(mode, bombEvery = 4, bombTimer = 14, minBombTimer = 7)
        }

        /** Classic: the score that completes [level]. */
        fun classicThreshold(level: Int): Int = 500 * level * (level + 1)
    }
}

/** Booster data: free, capped per level. There are no purchases, energy or loot boxes. */
data class BoosterDef(val kind: BoosterKind, val capPerLevel: Int, val extraMoves: Int = 0, val extraTimeMs: Long = 0)

object GemBoosters {
    val defs: List<BoosterDef> = listOf(
        BoosterDef(BoosterKind.HAMMER, 3),
        BoosterDef(BoosterKind.SWAP_ANY, 2),
        BoosterDef(BoosterKind.SHUFFLE, 2),
        BoosterDef(BoosterKind.EXTRA_MOVES, 1, extraMoves = 5, extraTimeMs = 10_000),
        BoosterDef(BoosterKind.COLOUR_BOMB, 1)
    )

    fun def(kind: BoosterKind): BoosterDef = defs.first { it.kind == kind }
}

// ---------------------------------------------------------------------------------------------
// Quest levels (data)
// ---------------------------------------------------------------------------------------------

/**
 * One goal of a level. [target] 0 means "everything the layout contains" (all ice layers, all stones, all cages,
 * all treasures, all butterflies, all bombs, all spiders). [kind] is the colour for `CLEAR_GEMS` (-1 = any).
 */
@Serializable
data class LevelGoal(val type: String, val target: Int = 0, val kind: Int = -1)

@Serializable
data class LevelBomb(val r: Int, val c: Int, val timer: Int)

/**
 * A quest level as JSON data. [pieces] is a row-per-string grid: `.` random gem, `L` caged gem, `S` stone (hp 2),
 * `s` stone (hp 1), `b` butterfly, `T` treasure, `x` spider, `#` a stone with hp 3. [floor] (optional) holds ice layers
 * `0`..`3` per cell. [bombs] turn a gem into a time bomb. [stars] are the 1, 2 and 3 star score thresholds.
 */
@Serializable
data class GemLevel(
    val id: Int,
    val rows: Int = 8,
    val cols: Int = 8,
    val kinds: Int = 5,
    val seed: Long,
    val moves: Int,
    val stars: List<Int>,
    val goals: List<LevelGoal>,
    val pieces: List<String>,
    val floor: List<String> = emptyList(),
    val bombs: List<LevelBomb> = emptyList()
) {
    fun starsFor(score: Int): Int = stars.count { score >= it }
}

@Serializable
data class GemLevelFile(val version: Int = 1, val levels: List<GemLevel>)

object GemLevels {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Parses the bundled JSON; throws on malformed input (the validator test would fail first). */
    fun parse(text: String): List<GemLevel> = json.decodeFromString(GemLevelFile.serializer(), text).levels

    fun goalType(name: String): GoalType? = when (name) {
        "score" -> GoalType.SCORE
        "clear_gems" -> GoalType.CLEAR_GEMS
        "ice" -> GoalType.CLEAR_ICE
        "stones" -> GoalType.BREAK_STONES
        "locks" -> GoalType.BREAK_LOCKS
        "treasure" -> GoalType.COLLECT_TREASURE
        "butterflies" -> GoalType.ESCAPE_BUTTERFLIES
        "bombs" -> GoalType.DEFUSE_BOMBS
        "specials" -> GoalType.MAKE_SPECIALS
        "spiders" -> GoalType.SQUASH_SPIDERS
        else -> null
    }

    private const val PIECE_CHARS = ".LSsbTx#"

    /** Everything wrong with a level's data (empty = fine). Pure checks only; solvability is the solver's job. */
    fun problems(level: GemLevel): List<String> {
        val out = ArrayList<String>()
        if (level.rows !in 6..10 || level.cols !in 6..10) out += "size ${level.rows}x${level.cols} outside 6..10"
        if (level.kinds !in 3..GemKinds.COUNT) out += "kinds ${level.kinds}"
        if (level.moves !in 3..80) out += "moves ${level.moves}"
        if (level.stars.size != 3 || level.stars.zipWithNext().any { (a, b) -> a >= b } || level.stars.any { it <= 0 }) out += "stars"
        if (level.pieces.size != level.rows || level.pieces.any { it.length != level.cols }) out += "pieces grid shape"
        if (level.floor.isNotEmpty() && (level.floor.size != level.rows || level.floor.any { it.length != level.cols })) out += "floor grid shape"
        if (level.goals.isEmpty()) out += "no goals"
        if (out.isNotEmpty()) return out
        for (row in level.pieces) for (ch in row) if (ch !in PIECE_CHARS) out += "bad piece char '$ch'"
        for (row in level.floor) for (ch in row) if (ch !in '0'..'3') out += "bad floor char '$ch'"
        for (b in level.bombs) {
            if (b.r !in 0 until level.rows || b.c !in 0 until level.cols) out += "bomb off board"
            else if (level.pieces[b.r][b.c] != '.') out += "bomb ${b.r},${b.c} not on a plain gem"
            if (b.timer !in 3..40) out += "bomb timer ${b.timer}"
        }
        for (g in level.goals) if (goalType(g.type) == null) out += "unknown goal ${g.type}"
        if (out.isNotEmpty()) return out
        val bottom = level.rows - 1
        if (level.pieces[bottom].any { it == 'T' }) out += "treasure on the bottom row"
        if (level.pieces[0].any { it == 'b' || it == 'x' }) out += "butterfly or spider on the top row"
        val have = countsOf(level)
        for (g in level.goals) {
            val type = goalType(g.type)!!
            val avail = have[type]
            if (avail != null && g.target > avail) out += "goal ${g.type} target ${g.target} > $avail available"
            if (avail != null && avail == 0 && g.target == 0) out += "goal ${g.type} has nothing in the layout"
            if (type == GoalType.CLEAR_GEMS && g.kind >= level.kinds) out += "goal colour ${g.kind} not in play"
        }
        return out
    }

    /** How much of each obstacle goal the layout contains (null for goals with no fixed supply). */
    fun countsOf(level: GemLevel): Map<GoalType, Int?> {
        val flat = level.pieces.joinToString("")
        val m = HashMap<GoalType, Int?>()
        m[GoalType.BREAK_LOCKS] = flat.count { it == 'L' }
        m[GoalType.BREAK_STONES] = flat.count { it == 'S' || it == 's' || it == '#' }
        m[GoalType.COLLECT_TREASURE] = flat.count { it == 'T' }
        m[GoalType.ESCAPE_BUTTERFLIES] = flat.count { it == 'b' }
        m[GoalType.SQUASH_SPIDERS] = flat.count { it == 'x' }
        m[GoalType.DEFUSE_BOMBS] = level.bombs.size
        m[GoalType.CLEAR_ICE] = level.floor.joinToString("").sumOf { it - '0' }
        m[GoalType.SCORE] = null
        m[GoalType.CLEAR_GEMS] = null
        m[GoalType.MAKE_SPECIALS] = null
        return m
    }

    /** A deterministic daily level from the UTC [epochDay]: a score target on a 6-colour 8 x 8 board in 30 moves. */
    fun daily(epochDay: Long): GemLevel {
        val rng = GemRng(epochDay * 7919L + 104729L)
        val target = 3500 + rng.nextInt(10) * 250
        return GemLevel(
            id = 1000, rows = 8, cols = 8, kinds = 6, seed = rng.nextLong(), moves = 30,
            stars = listOf(target, target * 3 / 2, target * 2),
            goals = listOf(LevelGoal("score", target)),
            pieces = List(8) { ".".repeat(8) }
        )
    }
}

/** UTC calendar helpers for the daily challenge (no time-zone surprises: everyone gets the same board on the same UTC day). */
object UtcDate {
    private const val DAY_MS = 86_400_000L

    fun epochDay(epochMillis: Long): Long = Math.floorDiv(epochMillis, DAY_MS)

    /** `yyyy-mm-dd` for an epoch day (civil-from-days algorithm, no java.time needed). */
    fun iso(epochDay: Long): String {
        val z = epochDay + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return "%04d-%02d-%02d".format(year, m, d)
    }

    fun dailySeed(epochDay: Long): Long = epochDay * 7919L + 104729L
}

package com.gamesuite.games.botbastion

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Arena size in world units (16 x 9). All engine coordinates, ranges and speeds are in these units. */
object BbArena {
    const val W = 16.0
    const val H = 9.0
}

enum class TargetPriority { FIRST, LAST, WEAKEST, STRONGEST, CLOSEST }

/**
 * The five bot roles (the Aegis shield is a Core upgrade, not a bot). Numbers are `BALANCE.md` section 1.
 * [range1] is a fraction of the arena width, [rate1] shots per second.
 */
enum class BotType(val id: String, val cost: Int, val power1: Double, val range1: Double, val rate1: Double) {
    PICKET("picket", 60, 12.0, 0.20, 2.0),
    LOBBER("lobber", 110, 60.0, 0.34, 0.45),
    HAZE("haze", 90, 3.5, 0.42, 6.0),
    SKIFF("skiff", 170, 20.0, 0.16, 3.0),
    TENDER("tender", 80, 0.0, 0.18, 0.0);

    /** Skiffs fly the rim and need no build node. */
    val needsNode: Boolean get() = this != SKIFF
    val deals: Boolean get() = this != TENDER

    companion object {
        fun fromId(id: String?): BotType? = values().firstOrNull { it.id == id }
    }
}

object BotStats {
    const val MAX_LEVEL = 5
    val UPGRADE_FACTORS = doubleArrayOf(0.7, 1.05, 1.6, 2.4)

    /** Lobber splash radius (0.045 W) and the blind spot around it (0.08 W), in world units. */
    const val SPLASH_U = 0.045 * BbArena.W
    const val BLIND_U = 0.08 * BbArena.W
    const val ARMOUR_FACTOR = 0.75
    const val FREEZE_SECONDS = 0.5
    const val SELL_PERCENT = 75
    const val SELL_UNDO_SECONDS = 3.0
    const val SKIFF_SPEED = 0.22 * BbArena.W

    private val tenderSlow = doubleArrayOf(0.30, 0.38, 0.46, 0.53, 0.60)
    private val tenderFreezeEvery = doubleArrayOf(6.0, 5.4, 4.8, 4.1, 3.5)

    fun power(t: BotType, level: Int): Double = t.power1 * 1.4.pow(level - 1)
    fun rangeFraction(t: BotType, level: Int): Double = t.range1 * (1 + 0.08 * (level - 1))
    fun rangeU(t: BotType, level: Int): Double = rangeFraction(t, level) * BbArena.W
    fun rate(t: BotType, level: Int): Double = t.rate1 * (1 + 0.10 * (level - 1))
    fun dps(t: BotType, level: Int): Double = power(t, level) * rate(t, level)
    fun tenderSlow(level: Int): Double = tenderSlow[(level - 1).coerceIn(0, 4)]
    fun tenderFreezeEvery(level: Int): Double = tenderFreezeEvery[(level - 1).coerceIn(0, 4)]

    /** Round to the nearest 5 (halves up). */
    fun roundTo5(x: Double): Int = (floor(x / 5.0 + 0.5) * 5).toInt()

    /** Price (before any tier price multiplier) to go from [level] to level + 1; null at the top. */
    fun upgradeCost(t: BotType, level: Int): Int? =
        if (level !in 1 until MAX_LEVEL) null else roundTo5(t.cost * UPGRADE_FACTORS[level - 1])

    /** Everything paid to get a bot to [level] (before tier multipliers). */
    fun invested(t: BotType, level: Int): Int {
        var sum = t.cost
        for (l in 1 until level) sum += upgradeCost(t, l)!!
        return sum
    }

    fun refund(invested: Int, ageSeconds: Double): Int =
        if (ageSeconds <= SELL_UNDO_SECONDS) invested else invested * SELL_PERCENT / 100
}

enum class EnemyType(
    val id: String, val hp: Double, val speed: Double, val points: Int, val scrap: Int, val leakMult: Int
) {
    DRIFTER("drifter", 30.0, 0.06, 10, 6, 1),
    DARTER("darter", 18.0, 0.125, 14, 7, 1),
    PLATER("plater", 120.0, 0.045, 35, 18, 2),
    SPLITTER("splitter", 60.0, 0.058, 25, 10, 1),
    COURIER("courier", 80.0, 0.10, 60, 25, 0),
    BEHEMOTH("behemoth", 1500.0, 0.03, 600, 250, 10),
    BIT("bit", 22.0, 0.058, 5, 2, 1);

    /** World units per second at map pace 1.0. */
    val speedU: Double get() = speed * BbArena.W
    val isBoss: Boolean get() = this == BEHEMOTH
}

/** The five difficulty tiers (`BALANCE.md` section 5). [goal] is the wave to clear to win; 0 = endless. */
enum class Tier(
    val id: String, val leak: Int, val cash: Double, val hp: Double, val speed: Double,
    val swarmEvery: Int, val goal: Int, val priceMult: Double
) {
    CADET("cadet", 4, 1.25, 0.8, 0.9, 0, 20, 1.0),
    CAPTAIN("captain", 5, 1.0, 1.0, 1.0, 0, 30, 1.0),
    HERO("hero", 5, 1.0, 1.25, 1.05, 5, 40, 1.0),
    INSANITY("insanity", 8, 0.85, 1.6, 1.15, 3, 40, 1.1),
    ENDLESS("endless", 5, 1.0, 1.0, 1.0, 5, 0, 1.0);

    val startScrap: Int get() = (200 * cash).roundToInt()
    val endless: Boolean get() = goal == 0

    companion object {
        fun fromId(id: String?): Tier? = values().firstOrNull { it.id == id }
    }
}

enum class SpecialKind(val id: String, val rare: Boolean) {
    SCRAP_SURGE("scrap_surge", false),
    SCORE_BOOST("score_boost", false),
    NOVA_PULSE("nova_pulse", true),
    REPAIR_KIT("repair_kit", true)
}

object BbRules {
    const val CORE_MAX = 100
    const val SHIELD_PER_AEGIS = 40
    const val AEGIS_BASE = 100
    const val AEGIS_MAX_BUYS = 12
    const val SPECIAL_CAP = 3
    const val MULT_CAP = 4
    const val MULT_SECONDS = 30.0
    const val CALL_EARLY_BONUS = 30
    const val FIRST_BUILD_SECONDS = 20.0
    const val BUILD_SECONDS = 10.0
    const val SPAWN_WINDOW = 20.0
    const val SWARM_COUNT = 30
    const val SWARM_WINDOW = 8.0
    const val SWARM_SPEED = 1.4
    const val MAX_PLAYERS = 4
    const val MAX_SKIFFS = 6
    const val PILOT_HP = 60.0
    const val PILOT_RESPAWN = 10.0
    const val MIN_GIFT = 10
    const val UPGRADE_REBATE_PERCENT = 10
    val COOP_HP = doubleArrayOf(1.0, 1.2, 1.4, 1.6)
    const val HP_GROWTH = 1.11
    const val SKIFF_CAP_PER_PLAYER = 3

    fun waveCount(w: Int): Int = minOf(90, 20 + 3 * (w - 1))
    fun hpGrowth(w: Int): Double = HP_GROWTH.pow(w - 1)
    fun clearScrap(w: Int): Int = 20 + 5 * w
    fun clearScore(w: Int): Int = 100 + 10 * w
    fun isBossWave(w: Int): Boolean = w > 0 && w % 10 == 0
    fun hasCourier(w: Int): Boolean = w > 0 && w % 5 == 0

    /** Aegis price after [bought] purchases (floor of 100 x 1.25^bought), then the tier multiplier. */
    fun aegisPrice(bought: Int, tier: Tier): Int {
        val base = floor(AEGIS_BASE * 1.25.pow(bought) + 1e-9).toInt()
        return priced(base, tier)
    }

    fun priced(base: Int, tier: Tier): Int = if (tier.priceMult == 1.0) base else floor(base * tier.priceMult + 1e-9).toInt()
}

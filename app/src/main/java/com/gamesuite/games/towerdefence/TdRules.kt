package com.gamesuite.games.towerdefence

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The balance sheet of the open-field "Castle Siege" maps (docs/design/tower-defence/MAP-MODEL.md
 * section 9 and the codex board). Stats belong to the ROLE and the ARCHETYPE, never to a theme:
 * a theme only re-skins names, art, colours and sounds. Pure Kotlin, no Compose, JVM tested.
 *
 * Units: distances are in TILES (the field is 24 x 14 tiles), speeds in tiles per second.
 * Every number is a tuning default taken from the design boards, not a playtest result.
 */
enum class TdRole { BOLT, BLAST, CONTROL, SUPPORT }

/** The level-3 upgrade choice. [NONE] is only legal below level 4. */
enum class TdBranch { NONE, A, B }

/** Per-tower targeting mode. [FIRST] is the shipped behaviour and the default. */
enum class TdTarget { FIRST, LAST, STRONG, CLOSE }

enum class TdEnemyKind { RUNNER, BRUTE, FLYER, BOSS }

data class TdTowerStats(
    val damage: Float,
    val shotsPerSecond: Float,
    val range: Float,
    /** Splash radius in tiles; 0 = single target. */
    val splashRadius: Float = 0f,
    /** Fraction of speed removed (0.3 = 30 percent slower). */
    val slow: Float = 0f,
    val stunSeconds: Float = 0f,
    /** Every Nth shot stuns (0 = never). */
    val stunEvery: Int = 0,
    val burnSeconds: Float = 0f,
    /** Support aura: radius in tiles and the bonuses it gives. */
    val auraRange: Float = 0f,
    val auraRate: Float = 0f,
    val auraRangeBonus: Float = 0f,
    val goldBonus: Float = 0f,
    /** Extra damage multiplier against bosses (Sniper: 0.25). */
    val bossBonus: Float = 0f
) {
    val attacks: Boolean get() = shotsPerSecond > 0f
    val cooldownSeconds: Float get() = if (attacks) 1f / shotsPerSecond else Float.MAX_VALUE
}

data class TdEnemySpec(
    val hpMultiplier: Float,
    val speedMultiplier: Float,
    val gold: Int,
    /** Fraction of Bolt damage ignored (Brute 0.25). Splash and burn ignore armour. */
    val boltArmour: Float,
    val flying: Boolean,
    val castleDamage: Int,
    val stunImmune: Boolean,
    /** Fraction of a slow's strength that is resisted (Boss 0.5). */
    val slowResist: Float
)

object TdRules {
    const val MAX_LEVEL = 5
    const val BRANCH_LEVEL = 3
    const val SELL_REFUND = 0.75f
    /** A tower sold within this many seconds of being built refunds in full. */
    const val UNDO_WINDOW_SECONDS = 3f
    const val MOVE_FEE = 0.20f
    const val PROJECTILE_SPEED = 9f // tiles per second
    const val PROJECTILE_HIT = 0.15f // tiles
    const val BURN_DPS = 8f
    const val SLOW_SECONDS = 1.5f

    const val BASE_ENEMY_HP = 40f
    const val ENEMY_HP_PER_WAVE = 24f
    /** Tuned so a first-wave runner needs roughly 33 s on an unobstructed ~26 tile route. */
    const val BASE_ENEMY_SPEED = 0.85f
    const val ENEMY_SPEED_PER_WAVE = 0.03f
    const val MAX_ENEMY_SPEED = 1.8f
    const val SPAWN_INTERVAL = 0.8f

    val TOTAL_WAVES: Int get() = TowerDefenceGame.TOTAL_WAVES

    fun baseCost(role: TdRole): Int = when (role) {
        TdRole.BOLT -> 50
        TdRole.BLAST -> 90
        TdRole.CONTROL -> 80
        TdRole.SUPPORT -> 110
    }

    private fun costFactor(role: TdRole): Float = when (role) {
        TdRole.BOLT -> 1.0f
        TdRole.BLAST -> 1.8f
        TdRole.CONTROL -> 1.6f
        TdRole.SUPPORT -> 2.2f
    }

    /** Price of the upgrade from [currentLevel] to the next: the shipped curve times a role factor. */
    fun upgradeCost(role: TdRole, currentLevel: Int): Int =
        (TowerDefenceGame.UPGRADE_BASE_COST * TowerDefenceGame.UPGRADE_COST_GROWTH.pow(currentLevel - 1) * costFactor(role)).roundToInt()

    /** True when the tower must pick a branch to move on (level 3, no branch yet). */
    fun needsBranch(level: Int, branch: TdBranch): Boolean = level == BRANCH_LEVEL && branch == TdBranch.NONE

    fun stats(role: TdRole, level: Int, branch: TdBranch): TdTowerStats {
        val l = level.coerceIn(1, MAX_LEVEL)
        val b = if (l <= BRANCH_LEVEL) TdBranch.NONE else branch
        return when (role) {
            TdRole.BOLT -> when {
                l == 1 -> TdTowerStats(18f, 1.5f, 5.3f)
                l == 2 -> TdTowerStats(27f, 1.6f, 5.8f)
                l == 3 -> TdTowerStats(36f, 1.7f, 6.3f)
                b == TdBranch.B -> if (l == 4) TdTowerStats(63f, 1.0f, 6.8f, bossBonus = 0.25f) else TdTowerStats(90f, 1.0f, 7.7f, bossBonus = 0.25f)
                else -> if (l == 4) TdTowerStats(36f, 2.2f, 5.8f) else TdTowerStats(36f, 2.9f, 5.8f)
            }
            TdRole.BLAST -> when {
                l == 1 -> TdTowerStats(30f, 0.6f, 5.0f, splashRadius = 1.5f)
                l == 2 -> TdTowerStats(42f, 0.6f, 5.4f, splashRadius = 1.6f)
                l == 3 -> TdTowerStats(54f, 0.7f, 5.8f, splashRadius = 1.8f)
                b == TdBranch.B -> if (l == 4) TdTowerStats(40f, 0.7f, 5.8f, splashRadius = 1.8f, burnSeconds = 6f) else TdTowerStats(50f, 0.7f, 5.8f, splashRadius = 1.8f, burnSeconds = 9f)
                else -> if (l == 4) TdTowerStats(54f, 0.7f, 5.8f, splashRadius = 2.2f) else TdTowerStats(66f, 0.7f, 5.8f, splashRadius = 2.8f)
            }
            TdRole.CONTROL -> when {
                l == 1 -> TdTowerStats(6f, 1.0f, 4.5f, slow = 0.30f)
                l == 2 -> TdTowerStats(9f, 1.0f, 4.9f, slow = 0.35f)
                l == 3 -> TdTowerStats(12f, 1.0f, 5.3f, slow = 0.40f)
                b == TdBranch.B -> if (l == 4) TdTowerStats(12f, 1.0f, 5.3f, slow = 0.40f, stunSeconds = 0.4f, stunEvery = 5) else TdTowerStats(12f, 1.0f, 5.8f, slow = 0.40f, stunSeconds = 0.6f, stunEvery = 4)
                else -> if (l == 4) TdTowerStats(12f, 1.0f, 5.3f, slow = 0.55f) else TdTowerStats(12f, 1.0f, 5.8f, slow = 0.65f)
            }
            TdRole.SUPPORT -> when {
                l == 1 -> TdTowerStats(0f, 0f, 0f, auraRange = 4.0f, auraRate = 0.15f)
                l == 2 -> TdTowerStats(0f, 0f, 0f, auraRange = 4.5f, auraRate = 0.18f)
                l == 3 -> TdTowerStats(0f, 0f, 0f, auraRange = 5.0f, auraRate = 0.21f)
                b == TdBranch.B -> if (l == 4) TdTowerStats(0f, 0f, 0f, auraRange = 5.0f, auraRate = 0.24f, auraRangeBonus = 0.12f) else TdTowerStats(0f, 0f, 0f, auraRange = 5.0f, auraRate = 0.30f, auraRangeBonus = 0.18f)
                else -> if (l == 4) TdTowerStats(0f, 0f, 0f, auraRange = 5.0f, auraRate = 0.21f, goldBonus = 0.10f) else TdTowerStats(0f, 0f, 0f, auraRange = 5.0f, auraRate = 0.21f, goldBonus = 0.20f)
            }
        }
    }

    fun enemy(kind: TdEnemyKind): TdEnemySpec = when (kind) {
        TdEnemyKind.RUNNER -> TdEnemySpec(0.7f, 1.5f, 8, 0f, false, 1, false, 0f)
        TdEnemyKind.BRUTE -> TdEnemySpec(3.0f, 0.6f, 25, 0.25f, false, 1, false, 0f)
        TdEnemyKind.FLYER -> TdEnemySpec(1.0f, 1.1f, 12, 0f, true, 1, false, 0f)
        TdEnemyKind.BOSS -> TdEnemySpec(20f, 0.5f, 150, 0f, false, 5, true, 0.5f)
    }

    fun waveBaseHp(wave: Int): Float = BASE_ENEMY_HP + ENEMY_HP_PER_WAVE * (wave - 1)
    /** Capped so an Endless run stays playable: the cap is first reached at wave 32 (the campaign ends at 10). */
    fun waveBaseSpeed(wave: Int): Float = (BASE_ENEMY_SPEED + ENEMY_SPEED_PER_WAVE * (wave - 1)).coerceAtMost(MAX_ENEMY_SPEED)
}

/** Difficulty numbers of the open maps and Classic alike (Gentle is a fourth, Tower Defence only tier). */
data class TdTierConfig(
    val startingGold: Int,
    val hpMultiplier: Float,
    val speedMultiplier: Float,
    val countMultiplier: Float,
    val castleMultiplier: Float
)

/** The authored wave table of the 10-wave campaign: groups per wave, bosses on waves 5 and 10. */
object TdWaves {
    private val TABLE: List<List<Pair<TdEnemyKind, Int>>> = listOf(
        listOf(TdEnemyKind.RUNNER to 6),
        listOf(TdEnemyKind.RUNNER to 8),
        listOf(TdEnemyKind.RUNNER to 6, TdEnemyKind.BRUTE to 2),
        listOf(TdEnemyKind.RUNNER to 6, TdEnemyKind.FLYER to 4),
        listOf(TdEnemyKind.RUNNER to 6, TdEnemyKind.BRUTE to 3, TdEnemyKind.BOSS to 1),
        listOf(TdEnemyKind.RUNNER to 10, TdEnemyKind.FLYER to 4, TdEnemyKind.BRUTE to 2),
        listOf(TdEnemyKind.RUNNER to 8, TdEnemyKind.BRUTE to 5),
        listOf(TdEnemyKind.RUNNER to 8, TdEnemyKind.FLYER to 8, TdEnemyKind.BRUTE to 3),
        listOf(TdEnemyKind.RUNNER to 10, TdEnemyKind.BRUTE to 6, TdEnemyKind.FLYER to 6),
        listOf(TdEnemyKind.RUNNER to 10, TdEnemyKind.BRUTE to 6, TdEnemyKind.FLYER to 6, TdEnemyKind.BOSS to 1)
    )

    /**
     * The groups of [wave]. Waves 1 to 10 are the authored table. Past 10 (Endless only) the table
     * repeats, each lap bigger: every group grows by 10 percent per wave past 10, and a boss lap
     * (waves 15, 20, 25 ...) brings one more boss every ten waves.
     */
    fun groups(wave: Int): List<Pair<TdEnemyKind, Int>> {
        val w = wave.coerceAtLeast(1)
        if (w <= TABLE.size) return TABLE[w - 1]
        val row = TABLE[(w - 1) % TABLE.size]
        val grow = 1f + 0.10f * (w - TABLE.size)
        return row.map { (kind, n) ->
            if (kind == TdEnemyKind.BOSS) kind to (1 + (w - 1) / TABLE.size) else kind to (n * grow).roundToInt().coerceAtLeast(1)
        }
    }

    /**
     * The spawn order of one wave: groups are interleaved round-robin so runners, brutes and flyers
     * arrive mixed, and the boss (never scaled) walks out about 40 percent of the way in. Counts of
     * non-boss groups scale with [countMultiplier] (at least one of each group stays).
     */
    fun spawnOrder(wave: Int, countMultiplier: Float): List<TdEnemyKind> {
        val groups = groups(wave)
        val queues = groups.filter { it.first != TdEnemyKind.BOSS }.map { (kind, n) ->
            ArrayDeque<TdEnemyKind>().also { q -> repeat((n * countMultiplier).roundToInt().coerceAtLeast(1)) { q.add(kind) } }
        }
        val out = ArrayList<TdEnemyKind>()
        while (queues.any { it.isNotEmpty() }) {
            for (q in queues) if (q.isNotEmpty()) out.add(q.removeFirst())
        }
        val bosses = groups.filter { it.first == TdEnemyKind.BOSS }.sumOf { it.second }
        if (bosses > 0) out.addAll((out.size * 0.4f).roundToInt(), List(bosses) { TdEnemyKind.BOSS })
        return out
    }

    fun hasBoss(wave: Int): Boolean = groups(wave).any { it.first == TdEnemyKind.BOSS }
}

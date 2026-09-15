package com.gamesuite.games.towerdefence

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * README Roadmap item 25 — the last item on the original new-games batch
 * (`docs/NEW_GAMES_BRAINSTORM.md`). Architecture settled in `docs/TOWER_DEFENCE_ADR.md`
 * (Compose `Canvas` + `withFrameNanos`, the same `tick(dtSeconds)` shape
 * [com.gamesuite.games.airhockey.AirHockeyGame]/[com.gamesuite.games.breakout.BreakoutGame]
 * already use in production, validated by a real on-device stress test at 130+ entities).
 * Gameplay settled in `docs/TOWER_DEFENCE_DESIGN.md` — every design call below traces to a
 * specific decision in that doc, not an improvisation.
 *
 * Coordinates: normalized 0f..1f on both axes, y=0 top / y=1 bottom, same convention as
 * AirHockeyGame/BreakoutGame — the UI multiplies by canvas width/height when drawing.
 *
 * PATHING: fixed path, no real-time pathfinding — enemies `lerp` along [TowerDefenceLevel.path]
 * via [positionAlongPath], the exact movement shape the ADR's own stress test already validated
 * on-device. Towers are placed only in a level's designated [TowerDefenceLevel.towerZones].
 *
 * LEVELS: 3 hand-designed fixed layouts ([LEVELS]) — no procedural level generation, the same
 * "fixed template, don't attempt full procedural generation" idiom `docs/KAKURO_DESIGN.md`'s own
 * run-topology decision established for an analogous "this half of generation is a real design
 * skill, not an algorithm" problem. 3 is the low end of the design doc's own "3-5" range — an
 * honest-MVP choice matching this batch's "1 tower type first" lean-scope instinct, not a
 * shortfall; more levels are a real, cheap-to-add future direction (just more path/zone data)
 * once this first version is played.
 *
 * DIFFICULTY: EASY/MEDIUM/HARD scales enemy HP/speed/count and starting gold — see
 * [difficultyConfig]/[waveEnemyHp]/[waveEnemySpeed]/[waveEnemyCount]. Starting lives
 * ([STARTING_LIVES]) stay CONSTANT across every tier, per the design doc's own explicit call —
 * difficulty makes the waves harder to survive, not the margin for error narrower on top of
 * that. Level choice ([level]) is a fully separate axis from [difficulty], settable
 * independently, same "pre-set by the UI before startMatch()" idiom as every other engine's own
 * `difficulty` var.
 *
 * ECONOMY: exactly 1 tower type ([placeTower]), upgradeable ([upgradeTower]) — gold earned per
 * kill (not per wave), spent on placing new towers or upgrading existing ones. No branching
 * upgrade trees: one upgrade action per tower, cost growing geometrically
 * ([UPGRADE_COST_GROWTH]) up to [MAX_UPGRADE_LEVEL].
 *
 * PAUSE: a genuinely different shape from every other real-time game's own `pause()`/`resume()`
 * in this app (AirHockeyGame/BreakoutGame's are empty no-ops — see BreakoutGame's own KDoc for
 * why that's the right call THERE). Tower Defence's strategic, think-under-pressure pacing makes
 * an actual "freeze the simulation, review the board, place/upgrade towers" pause a real genre
 * expectation, per the design doc. [TowerDefenceState.paused] is checked by [tick] and returns
 * early on it — but deliberately NOT by [placeTower]/[upgradeTower], since reviewing the board and
 * acting on it while paused is the entire point. [pause]/[resume] (the GameModule lifecycle hooks,
 * called on app backgrounding) and the player-facing [togglePause] (an in-game Pause button) both
 * drive this SAME flag — backgrounding the app while mid-wave leaves the simulation frozen
 * exactly where the player left it, rather than silently resuming and losing lives off-screen.
 *
 * WIN/LOSS: survive [TOTAL_WAVES] waves with lives above 0 to win; lives hitting 0 ends the run
 * immediately, even mid-wave. Both are a per-run [TowerDefenceRunResult], distinct from
 * [matchOver] (the whole session ending via [leaveSession]) — same "per-round flag vs.
 * session-level flag" split every solo game in this batch uses (e.g. BreakoutState.gameOver vs.
 * BreakoutGame.matchOver).
 *
 * STATS: furthest wave reached, tracked per (level, difficulty) via [TowerDefenceStatsStore] —
 * the same "arcade high score" shape [com.gamesuite.games.breakout.BreakoutStatsStore] already
 * established, adapted from points to waves. "Furthest wave reached" is [TowerDefenceState.waveNumber]
 * at the moment a run ends (win OR loss) — dying partway through wave 6 still counts as reaching
 * wave 6, matching the design doc's own "how far did you get" framing rather than only counting
 * fully-cleared waves. Updates on EVERY run regardless of outcome, same "best attempt ever" idiom
 * BreakoutStatsStore's own best-score already follows.
 *
 * `matchOver` guard built into every gameplay-mutating method from the start (not found after the
 * fact) — the design doc's own explicit call-out, the same now-well-known bug pattern every prior
 * engine in this batch has needed.
 *
 * SCOPE CUTS (honest MVP, see `docs/TOWER_DEFENCE_DESIGN.md`'s own "Deliberate scope cuts"):
 * exactly 1 tower type (not 3), no branching upgrade trees, no player-editable maze/pathfinding,
 * no procedural level generation, no enemy variety beyond stat scaling. All explicitly flagged in
 * the design doc for future revisit, not oversights.
 */
class TowerDefenceGame : GameModule {
    override val gameId = "tower-defence"
    override val displayName = "Tower Defence"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    companion object {
        const val STARTING_LIVES = 20
        const val TOTAL_WAVES = 10

        const val BASE_ENEMY_HP = 40f
        const val ENEMY_HP_GROWTH_PER_WAVE = 12f
        const val BASE_ENEMY_SPEED = 0.09f // normalized units/second
        const val ENEMY_SPEED_GROWTH_PER_WAVE = 0.003f
        const val BASE_ENEMY_COUNT = 6
        const val ENEMY_COUNT_GROWTH_PER_WAVE = 1
        const val ENEMY_GOLD_REWARD = 10
        const val SPAWN_INTERVAL_SECONDS = 0.8f
        const val INTER_WAVE_SECONDS = 5f

        const val BASE_TOWER_COST = 50
        const val UPGRADE_BASE_COST = 40
        const val UPGRADE_COST_GROWTH = 1.6f
        const val MAX_UPGRADE_LEVEL = 5
        const val BASE_TOWER_RANGE = 0.22f // normalized units
        const val RANGE_PER_UPGRADE = 0.02f
        const val BASE_TOWER_DAMAGE = 18f
        const val DAMAGE_PER_UPGRADE = 9f
        const val BASE_TOWER_COOLDOWN_SECONDS = 0.65f
        const val COOLDOWN_REDUCTION_PER_UPGRADE = 0.04f
        const val MIN_TOWER_COOLDOWN_SECONDS = 0.25f
        const val PROJECTILE_SPEED = 1.1f // normalized units/second
        const val PROJECTILE_HIT_EPSILON = 0.02f

        fun towerRange(upgradeLevel: Int): Float = BASE_TOWER_RANGE + RANGE_PER_UPGRADE * (upgradeLevel - 1)
        fun towerDamage(upgradeLevel: Int): Float = BASE_TOWER_DAMAGE + DAMAGE_PER_UPGRADE * (upgradeLevel - 1)
        fun towerCooldownSeconds(upgradeLevel: Int): Float =
            (BASE_TOWER_COOLDOWN_SECONDS - COOLDOWN_REDUCTION_PER_UPGRADE * (upgradeLevel - 1))
                .coerceAtLeast(MIN_TOWER_COOLDOWN_SECONDS)
        fun upgradeCost(currentLevel: Int): Int =
            (UPGRADE_BASE_COST * UPGRADE_COST_GROWTH.pow(currentLevel - 1)).roundToInt()

        /**
         * Position along [path] at [distanceTraveled] normalized units from the start (waypoint
         * 0), or null once that distance exceeds the path's total length — the enemy has reached
         * the end ("leaked"). The one shared geometry helper both enemy movement and tower
         * targeting read from, so a leaked enemy is judged identically everywhere.
         */
        fun positionAlongPath(path: List<Offset>, distanceTraveled: Float): Offset? {
            var remaining = distanceTraveled
            for (i in 0 until path.size - 1) {
                val a = path[i]
                val b = path[i + 1]
                val segmentLength = (b - a).getDistance()
                if (remaining <= segmentLength) {
                    val t = if (segmentLength > 0f) remaining / segmentLength else 0f
                    return Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                }
                remaining -= segmentLength
            }
            return null
        }

        // -- Hand-designed levels -- see the class KDoc's LEVELS section. --

        val LEVEL_SWITCHBACK = TowerDefenceLevel(
            id = "switchback",
            displayName = "Switchback",
            path = listOf(
                Offset(0.02f, 0.12f), Offset(0.82f, 0.12f),
                Offset(0.82f, 0.42f), Offset(0.18f, 0.42f),
                Offset(0.18f, 0.72f), Offset(0.92f, 0.72f),
                Offset(0.92f, 0.95f)
            ),
            towerZones = listOf(
                Offset(0.20f, 0.24f), Offset(0.45f, 0.24f), Offset(0.68f, 0.24f),
                Offset(0.35f, 0.55f), Offset(0.60f, 0.55f),
                Offset(0.35f, 0.85f), Offset(0.60f, 0.85f), Offset(0.80f, 0.85f)
            )
        )

        val LEVEL_SPIRAL = TowerDefenceLevel(
            id = "spiral",
            displayName = "Spiral Keep",
            path = listOf(
                Offset(0.05f, 0.05f), Offset(0.95f, 0.05f), Offset(0.95f, 0.95f),
                Offset(0.15f, 0.95f), Offset(0.15f, 0.25f), Offset(0.75f, 0.25f),
                Offset(0.75f, 0.75f), Offset(0.35f, 0.75f), Offset(0.35f, 0.45f),
                Offset(0.55f, 0.45f)
            ),
            towerZones = listOf(
                Offset(0.5f, 0.16f), Offset(0.84f, 0.5f), Offset(0.5f, 0.84f),
                Offset(0.26f, 0.5f), Offset(0.45f, 0.36f), Offset(0.64f, 0.36f),
                Offset(0.64f, 0.64f), Offset(0.45f, 0.6f)
            )
        )

        val LEVEL_GAUNTLET = TowerDefenceLevel(
            id = "gauntlet",
            displayName = "Zigzag Gauntlet",
            path = listOf(
                Offset(0.02f, 0.5f), Offset(0.3f, 0.5f), Offset(0.3f, 0.08f),
                Offset(0.5f, 0.08f), Offset(0.5f, 0.92f), Offset(0.7f, 0.92f),
                Offset(0.7f, 0.5f), Offset(0.98f, 0.5f)
            ),
            towerZones = listOf(
                Offset(0.15f, 0.3f), Offset(0.15f, 0.7f),
                Offset(0.4f, 0.3f), Offset(0.4f, 0.7f),
                Offset(0.6f, 0.3f), Offset(0.6f, 0.7f),
                Offset(0.85f, 0.3f), Offset(0.85f, 0.7f)
            )
        )

        val LEVELS = listOf(LEVEL_SWITCHBACK, LEVEL_SPIRAL, LEVEL_GAUNTLET)
    }

    data class TowerDefenceLevel(
        val id: String,
        val displayName: String,
        val path: List<Offset>,
        val towerZones: List<Offset>
    )

    enum class TowerDefenceRunResult { WON, LOST }

    data class TowerDefenceEnemy(
        val id: Int,
        val distanceTraveled: Float,
        val hp: Float,
        val maxHp: Float,
        val speed: Float,
        val goldReward: Int
    )

    data class TowerDefenceTower(
        val id: Int,
        val zoneIndex: Int,
        val upgradeLevel: Int,
        val cooldownRemaining: Float
    )

    data class TowerDefenceProjectile(
        val id: Int,
        val position: Offset,
        val targetEnemyId: Int,
        val damage: Float
    )

    /** A kill's own position and a monotonic [seq] — the UI keys a one-shot effect (a particle
     *  burst) off [seq] changing, same "seq-numbered event, persists until the next one"
     *  idiom BreakoutGame's own `lastBrickBroken`/`lastPaddleBounce` already use. At most one
     *  captured per tick (see [tick]'s phase 4) if multiple kills land in the same frame — the
     *  same acceptable simplification BreakoutGame's own KDoc already documents for its events. */
    data class TowerDefenceEnemyDeathEvent(val seq: Long, val position: Offset)

    data class TowerDefenceState(
        val level: TowerDefenceLevel,
        val difficulty: CpuDifficulty,
        val waveNumber: Int,
        val totalWaves: Int,
        val lives: Int,
        val gold: Int,
        val enemies: List<TowerDefenceEnemy> = emptyList(),
        val towers: List<TowerDefenceTower> = emptyList(),
        val projectiles: List<TowerDefenceProjectile> = emptyList(),
        val enemiesRemainingToSpawn: Int = 0,
        val spawnCooldown: Float = 0f,
        /** Counts down before [waveNumber]'s wave begins spawning; > 0 means "waiting to
         *  start" (the window the design doc means by "review the board, place/upgrade
         *  towers" between waves). */
        val interWaveCooldown: Float,
        val paused: Boolean = false,
        val runResult: TowerDefenceRunResult? = null,
        /** The most recent enemy kill's position, for the UI's own particle-burst effect —
         *  see [TowerDefenceEnemyDeathEvent]'s own KDoc. */
        val lastEnemyDeath: TowerDefenceEnemyDeathEvent? = null,
        /** Bumped by every [startMatch]/[playAgain] — lets the UI reliably detect "a new run
         *  just started/ended" without relying on a Boolean transition that [playAgain] could
         *  otherwise flip past unobserved. */
        val runSeq: Int = 0
    ) {
        val waveInProgress: Boolean get() = enemiesRemainingToSpawn > 0 || enemies.isNotEmpty()
        val runOver: Boolean get() = runResult != null
    }

    data class DifficultyConfig(
        val startingGold: Int,
        val enemyHpMultiplier: Float,
        val enemySpeedMultiplier: Float,
        val enemyCountMultiplier: Float
    )

    private val difficultyConfig: Map<CpuDifficulty, DifficultyConfig> = mapOf(
        CpuDifficulty.EASY to DifficultyConfig(startingGold = 150, enemyHpMultiplier = 0.75f, enemySpeedMultiplier = 0.85f, enemyCountMultiplier = 0.8f),
        CpuDifficulty.MEDIUM to DifficultyConfig(startingGold = 100, enemyHpMultiplier = 1.0f, enemySpeedMultiplier = 1.0f, enemyCountMultiplier = 1.0f),
        CpuDifficulty.HARD to DifficultyConfig(startingGold = 70, enemyHpMultiplier = 1.4f, enemySpeedMultiplier = 1.2f, enemyCountMultiplier = 1.3f)
    )

    val state = mutableStateOf(
        TowerDefenceState(
            level = LEVELS.first(), difficulty = CpuDifficulty.MEDIUM, waveNumber = 1,
            totalWaves = TOTAL_WAVES, lives = STARTING_LIVES, gold = 100, interWaveCooldown = INTER_WAVE_SECONDS
        )
    )
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(), same
     *  idiom as every other engine's own `difficulty` var. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Pre-set by the UI's level-select control before startMatch() — a fully separate axis from
     *  [difficulty], per the class KDoc's DIFFICULTY section. */
    var level: TowerDefenceLevel = LEVELS.first()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextEnemyId = 0
    private var nextTowerId = 0
    private var nextProjectileId = 0
    private var eventSeq = 0L

    /** The furthest wave reached across any run THIS SESSION (across any [playAgain] restarts),
     *  reported by [leaveSession] — same "session-level tally reported on the way out" shape as
     *  BreakoutGame's own `bestScoreThisSession`. */
    private var bestWaveThisSession = 0
    private var everWonThisSession = false

    /** Tracks whether the CURRENT [TowerDefenceState.paused]=true was caused by [pause] (an app
     *  backgrounding event) rather than by the player's own [togglePause] — see [pause]/[resume]'s
     *  own KDoc for why this exists: without it, [resume] can't tell a manual pause it must leave
     *  alone apart from a lifecycle pause it's responsible for clearing. */
    private var pausedByLifecycle = false

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val cfg = difficultyConfig.getValue(difficulty)
        nextEnemyId = 0; nextTowerId = 0; nextProjectileId = 0; eventSeq = 0L
        state.value = TowerDefenceState(
            level = level,
            difficulty = difficulty,
            waveNumber = 1,
            totalWaves = TOTAL_WAVES,
            lives = STARTING_LIVES,
            gold = cfg.startingGold,
            interWaveCooldown = INTER_WAVE_SECONDS,
            runSeq = state.value.runSeq + 1
        )
        matchOver.value = false
        pausedByLifecycle = false
    }

    /** See the class KDoc's PAUSE section — unlike Air Hockey/Breakout's empty no-ops, these
     *  drive the same [TowerDefenceState.paused] flag [togglePause] does. Only takes ownership of
     *  the pause (recording [pausedByLifecycle]) when the game wasn't ALREADY paused — if the
     *  player had already paused manually via [togglePause], backgrounding the app must not let
     *  the matching [resume] silently clear that manual pause later. */
    override fun pause() {
        val s = state.value
        if (matchOver.value || s.runOver) return
        if (s.paused) return
        pausedByLifecycle = true
        state.value = s.copy(paused = true)
    }

    /** Only clears [TowerDefenceState.paused] if THIS lifecycle hook (via [pause]) was the one
     *  that set it — a pause the player set manually via [togglePause] is left alone, so
     *  foregrounding the app after a background/foreground cycle never undoes an intentional
     *  pause. See [pause]'s own KDoc and the class KDoc's PAUSE section. */
    override fun resume() {
        val s = state.value
        if (matchOver.value || s.runOver) return
        if (!pausedByLifecycle) return
        pausedByLifecycle = false
        state.value = s.copy(paused = false)
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** The in-game Pause button — see the class KDoc's PAUSE section for why this and the
     *  lifecycle [pause]/[resume] hooks deliberately drive the same flag. Toggling manually always
     *  takes ownership of the flag away from [pausedByLifecycle] — a manual unpause must never be
     *  re-paused by a stale, already-handled backgrounding event, and a manual pause must never be
     *  mistaken by [resume] for one IT is responsible for clearing. */
    fun togglePause() {
        val s = state.value
        if (matchOver.value || s.runOver) return
        pausedByLifecycle = false
        state.value = s.copy(paused = !s.paused)
    }

    /** Deliberately NOT guarded by [TowerDefenceState.paused] — see the class KDoc's PAUSE
     *  section: placing/upgrading towers while paused is the entire point of pausing. */
    fun placeTower(zoneIndex: Int) {
        val s = state.value
        if (matchOver.value || s.runOver) return
        if (zoneIndex !in s.level.towerZones.indices) return
        if (s.towers.any { it.zoneIndex == zoneIndex }) return
        if (s.gold < BASE_TOWER_COST) return
        state.value = s.copy(
            gold = s.gold - BASE_TOWER_COST,
            towers = s.towers + TowerDefenceTower(id = nextTowerId++, zoneIndex = zoneIndex, upgradeLevel = 1, cooldownRemaining = 0f)
        )
    }

    fun upgradeTower(towerId: Int) {
        val s = state.value
        if (matchOver.value || s.runOver) return
        val tower = s.towers.firstOrNull { it.id == towerId } ?: return
        if (tower.upgradeLevel >= MAX_UPGRADE_LEVEL) return
        val cost = upgradeCost(tower.upgradeLevel)
        if (s.gold < cost) return
        state.value = s.copy(
            gold = s.gold - cost,
            towers = s.towers.map { if (it.id == towerId) it.copy(upgradeLevel = it.upgradeLevel + 1) else it }
        )
    }

    private fun waveEnemyCount(waveNumber: Int, cfg: DifficultyConfig): Int =
        (((BASE_ENEMY_COUNT + ENEMY_COUNT_GROWTH_PER_WAVE * (waveNumber - 1)) * cfg.enemyCountMultiplier).roundToInt()).coerceAtLeast(1)

    private fun waveEnemyHp(waveNumber: Int, cfg: DifficultyConfig): Float =
        (BASE_ENEMY_HP + ENEMY_HP_GROWTH_PER_WAVE * (waveNumber - 1)) * cfg.enemyHpMultiplier

    private fun waveEnemySpeed(waveNumber: Int, cfg: DifficultyConfig): Float =
        (BASE_ENEMY_SPEED + ENEMY_SPEED_GROWTH_PER_WAVE * (waveNumber - 1)) * cfg.enemySpeedMultiplier

    /**
     * Called every frame from the UI's game loop with elapsed seconds — same shape as
     * BreakoutGame.tick(dtSeconds). A no-op while the session or this run has already ended, or
     * while [TowerDefenceState.paused] — see the class KDoc's PAUSE section.
     *
     * Phases run in a fixed order within one tick, each reading the previous phase's already-
     * updated local state: (0) count down to the next wave's start, (1) spawn this wave's
     * enemies, (2) move enemies and resolve leaks/lives, (3) towers target the furthest-along
     * in-range enemy and fire, (4) move projectiles and resolve impacts/kills/gold, (5) check
     * whether the just-cleared wave ends the run (WON) or advances to the next one.
     *
     * Targeting rule ("First" in genre terms — whichever in-range enemy has traveled furthest,
     * i.e. is closest to leaking) was chosen over plain nearest-to-tower distance after this
     * engine's own test suite caught the latter's real failure mode: towers clustered near a
     * level's spawn point would keep re-targeting each freshly-spawned enemy (physically
     * closest) while an older runner already deep into the path walked past unmolested toward
     * the exit. `docs/TOWER_DEFENCE_ADR.md`'s own "nearest-in-range" phrase describes that
     * throwaway stress-test pilot's targeting (built only to measure frame time, already
     * removed), not a gameplay commitment for the real engine.
     */
    fun tick(dtSeconds: Float) {
        val s = state.value
        if (matchOver.value || s.runOver || s.paused) return
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        val cfg = difficultyConfig.getValue(s.difficulty)

        var lives = s.lives
        var gold = s.gold
        var enemies = s.enemies
        var towers = s.towers
        var projectiles = s.projectiles
        var enemiesRemainingToSpawn = s.enemiesRemainingToSpawn
        var spawnCooldown = s.spawnCooldown
        var interWaveCooldown = s.interWaveCooldown
        var waveNumber = s.waveNumber
        var runResult: TowerDefenceRunResult? = null
        var enemyDeath = s.lastEnemyDeath

        // Phase 0: count down to the next wave's start.
        if (enemiesRemainingToSpawn == 0 && enemies.isEmpty() && interWaveCooldown > 0f) {
            interWaveCooldown = (interWaveCooldown - dt).coerceAtLeast(0f)
            if (interWaveCooldown <= 0f) {
                enemiesRemainingToSpawn = waveEnemyCount(waveNumber, cfg)
                spawnCooldown = 0f
            }
        }

        // Phase 1: spawn this wave's enemies, one per SPAWN_INTERVAL_SECONDS.
        if (enemiesRemainingToSpawn > 0) {
            spawnCooldown -= dt
            while (spawnCooldown <= 0f && enemiesRemainingToSpawn > 0) {
                val hp = waveEnemyHp(waveNumber, cfg)
                enemies = enemies + TowerDefenceEnemy(
                    id = nextEnemyId++, distanceTraveled = 0f, hp = hp, maxHp = hp,
                    speed = waveEnemySpeed(waveNumber, cfg), goldReward = ENEMY_GOLD_REWARD
                )
                enemiesRemainingToSpawn--
                spawnCooldown += SPAWN_INTERVAL_SECONDS
            }
        }

        // Phase 2: move enemies; a leaked enemy (past the path's end) costs one life.
        val stillOnPath = mutableListOf<TowerDefenceEnemy>()
        for (enemy in enemies) {
            val moved = enemy.copy(distanceTraveled = enemy.distanceTraveled + enemy.speed * dt)
            if (positionAlongPath(s.level.path, moved.distanceTraveled) == null) {
                lives -= 1
            } else {
                stillOnPath.add(moved)
            }
        }
        enemies = stillOnPath
        if (lives <= 0) {
            lives = 0
            runResult = TowerDefenceRunResult.LOST
        }

        // Phase 3: towers target the furthest-along (closest to leaking) in-range enemy and fire
        // on cooldown -- see the class KDoc's [tick] section for why this beats plain nearest-
        // to-tower distance.
        if (runResult == null) {
            val updatedTowers = mutableListOf<TowerDefenceTower>()
            val newProjectiles = mutableListOf<TowerDefenceProjectile>()
            val enemyPositions = enemies.mapNotNull { e -> positionAlongPath(s.level.path, e.distanceTraveled)?.let { e to it } }
            for (tower in towers) {
                var cooldown = tower.cooldownRemaining - dt
                if (cooldown <= 0f) {
                    val zonePos = s.level.towerZones[tower.zoneIndex]
                    val range = towerRange(tower.upgradeLevel)
                    val target = enemyPositions
                        .filter { (_, pos) -> (pos - zonePos).getDistance() <= range }
                        .maxByOrNull { (enemy, _) -> enemy.distanceTraveled }
                    if (target != null) {
                        newProjectiles.add(
                            TowerDefenceProjectile(
                                id = nextProjectileId++, position = zonePos,
                                targetEnemyId = target.first.id, damage = towerDamage(tower.upgradeLevel)
                            )
                        )
                        cooldown = towerCooldownSeconds(tower.upgradeLevel)
                    }
                }
                updatedTowers.add(tower.copy(cooldownRemaining = cooldown.coerceAtLeast(0f)))
            }
            towers = updatedTowers
            projectiles = projectiles + newProjectiles
        }

        // Phase 4: move projectiles; resolve impacts, kills, and gold. A projectile whose target
        // already died (to another projectile this same tick) or leaked is simply dropped -- a
        // wasted shot, not an error.
        if (runResult == null) {
            val enemyById = enemies.associateByTo(LinkedHashMap()) { it.id }
            val remainingProjectiles = mutableListOf<TowerDefenceProjectile>()
            for (projectile in projectiles) {
                val target = enemyById[projectile.targetEnemyId]
                val targetPos = target?.let { positionAlongPath(s.level.path, it.distanceTraveled) }
                if (target == null || targetPos == null) continue
                val toTarget = targetPos - projectile.position
                val distance = toTarget.getDistance()
                if (distance <= PROJECTILE_HIT_EPSILON) {
                    val newHp = target.hp - projectile.damage
                    if (newHp <= 0f) {
                        gold += target.goldReward
                        enemyById.remove(target.id)
                        eventSeq++
                        enemyDeath = TowerDefenceEnemyDeathEvent(eventSeq, targetPos)
                    } else {
                        enemyById[target.id] = target.copy(hp = newHp)
                    }
                } else {
                    // Clamped to the remaining distance -- an adversarial-review finding, confirmed
                    // by simulation: an uncapped step of exactly PROJECTILE_SPEED*dt could overshoot
                    // past a target sitting between PROJECTILE_HIT_EPSILON and the step's own
                    // magnitude away, landing on the far side at the same offset it started from.
                    // For a near-stationary target that's a period-2 orbit that can stay outside the
                    // hit epsilon far longer than one tick (a real enemy's own forward movement
                    // eventually breaks the symmetry, but only after an observable, avoidable delay --
                    // see TowerDefenceGameTest's own regression test).
                    val stepMagnitude = minOf(PROJECTILE_SPEED * dt, distance)
                    val step = toTarget * (stepMagnitude / distance)
                    remainingProjectiles.add(projectile.copy(position = projectile.position + step))
                }
            }
            enemies = enemyById.values.toList()
            projectiles = remainingProjectiles
        }

        // Phase 5: this wave is fully cleared (nothing left to spawn or fight) -- win, or advance.
        if (runResult == null && enemiesRemainingToSpawn == 0 && enemies.isEmpty() && interWaveCooldown <= 0f) {
            if (waveNumber >= s.totalWaves) {
                runResult = TowerDefenceRunResult.WON
            } else {
                waveNumber += 1
                interWaveCooldown = INTER_WAVE_SECONDS
            }
        }

        if (runResult == TowerDefenceRunResult.WON) everWonThisSession = true
        if (runResult != null) bestWaveThisSession = maxOf(bestWaveThisSession, waveNumber)

        state.value = s.copy(
            lives = lives, gold = gold, enemies = enemies, towers = towers, projectiles = projectiles,
            enemiesRemainingToSpawn = enemiesRemainingToSpawn, spawnCooldown = spawnCooldown,
            interWaveCooldown = interWaveCooldown, waveNumber = waveNumber, runResult = runResult,
            lastEnemyDeath = enemyDeath
        )
    }

    /** Called from the finished-run panel's "Play Again" button — restarts the SAME level and
     *  difficulty, keeps [bestWaveThisSession] running. A no-op unless the current run has
     *  actually ended, same idempotency guard every solo game's own `playAgain()` has. */
    fun playAgain() {
        if (matchOver.value || !state.value.runOver) return
        startMatch()
    }

    /** Called from the finished-run panel's (or in-progress screen's) "Back to Menu" button --
     *  ends the whole session, reporting [bestWaveThisSession] -- same shape as
     *  BreakoutGame.leaveSession() reporting its own bestScoreThisSession. */
    fun leaveSession() {
        if (matchOver.value) return
        val s = state.value
        if (s.runOver) bestWaveThisSession = maxOf(bestWaveThisSession, s.waveNumber)
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(playerId = player.playerId, score = bestWaveThisSession, isWinner = everWonThisSession)
            ) else emptyList()
        )
        endMatch(result)
    }
}

package com.gamesuite.games.whackamole

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

enum class WhackPhase { COUNTDOWN, PLAY, OVER }

enum class WhackKind { IGNORED, HIT, BONUS, DECOY, MISS }

/** What a whack did: [delta] is the score change, [spawnId] the critter it landed on (-1 for a miss), [combo] the streak afterwards. */
data class WhackResult(val kind: WhackKind, val delta: Int = 0, val spawnId: Int = -1, val combo: Int = 0, val hole: Int = -1)

data class WhackEvent(val serial: Int, val kind: WhackKind, val seat: Int, val delta: Int, val hole: Int, val combo: Int)

data class WhackConfig(
    val seats: Int,
    val durationMs: Int,
    val grid: Int,
    val relaxed: Boolean,
    val oneSwitch: Boolean,
    val seed: Long,
    val cpuSeats: Set<Int>,
    val level: CpuDifficulty
) {
    init {
        require(seats in WhackRules.MIN_SEATS..WhackRules.MAX_SEATS) { "seats" }
        require(grid in WhackRules.GRID_CHOICES) { "grid" }
        require(durationMs in 10_000..600_000) { "duration" }
    }

    val holes: Int get() = grid * grid
    val pace: Double get() = if (oneSwitch) WhackRules.SWITCH_PACE else if (relaxed) WhackRules.RELAXED_PACE else 1.0
}

/** What one hole looks like to one player at a moment: [pop] is 0 (hidden) to 1 (fully up); [hitAgeMs] is >= 0 for a critter this player just whacked. */
data class HoleView(val critter: Critter, val pop: Float, val hitAgeMs: Int, val spawnId: Int)

/**
 * The pure rules of Whack-a-Mole Race: every zone is its own grid but they all follow ONE seeded [spawns] list, so
 * nobody gets luckier critters. Each player's score depends only on their own whacks (and the shared list). The
 * engine has no clock of its own: every method is told "now"; a whack carries the time of the touch.
 *
 * Scoring: a mole is worth 10, a golden bonus 30, both times a combo multiplier (x2 from five whacks in a row, x3
 * from ten); an urchin decoy costs 20 (never below 0) and breaks the combo; a whack on an empty hole only breaks the
 * combo. RELAXED removes every penalty (decoys score nothing, misses cost nothing), slows the critters and keeps them
 * up longer. ONE-SWITCH replaces aiming with a cursor that sweeps the holes: one press whacks the hole it is on.
 */
class WhackEngine(val config: WhackConfig) {
    var phase: WhackPhase = WhackPhase.OVER
        private set

    val spawns: List<WhackSpawn> = WhackRules.generate(config.seed, config.grid, config.durationMs, config.pace)
    private val byHole: List<List<WhackSpawn>> = List(config.holes) { h -> spawns.filter { it.hole == h } }

    val score = IntArray(config.seats)
    val combo = IntArray(config.seats)
    val bestCombo = IntArray(config.seats)
    val hits = IntArray(config.seats)
    val bonuses = IntArray(config.seats)
    val decoyHits = IntArray(config.seats)
    val misses = IntArray(config.seats)

    /** Per seat, per spawn id: -1 while unresolved, else the play-relative ms of the whack that resolved it. */
    private val takenAt = Array(config.seats) { IntArray(spawns.size) { -1 } }
    private val lastTap = Array(config.seats) { IntArray(config.holes) { -1_000_000 } }

    var playStartAt = 0L
        private set
    private var countdownEndAt = 0L
    private var clockNow = 0L

    private class Act(val seat: Int, val at: Int, val hole: Int)

    private val acts = ArrayList<Act>()
    private var nextAct = 0

    private val log = ArrayDeque<WhackEvent>()
    var eventSerial = 0
        private set

    fun eventsSince(afterSerial: Int): List<WhackEvent> = log.filter { it.serial > afterSerial }

    private fun emit(kind: WhackKind, seat: Int, delta: Int, hole: Int, combo: Int) {
        eventSerial++
        log.addLast(WhackEvent(eventSerial, kind, seat, delta, hole, combo))
        while (log.size > 80) log.removeFirst()
    }

    fun startMatch(now: Long) {
        score.fill(0); combo.fill(0); bestCombo.fill(0); hits.fill(0); bonuses.fill(0); decoyHits.fill(0); misses.fill(0)
        takenAt.forEach { it.fill(-1) }
        lastTap.forEach { it.fill(-1_000_000) }
        log.clear()
        clockNow = now
        phase = WhackPhase.COUNTDOWN
        countdownEndAt = now + WhackRules.COUNTDOWN_MS
        playStartAt = countdownEndAt
        acts.clear(); nextAct = 0
        for (seat in config.cpuSeats) if (seat in 0 until config.seats) planCpu(seat)
        acts.sortWith(compareBy({ it.at }, { it.seat }))
    }

    private fun planCpu(seat: Int) {
        val p = WhackRules.profile(config.level)
        val r = Random(config.seed * 31 + 977L * (seat + 1))
        class Cand(val at: Int, val sp: WhackSpawn)
        val cands = ArrayList<Cand>()
        for (sp in spawns) {
            if (sp.critter == Critter.DECOY) {
                if (r.nextDouble() < p.mistake) cands += Cand(sp.startMs + (p.reactMs * 0.8).toInt(), sp)
            } else if (r.nextDouble() < p.notice) {
                val react = WhackRules.gauss(r, p.reactMs.toDouble(), p.reactSd.toDouble()).coerceAtLeast(p.floorMs.toDouble()).toInt()
                cands += Cand(sp.startMs + react, sp)
            }
        }
        cands.sortBy { it.at }
        var lastAt = -1_000_000
        for (c in cands) {
            var at = c.at
            if (at - lastAt < p.gapMs) at = lastAt + p.gapMs + r.nextInt(60)
            if (at >= c.sp.endMs - 40 || at >= config.durationMs) continue
            acts += Act(seat, at, c.sp.hole)
            lastAt = at
        }
    }

    /** The computer seat's planned whacks as (play-relative ms, hole), in time order (read-only; the engine plays them itself). */
    fun plannedWhacks(seat: Int): List<Pair<Int, Int>> = acts.filter { it.seat == seat }.map { it.at to it.hole }

    /** Advances to [now]: leaves the countdown, plays the computers' whacks that are due, ends the round. */
    fun tick(now: Long) {
        if (now > clockNow) clockNow = now
        if (phase == WhackPhase.COUNTDOWN && now >= countdownEndAt) phase = WhackPhase.PLAY
        if (phase == WhackPhase.PLAY) {
            while (nextAct < acts.size && playStartAt + acts[nextAct].at <= now) {
                val a = acts[nextAct++]
                apply(a.seat, a.hole, a.at)
            }
            if (now >= playStartAt + config.durationMs) {
                phase = WhackPhase.OVER
                emit(WhackKind.IGNORED, -1, 0, -1, 0)
            }
        }
    }

    fun timeLeftMs(now: Long): Long = when (phase) {
        WhackPhase.COUNTDOWN -> config.durationMs.toLong()
        WhackPhase.PLAY -> (playStartAt + config.durationMs - now).coerceIn(0L, config.durationMs.toLong())
        WhackPhase.OVER -> 0L
    }

    /** Milliseconds into play at [now] (negative during the countdown). */
    fun relative(now: Long): Long = now - playStartAt

    /**
     * Seat [seat] whacked hole [hole] at [eventTime] (the moment of the touch, the clock of [now]). In one-switch
     * mode [hole] is ignored: the hole under the sweeping cursor at that moment is whacked.
     */
    fun whack(seat: Int, hole: Int, eventTime: Long, now: Long): WhackResult {
        tick(now)
        if (phase != WhackPhase.PLAY || seat !in 0 until config.seats) return WhackResult(WhackKind.IGNORED)
        val rel = (minOf(eventTime, now) - playStartAt).toInt()
        if (rel < 0 || rel >= config.durationMs) return WhackResult(WhackKind.IGNORED)
        val h = if (config.oneSwitch) WhackRules.scanHole(rel.toLong(), config.holes) else hole
        if (h !in 0 until config.holes) return WhackResult(WhackKind.IGNORED)
        return apply(seat, h, rel)
    }

    private fun apply(seat: Int, hole: Int, rel: Int): WhackResult {
        if (rel - lastTap[seat][hole] < WhackRules.DOUBLE_TAP_MS) return WhackResult(WhackKind.IGNORED)
        lastTap[seat][hole] = rel
        val sp = byHole[hole].firstOrNull { rel >= it.startMs && rel < it.endMs && takenAt[seat][it.id] < 0 }
        if (sp == null) {
            misses[seat]++
            if (!config.relaxed) combo[seat] = 0
            emit(WhackKind.MISS, seat, 0, hole, combo[seat])
            return WhackResult(WhackKind.MISS, 0, -1, combo[seat], hole)
        }
        takenAt[seat][sp.id] = rel
        if (sp.critter == Critter.DECOY) {
            decoyHits[seat]++
            val delta = if (config.relaxed) 0 else -minOf(-sp.critter.points, score[seat])
            score[seat] += delta
            if (!config.relaxed) combo[seat] = 0
            emit(WhackKind.DECOY, seat, delta, hole, combo[seat])
            return WhackResult(WhackKind.DECOY, delta, sp.id, combo[seat], hole)
        }
        combo[seat]++
        if (combo[seat] > bestCombo[seat]) bestCombo[seat] = combo[seat]
        val delta = sp.critter.points * WhackRules.comboMultiplier(combo[seat])
        score[seat] += delta
        val kind = if (sp.critter == Critter.GOLD) WhackKind.BONUS else WhackKind.HIT
        if (kind == WhackKind.BONUS) bonuses[seat]++ else hits[seat]++
        emit(kind, seat, delta, hole, combo[seat])
        return WhackResult(kind, delta, sp.id, combo[seat], hole)
    }

    /** What [hole] shows seat [seat] at play-relative time [rel], or null when nothing is up there. */
    fun holeView(seat: Int, hole: Int, rel: Long): HoleView? {
        for (sp in byHole[hole]) {
            val taken = takenAt[seat][sp.id]
            if (taken >= 0) {
                val age = (rel - taken).toInt()
                if (age in 0..HIT_SHOW_MS) return HoleView(sp.critter, 1f, age, sp.id)
                continue
            }
            if (rel < sp.startMs) return null
            if (rel >= sp.endMs) continue
            val up = ((rel - sp.startMs).toFloat() / WhackRules.POP_MS).coerceIn(0f, 1f)
            val down = ((sp.endMs - rel).toFloat() / WhackRules.POP_MS).coerceIn(0f, 1f)
            return HoleView(sp.critter, minOf(up, down), -1, sp.id)
        }
        return null
    }

    /** The hole the one-switch cursor is on at play-relative time [rel]. */
    fun scanHoleAt(rel: Long): Int = WhackRules.scanHole(rel, config.holes)

    /** Winning seat (strictly highest score), or -1 for a tie at the top. */
    fun winner(): Int {
        val top = score.max()
        return if (score.count { it == top } == 1) score.indexOf(top) else -1
    }

    companion object {
        /** How long a whacked critter stays on screen in its "bonk" pose. */
        const val HIT_SHOW_MS = 320
    }
}

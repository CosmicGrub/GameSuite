package com.gamesuite.games.fishing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Fixed numbers of the fishing contest (60 steps a second). */
object FishingRules {
    const val STEPS_PER_SECOND = 60
    const val STEP_MS = 1000.0 / STEPS_PER_SECOND
    const val DT = 1.0 / STEPS_PER_SECOND
    const val COLUMNS = 5
    const val BANDS = 4
    const val MAX_SEATS = 4
    val ROUND_CHOICES = listOf(3, 5, 8)

    /** Ticks a turn may wait for the cast before it is lost (doubled by the relaxed assist). */
    const val AIM_TIMEOUT = 60 * 25
    /** The swing meter rises for this long, then falls for as long. */
    const val METER_HALF = 70
    const val CAST_TICKS = 40
    const val NIBBLE_TICKS = 24
    const val BITE_WINDOW = 48
    const val RESULT_TICKS = 150
    const val REEL_TIMEOUT = 60 * 80

    const val T_UP = 0.8
    const val T_DOWN = 0.75
    const val REEL_SPEED = 5.0
    const val DRIFT = 0.4
    const val SLACK_LEVEL = 0.08
    const val SLACK_TICKS = 70
    const val RUSH_WARNING = 22

    fun distanceOf(band: Int): Double = 6.0 + 8.0 * band
    fun meterAt(chargeTicks: Int): Double {
        val c = chargeTicks % (2 * METER_HALF)
        return if (c <= METER_HALF) c.toDouble() / METER_HALF else (2 * METER_HALF - c).toDouble() / METER_HALF
    }
    fun bandOfPower(power: Double): Int = (power * BANDS).toInt().coerceIn(0, BANDS - 1)
}

enum class FishPhase { AIM, CHARGING, CASTING, WAITING, BITE, REELING, RESULT, OVER }

enum class FishOutcome { NONE, CAUGHT, SLACK, SNAPPED, TOO_EARLY, MISSED, TIMED_OUT, GOT_AWAY }

/** One kind of fish. Weights in grams. [depthWeights] is the chance weight in shallow, middle and deep water. */
data class FishSpecies(
    val id: String, val minG: Int, val maxG: Int, val rarity: Double,
    val depthWeights: List<Double>, val coverFavourite: Int, val strength: Double, val hue: Int, val pattern: Int, val shape: Int
) {
    val tier: Int get() = if (rarity >= 5) 0 else if (rarity >= 2.5) 1 else if (rarity >= 0.5) 2 else 3
    val avgG: Double get() = minG + (maxG - minG) * (1.0 / 3.2)
}

/** Cover on the water: 0 open, 1 reeds, 2 rocks, 3 lily pads. */
object FishingLakes {
    val species: List<FishSpecies> = listOf(
        FishSpecies("minnow", 20, 60, 10.0, listOf(3.0, 1.0, 0.0), 1, 0.10, 0, 0, 0),
        FishSpecies("bluegill", 80, 350, 9.0, listOf(3.0, 2.0, 0.3), 3, 0.20, 1, 1, 1),
        FishSpecies("perch", 120, 700, 8.0, listOf(2.0, 3.0, 1.0), 1, 0.30, 2, 2, 2),
        FishSpecies("roach", 100, 900, 7.0, listOf(2.0, 3.0, 1.0), 0, 0.30, 3, 0, 2),
        FishSpecies("crappie", 150, 900, 6.0, listOf(1.0, 3.0, 2.0), 2, 0.35, 4, 3, 1),
        FishSpecies("carp", 800, 9000, 4.0, listOf(1.0, 3.0, 3.0), 0, 0.55, 5, 0, 3),
        FishSpecies("trout", 400, 3500, 4.0, listOf(0.0, 3.0, 3.0), 0, 0.50, 6, 3, 2),
        FishSpecies("bass", 600, 5500, 4.0, listOf(1.0, 3.0, 2.0), 2, 0.60, 7, 2, 1),
        FishSpecies("catfish", 1500, 14000, 3.0, listOf(0.0, 2.0, 3.0), 2, 0.60, 8, 1, 3),
        FishSpecies("pike", 1500, 12000, 2.5, listOf(1.0, 3.0, 2.0), 1, 0.75, 9, 3, 4),
        FishSpecies("eel", 300, 2500, 2.0, listOf(0.0, 1.0, 3.0), 2, 0.50, 10, 0, 5),
        FishSpecies("boot", 300, 600, 2.0, listOf(1.0, 1.0, 1.0), 0, 0.05, 11, 0, 6),
        FishSpecies("sturgeon", 5000, 40000, 0.8, listOf(0.0, 1.0, 3.0), 0, 0.90, 12, 1, 4),
        FishSpecies("koi", 2000, 12000, 0.6, listOf(3.0, 2.0, 0.0), 3, 0.65, 13, 2, 3),
        FishSpecies("moonpike", 20000, 60000, 0.15, listOf(0.0, 1.0, 3.0), 0, 1.00, 14, 1, 4)
    )

    val lakeIds = listOf("pond", "lake", "reservoir")

    /** Depth 0..2 for each (band, column) of the lake. */
    private val depthMaps = mapOf(
        "pond" to listOf(listOf(0, 0, 0, 0, 0), listOf(0, 1, 0, 1, 0), listOf(1, 1, 1, 1, 1), listOf(1, 1, 2, 1, 1)),
        "lake" to listOf(listOf(0, 0, 1, 0, 0), listOf(0, 1, 1, 1, 0), listOf(1, 1, 2, 1, 1), listOf(1, 2, 2, 2, 1)),
        "reservoir" to listOf(listOf(0, 1, 1, 1, 0), listOf(1, 1, 2, 1, 1), listOf(1, 2, 2, 2, 1), listOf(2, 2, 2, 2, 2))
    )

    private val lakeMult: Map<String, Map<String, Double>> = mapOf(
        "pond" to mapOf("koi" to 4.0, "catfish" to 0.0, "eel" to 0.0, "sturgeon" to 0.0, "moonpike" to 0.0, "pike" to 0.3, "trout" to 0.3, "bass" to 0.7),
        "lake" to emptyMap(),
        "reservoir" to mapOf("minnow" to 0.4, "bluegill" to 0.3, "koi" to 0.0, "sturgeon" to 3.0, "catfish" to 2.0, "moonpike" to 3.0, "eel" to 1.5)
    )

    fun lakeId(id: String?): String = if (id in lakeIds) id!! else lakeIds[0]

    fun depthAt(lake: String, band: Int, col: Int): Int = depthMaps.getValue(lakeId(lake))[band.coerceIn(0, 3)][col.coerceIn(0, 4)]

    fun coverAt(lake: String, band: Int, col: Int): Int = ((col.coerceIn(0, 4) * 3 + band.coerceIn(0, 3) * 5 + lakeIds.indexOf(lakeId(lake))) % 4)

    fun speciesById(id: String): FishSpecies? = species.firstOrNull { it.id == id }

    /** Chance weight of every species in one spot of the water. */
    fun weightsAt(lake: String, band: Int, col: Int): List<Double> {
        val l = lakeId(lake)
        val depth = depthAt(l, band, col)
        val cover = coverAt(l, band, col)
        return species.map { sp ->
            val m = lakeMult.getValue(l)[sp.id] ?: 1.0
            sp.rarity * sp.depthWeights[depth] * (if (cover == sp.coverFavourite) 2.5 else 1.0) * m
        }
    }

    /** Average grams a cast to this spot brings in (what a clever angler aims for). */
    fun expectedGrams(lake: String, band: Int, col: Int): Double {
        val w = weightsAt(lake, band, col)
        val total = w.sum()
        if (total <= 0.0) return 0.0
        return species.indices.sumOf { w[it] * species[it].avgG } / total
    }
}

/** One fish rushing in the reel-in: from [start] ticks after the hook, lasting [len], with [power] 50..100 (percent). */
data class FishRush(val start: Int, val len: Int, val power: Int)

data class FishCatch(val speciesId: String, val grams: Int)

/** Everything a turn rolls once at the cast; none of it is shown to anybody before the fish is hooked. */
class FishRoll(val speciesIndex: Int, val grams: Int, val biteDelay: Int, val nibbles: List<Int>, val rushes: List<FishRush>) {
    val species: FishSpecies get() = FishingLakes.species[speciesIndex]
}

/**
 * The turn-by-turn contest engine: fixed steps, no clock, all randomness drawn from the injected
 * [rng] at the moment of a cast. One angler acts at a time ([seat]); everyone else watches. The
 * angler's whole interface is one button ([step]'s `hold`) plus the aim column.
 */
class FishingEngine(val lake: String, val seats: Int, val rounds: Int, val relaxed: Boolean, private val rng: Random) {
    var phase = FishPhase.AIM
        internal set
    var round = 0
        internal set
    var turnIndex = 0
        internal set
    var phaseTicks = 0
        internal set
    var turnTicks = 0
        internal set
    var chargeTicks = 0
        internal set
    var aimCol = 2
    var band = 0
        internal set
    var power = 0.0
        internal set
    var tension = 0.0
        internal set
    var distance = 0.0
        internal set
    var reelTicks = 0
        internal set
    var slackTicks = 0
        internal set
    var hold = false
        internal set
    private var prevHold = false
    var outcome = FishOutcome.NONE
        internal set
    var roll: FishRoll? = null
        internal set
    /** Only meaningful once a fish is hooked or the turn is over. */
    var catchOfTurn: FishCatch? = null
        internal set

    val grams = IntArray(seats)
    val catches = List(seats) { ArrayList<FishCatch>() }
    val casts = IntArray(seats)
    var castSerial = 0; internal set
    var nibbleSerial = 0; internal set
    var biteSerial = 0; internal set
    var hookSerial = 0; internal set
    var resultSerial = 0; internal set
    var rushSerial = 0; internal set

    val seat: Int get() = (round + turnIndex) % seats
    val sweetLo: Double get() = if (relaxed) 0.22 else 0.30
    val sweetHi: Double get() = if (relaxed) 0.78 else 0.70
    val biteWindow: Int get() = if (relaxed) FishingRules.BITE_WINDOW * 2 else FishingRules.BITE_WINDOW
    val aimTimeout: Int get() = if (relaxed) FishingRules.AIM_TIMEOUT * 2 else FishingRules.AIM_TIMEOUT
    val resultTicks: Int get() = if (relaxed) FishingRules.RESULT_TICKS * 3 / 2 else FishingRules.RESULT_TICKS
    private val warnLead: Int get() = if (relaxed) FishingRules.RUSH_WARNING * 2 else FishingRules.RUSH_WARNING

    val meter: Double get() = if (phase == FishPhase.CHARGING) FishingRules.meterAt(chargeTicks) else 0.0
    val isNibbling: Boolean get() = phase == FishPhase.WAITING && (roll?.nibbles?.any { phaseTicks >= it && phaseTicks < it + FishingRules.NIBBLE_TICKS } == true)

    /** The rush that is on right now, or null. */
    val rush: FishRush? get() = if (phase == FishPhase.REELING) roll?.rushes?.firstOrNull { reelTicks >= it.start && reelTicks < it.start + it.len } else null
    val warning: Boolean get() = phase == FishPhase.REELING && rush == null &&
        roll?.rushes?.any { reelTicks >= it.start - warnLead && reelTicks < it.start } == true

    /** Chance-free steps a guest can take between snapshots: the swing meter and the reel-in. */
    fun stepLocal(holdNow: Boolean) {
        phaseTicks++
        hold = holdNow
        when (phase) {
            FishPhase.CHARGING -> chargeTicks++
            FishPhase.REELING -> if (roll != null) reelStep(holdNow, decide = false)
            else -> {}
        }
        prevHold = holdNow
    }

    /** One authoritative step for the angler at [seat] whose button is [holdNow]. */
    fun step(holdNow: Boolean) {
        val down = holdNow && !prevHold
        prevHold = holdNow
        hold = holdNow
        if (phase == FishPhase.OVER) return
        phaseTicks++
        when (phase) {
            FishPhase.AIM -> {
                turnTicks++
                if (down) { phase = FishPhase.CHARGING; phaseTicks = 0; chargeTicks = 0 }
                else if (turnTicks >= aimTimeout) endTurn(FishOutcome.TIMED_OUT)
            }
            FishPhase.CHARGING -> {
                turnTicks++
                chargeTicks++
                if (!holdNow) release()
                else if (turnTicks >= aimTimeout) endTurn(FishOutcome.TIMED_OUT)
            }
            FishPhase.CASTING -> if (phaseTicks >= FishingRules.CAST_TICKS) { phase = FishPhase.WAITING; phaseTicks = 0 }
            FishPhase.WAITING -> {
                val r = roll!!
                if (r.nibbles.any { it == phaseTicks }) nibbleSerial++
                if (down && isNibbling) { endTurn(FishOutcome.TOO_EARLY); return }
                if (phaseTicks >= r.biteDelay) { phase = FishPhase.BITE; phaseTicks = 0; biteSerial++ }
            }
            FishPhase.BITE -> {
                if (down) hookFish()
                else if (phaseTicks >= biteWindow) endTurn(FishOutcome.MISSED)
            }
            FishPhase.REELING -> reelStep(holdNow, decide = true)
            FishPhase.RESULT -> if (phaseTicks >= resultTicks) nextTurn()
            FishPhase.OVER -> {}
        }
    }

    private fun release() {
        power = FishingRules.meterAt(chargeTicks)
        band = FishingRules.bandOfPower(power)
        castSerial++
        casts[seat]++
        roll = rollFish(band, aimCol.coerceIn(0, FishingRules.COLUMNS - 1))
        phase = FishPhase.CASTING; phaseTicks = 0
    }

    private fun rollFish(band: Int, col: Int): FishRoll {
        val w = FishingLakes.weightsAt(lake, band, col)
        var pick = rng.nextDouble() * w.sum()
        var idx = w.size - 1
        for (i in w.indices) { pick -= w[i]; if (pick < 0) { idx = i; break } }
        val sp = FishingLakes.species[idx]
        val u = rng.nextDouble().pow(2.2)
        val grams = (sp.minG + (sp.maxG - sp.minG) * u).toInt().coerceIn(sp.minG, sp.maxG)
        val rare = (1.0 - (sp.rarity / 10.0).coerceAtMost(1.0))
        val delay = ((100 + rng.nextInt(260)) * (1.0 + 0.5 * rare)).toInt().coerceIn(90, 520)
        val nibbles = ArrayList<Int>()
        val count = when (rng.nextDouble()) { in 0.0..0.4 -> 0; in 0.4..0.8 -> 1; else -> 2 }
        var t = 30
        repeat(count) {
            val latest = delay - FishingRules.NIBBLE_TICKS - 20
            if (t < latest) { val at = t + rng.nextInt(max(1, min(60, latest - t))); nibbles += at; t = at + FishingRules.NIBBLE_TICKS + 30 }
        }
        val rushCount = 1 + (sp.strength * 5).toInt() + rng.nextInt(2)
        val rushes = ArrayList<FishRush>()
        var rt = 90 + rng.nextInt(60)
        repeat(rushCount) {
            val len = 40 + rng.nextInt(50)
            val pw = ((0.5 + 0.5 * rng.nextDouble()) * 100).toInt()
            rushes += FishRush(rt, len, pw)
            rt += len + warnLead + 50 + rng.nextInt(90)
        }
        return FishRoll(idx, grams, delay, nibbles, rushes)
    }

    private fun hookFish() {
        phase = FishPhase.REELING; phaseTicks = 0
        tension = 0.4; distance = FishingRules.distanceOf(band); reelTicks = 0; slackTicks = 0
        hookSerial++
    }

    private fun reelStep(holdNow: Boolean, decide: Boolean) {
        val r = roll ?: return
        val str = r.species.strength
        reelTicks++
        val rushNow = rush
        val pull = if (rushNow != null) (0.8 + 1.4 * str * rushNow.power / 100.0) * (if (relaxed) 0.7 else 1.0) else 0.0
        var rate = if (holdNow) (if (relaxed) FishingRules.T_UP * 0.8 else FishingRules.T_UP) else -FishingRules.T_DOWN
        rate += pull * (if (holdNow) 1.0 else 0.35)
        tension = (tension + rate * FishingRules.DT).coerceIn(0.0, 1.05)
        if (rushNow != null) {
            distance += (2.5 + 3.0 * str * rushNow.power / 100.0) * FishingRules.DT
            if (reelTicks == rushNow.start + 1) rushSerial++
        } else if (holdNow) {
            val zone = if (tension in sweetLo..sweetHi) 1.0 else if (tension > sweetHi) 0.7 else 0.5
            distance -= FishingRules.REEL_SPEED * (1.0 - 0.5 * str) * zone * FishingRules.DT
        } else {
            distance += FishingRules.DRIFT * FishingRules.DT
        }
        if (distance > 60.0) distance = 60.0
        // a fish that is pulling keeps the line taut, so only a calm fish can slip a slack line
        slackTicks = if (tension < FishingRules.SLACK_LEVEL && rushNow == null) slackTicks + 1 else 0
        if (!decide) return
        when {
            tension >= 1.0 -> endTurn(FishOutcome.SNAPPED)
            slackTicks >= FishingRules.SLACK_TICKS -> endTurn(FishOutcome.SLACK)
            distance <= 0.0 -> endTurn(FishOutcome.CAUGHT)
            reelTicks >= FishingRules.REEL_TIMEOUT -> endTurn(FishOutcome.GOT_AWAY)
        }
    }

    private fun endTurn(o: FishOutcome) {
        outcome = o
        if (o == FishOutcome.CAUGHT) {
            val r = roll!!
            val c = FishCatch(r.species.id, r.grams)
            catchOfTurn = c
            catches[seat].add(c)
            grams[seat] += c.grams
        } else catchOfTurn = null
        phase = FishPhase.RESULT; phaseTicks = 0
        resultSerial++
    }

    private fun nextTurn() {
        turnIndex++
        if (turnIndex >= seats) { turnIndex = 0; round++ }
        roll = null; catchOfTurn = null; outcome = FishOutcome.NONE
        tension = 0.0; distance = 0.0; reelTicks = 0; slackTicks = 0; chargeTicks = 0; turnTicks = 0; phaseTicks = 0
        if (round >= rounds) phase = FishPhase.OVER else phase = FishPhase.AIM
    }

    /** Heaviest single catch of a seat in grams, 0 if none. */
    fun biggest(s: Int): Int = catches[s].maxOfOrNull { it.grams } ?: 0

    /** Seats ranked best first: total weight, then heaviest fish, then number of fish; ties share a place. */
    fun ranking(): List<Int> = (0 until seats).sortedWith(
        compareByDescending<Int> { grams[it] }.thenByDescending { biggest(it) }.thenByDescending { catches[it].size }.thenBy { it }
    )

    fun tied(a: Int, b: Int): Boolean = grams[a] == grams[b] && biggest(a) == biggest(b) && catches[a].size == catches[b].size

    fun fingerprint(): Long {
        var h = 7L
        fun mix(x: Long) { h = h * 31 + x }
        mix(phase.ordinal.toLong()); mix(round.toLong()); mix(turnIndex.toLong()); mix(phaseTicks.toLong())
        grams.forEach { mix(it.toLong()) }
        catches.forEach { l -> l.forEach { mix(it.grams.toLong()); mix(it.speciesId.hashCode().toLong()) } }
        mix(tension.toRawBits()); mix(distance.toRawBits())
        return h
    }
}

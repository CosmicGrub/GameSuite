package com.gamesuite.games.billiards

import kotlinx.serialization.Serializable
import kotlin.random.Random

object BilliardsVariant {
    const val EIGHT = "8ball"
    const val NINE = "9ball"
    val ALL = setOf(EIGHT, NINE)

    fun fromOption(raw: String?): String = if (raw == NINE) NINE else EIGHT
    fun ballCount(variant: String) = if (variant == NINE) 9 else 15
}

/** Why a shot was a foul. The names are what the screens map to text. */
object FoulKind {
    const val SCRATCH = "scratch"
    const val NO_CONTACT = "no_contact"
    const val WRONG_BALL = "wrong_ball"
    const val NO_RAIL = "no_rail"
    const val BAD_BREAK = "bad_break"
    const val THREE_FOULS = "three_fouls"
    val ALL = setOf(SCRATCH, NO_CONTACT, WRONG_BALL, NO_RAIL, BAD_BREAK, THREE_FOULS)
}

/** How a rack ended. */
object WinReason {
    const val NONE = ""
    const val CLEARED = "cleared"          // 8-ball: sank the 8 after clearing the group; 9-ball: sank the 9
    const val EIGHT_EARLY = "eight_early"  // sank the 8 too soon
    const val EIGHT_FOUL = "eight_foul"    // sank the 8 and fouled
    const val THREE_FOULS = "three_fouls"
    val ALL = setOf(NONE, CLEARED, EIGHT_EARLY, EIGHT_FOUL, THREE_FOULS)
}

/** What the table looked like when the shot was taken, as far as the rules care. */
class RuleContext(
    val variant: String,
    val shooter: Int,
    /** Per seat: 0 = open table / no group, 1 = solids, 2 = stripes (8-ball only). */
    val groups: List<Int>,
    /** Balls (ids) on the table before the shot, cue ball excluded. */
    val onTable: Set<Int>,
    val isBreak: Boolean,
    /** Consecutive fouls the shooter had made before this shot. */
    val foulStreak: Int
)

/** The facts of a shot, from [SimResult]. */
class ShotOutcome(
    val firstContact: Int,
    val pocketed: List<Int>,
    val railOrPocketAfterContact: Boolean,
    val objectBallsOnRails: Int
) {
    companion object {
        fun of(r: SimResult) = ShotOutcome(r.firstContact, r.pocketed, r.railOrPocketAfterContact, r.objectBallsOnRails.size)
    }
}

@Serializable
data class Verdict(
    val fouls: List<String>,
    /** Object balls (not the cue, not a re-spotted ball) that stay down this shot. */
    val pocketed: List<Int>,
    val nextShooter: Int,
    val ballInHand: Boolean,
    val behindHead: Boolean,
    /** -1 while the rack goes on. */
    val winner: Int,
    val winReason: String,
    /** The group the shooter was given by this shot (8-ball open table), else 0. */
    val assignedGroup: Int,
    /** Balls that go back on the foot spot. */
    val respot: List<Int>
) {
    val foul: Boolean get() = fouls.isNotEmpty()
}

object BilliardsRules {
    // ---- rack -------------------------------------------------------------------------------------

    private const val GAP = 0.02

    /** Positions of the object balls for a fresh rack, with the cue ball on the head spot. Random only decides which ball sits where. */
    fun rack(variant: String, random: Random): Triple<DoubleArray, DoubleArray, BooleanArray> {
        val x = DoubleArray(BilliardsTable.BALLS); val y = DoubleArray(BilliardsTable.BALLS)
        val on = BooleanArray(BilliardsTable.BALLS)
        x[0] = BilliardsTable.HEAD_X; y[0] = BilliardsTable.MID_Y; on[0] = true
        val spacing = 2 * BilliardsTable.R + GAP
        val dx = spacing * Math.sqrt(3.0) / 2.0
        val slots = ArrayList<Pair<Double, Double>>()
        if (variant == BilliardsVariant.NINE) {
            val rows = intArrayOf(1, 2, 3, 2, 1)
            rows.forEachIndexed { r, cnt ->
                for (j in 0 until cnt) slots.add(BilliardsTable.FOOT_X + r * dx to BilliardsTable.MID_Y + (j - (cnt - 1) / 2.0) * spacing)
            }
            // 1 at the apex, 9 in the middle, the rest in any order
            val rest = (2..8).toMutableList().also { it.shuffle(random) }
            val order = IntArray(9)
            order[0] = 1; order[4] = 9
            var k = 0
            for (s in 0 until 9) if (s != 0 && s != 4) order[s] = rest[k++]
            for (s in 0 until 9) { x[order[s]] = slots[s].first; y[order[s]] = slots[s].second; on[order[s]] = true }
        } else {
            for (r in 0 until 5) for (j in 0..r) {
                slots.add(BilliardsTable.FOOT_X + r * dx to BilliardsTable.MID_Y + (j - r / 2.0) * spacing)
            }
            val balls = ((1..7) + (9..15)).toMutableList().also { it.shuffle(random) }
            val order = IntArray(15)
            var k = 0
            for (s in 0 until 15) order[s] = if (s == 4) 8 else balls[k++]
            // the two back corners must be one solid and one stripe
            if (BilliardsTable.groupOf(order[10]) == BilliardsTable.groupOf(order[14])) {
                val want = 3 - BilliardsTable.groupOf(order[14])
                val swapWith = (0 until 15).first { it != 4 && it != 10 && it != 14 && BilliardsTable.groupOf(order[it]) == want }
                val t = order[14]; order[14] = order[swapWith]; order[swapWith] = t
            }
            for (s in 0 until 15) { x[order[s]] = slots[s].first; y[order[s]] = slots[s].second; on[order[s]] = true }
        }
        return Triple(x, y, on)
    }

    // ---- placement -------------------------------------------------------------------------------

    /** Whether the cue ball may be set down at ([cx], [cy]) while the other balls are where [x]/[y]/[on] say. */
    fun canPlaceCue(cx: Double, cy: Double, x: DoubleArray, y: DoubleArray, on: BooleanArray, behindHead: Boolean): Boolean {
        if (!cx.isFinite() || !cy.isFinite()) return false
        val r = BilliardsTable.R
        if (cx < r || cx > BilliardsTable.W - r || cy < r || cy > BilliardsTable.H - r) return false
        if (behindHead && cx > BilliardsTable.HEAD_X) return false
        for (b in 1 until BilliardsTable.BALLS) {
            if (!on[b]) continue
            val dx = x[b] - cx; val dy = y[b] - cy
            if (dx * dx + dy * dy < 4 * r * r) return false
        }
        // not inside a pocket mouth
        for (k in 0 until 6) {
            val dx = cx - BilliardsTable.POCKET_X[k]; val dy = cy - BilliardsTable.POCKET_Y[k]
            if (dx * dx + dy * dy < (BilliardsTable.POCKET_R[k] + r) * (BilliardsTable.POCKET_R[k] + r)) return false
        }
        return true
    }

    /** The nearest free spot to (px, py) for a ball to be set down, searching in rings. */
    fun freeSpot(px: Double, py: Double, x: DoubleArray, y: DoubleArray, on: BooleanArray, behindHead: Boolean = false): Pair<Double, Double> {
        if (canPlaceCue(px, py, x, y, on, behindHead)) return px to py
        var best: Pair<Double, Double>? = null
        var bestD = Double.MAX_VALUE
        var gx = BilliardsTable.R
        while (gx <= BilliardsTable.W - BilliardsTable.R + 1e-9) {
            var gy = BilliardsTable.R
            while (gy <= BilliardsTable.H - BilliardsTable.R + 1e-9) {
                if (canPlaceCue(gx, gy, x, y, on, behindHead)) {
                    val d = (gx - px) * (gx - px) + (gy - py) * (gy - py)
                    if (d < bestD) { bestD = d; best = gx to gy }
                }
                gy += 1.0
            }
            gx += 1.0
        }
        return best ?: (BilliardsTable.HEAD_X to BilliardsTable.MID_Y)
    }

    // ---- targets ---------------------------------------------------------------------------------

    /** Own group's balls still on the table for a seat with [group] (empty on an open table). */
    fun remainingOf(group: Int, onTable: Set<Int>): Set<Int> =
        if (group == 0) emptySet() else onTable.filter { BilliardsTable.groupOf(it) == group }.toSet()

    /** The balls the shooter may touch first with the cue ball. */
    fun legalTargets(variant: String, groups: List<Int>, shooter: Int, onTable: Set<Int>, isBreak: Boolean): Set<Int> {
        if (variant == BilliardsVariant.NINE) return onTable.minOrNull()?.let { setOf(it) } ?: emptySet()
        if (isBreak) return onTable
        val g = groups[shooter]
        if (g == 0) return onTable - 8
        val own = remainingOf(g, onTable)
        return if (own.isEmpty()) (if (8 in onTable) setOf(8) else emptySet()) else own
    }

    // ---- the verdict -----------------------------------------------------------------------------

    fun judge(ctx: RuleContext, out: ShotOutcome): Verdict =
        if (ctx.variant == BilliardsVariant.NINE) judgeNine(ctx, out) else judgeEight(ctx, out)

    private fun baseFouls(ctx: RuleContext, out: ShotOutcome, legalFirst: Set<Int>, objectPocketed: Int): MutableList<String> {
        val fouls = ArrayList<String>()
        if (0 in out.pocketed) fouls.add(FoulKind.SCRATCH)
        if (out.firstContact == -1) fouls.add(FoulKind.NO_CONTACT)
        else if (out.firstContact !in legalFirst) fouls.add(FoulKind.WRONG_BALL)
        else if (!ctx.isBreak && objectPocketed == 0 && !out.railOrPocketAfterContact) fouls.add(FoulKind.NO_RAIL)
        if (ctx.isBreak && objectPocketed == 0 && out.objectBallsOnRails < 4) fouls.add(FoulKind.BAD_BREAK)
        return fouls
    }

    private fun judgeEight(ctx: RuleContext, out: ShotOutcome): Verdict {
        val me = ctx.shooter; val opp = 1 - me
        val legalFirst = legalTargets(ctx.variant, ctx.groups, me, ctx.onTable, ctx.isBreak)
        var objects = out.pocketed.filter { it != 0 }
        val respot = ArrayList<Int>()
        val fouls = baseFouls(ctx, out, legalFirst, objects.size)
        val brokePocketed = objects.isNotEmpty()
        // an 8 sunk on the break goes back on the foot spot and the game goes on
        if (ctx.isBreak && 8 in objects) { respot.add(8); objects = objects - 8 }
        val scratchOnBreak = ctx.isBreak && FoulKind.SCRATCH in fouls

        // the 8 ball
        if (8 in objects) {
            val myGroup = ctx.groups[me]
            val readyForEight = myGroup != 0 && remainingOf(myGroup, ctx.onTable).isEmpty()
            return if (fouls.isEmpty() && readyForEight) {
                Verdict(emptyList(), objects, me, false, false, me, WinReason.CLEARED, 0, respot)
            } else {
                Verdict(fouls, objects, opp, false, false, opp, if (readyForEight) WinReason.EIGHT_FOUL else WinReason.EIGHT_EARLY, 0, respot)
            }
        }

        var assigned = 0
        if (fouls.isEmpty() && ctx.groups[me] == 0 && !ctx.isBreak && objects.isNotEmpty()) {
            assigned = BilliardsTable.groupOf(objects.first { BilliardsTable.groupOf(it) != 0 })
        }
        val effectiveGroup = if (assigned != 0) assigned else ctx.groups[me]
        val pottedOwn = when {
            ctx.isBreak -> brokePocketed
            effectiveGroup == 0 -> objects.isNotEmpty()
            else -> objects.any { BilliardsTable.groupOf(it) == effectiveGroup }
        }
        if (fouls.isNotEmpty()) {
            return Verdict(fouls, objects, opp, true, scratchOnBreak, -1, WinReason.NONE, 0, respot)
        }
        return Verdict(emptyList(), objects, if (pottedOwn) me else opp, false, false, -1, WinReason.NONE, assigned, respot)
    }

    private fun judgeNine(ctx: RuleContext, out: ShotOutcome): Verdict {
        val me = ctx.shooter; val opp = 1 - me
        val legalFirst = legalTargets(ctx.variant, ctx.groups, me, ctx.onTable, ctx.isBreak)
        var objects = out.pocketed.filter { it != 0 }
        val fouls = baseFouls(ctx, out, legalFirst, objects.size)
        val respot = ArrayList<Int>()
        val streak = if (fouls.isNotEmpty()) ctx.foulStreak + 1 else 0
        if (9 in objects) {
            if (fouls.isEmpty()) return Verdict(emptyList(), objects, me, false, false, me, WinReason.CLEARED, 0, respot)
            respot.add(9); objects = objects - 9
        }
        if (fouls.isNotEmpty()) {
            if (streak >= 3) {
                return Verdict(fouls + FoulKind.THREE_FOULS, objects, opp, true, false, opp, WinReason.THREE_FOULS, 0, respot)
            }
            return Verdict(fouls, objects, opp, true, ctx.isBreak && FoulKind.SCRATCH in fouls, -1, WinReason.NONE, 0, respot)
        }
        return Verdict(emptyList(), objects, if (objects.isNotEmpty()) me else opp, false, false, -1, WinReason.NONE, 0, respot)
    }
}

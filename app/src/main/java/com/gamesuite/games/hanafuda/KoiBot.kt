package com.gamesuite.games.hanafuda

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The computer opponent. It is handed a VIEW of the round for its own seat (the other hand and the
 * deck already replaced by placeholders, see [KoiRound.redactedFor]) and uses nothing else, so it
 * cannot see hidden cards; the tests feed it redacted views to prove it.
 *
 *  - EASY plays the biggest capture most of the time but blunders a third of the time and calls
 *    shobu on small hands, so it is beatable and feels human.
 *  - MEDIUM chases captures by card worth, takes a new combination when it can, and calls shobu from
 *    five points or with one card left.
 *  - HARD also weighs how close each play brings its own combinations, which cards it takes away
 *    from the opponent's combinations, how risky a discard is given the cards still unseen, and uses
 *    the opponent's progress to decide between koi-koi and shobu.
 */
class KoiBot(private val difficulty: CpuDifficulty, private val random: Random = Random.Default) {

    fun choosePlay(view: KoiRound, seat: Int): KoiPlay {
        val plays = KoiRules.legalPlays(view)
        require(plays.isNotEmpty()) { "no legal play" }
        if (difficulty == CpuDifficulty.EASY && random.nextInt(100) < 35) return plays[random.nextInt(plays.size)]
        return plays.maxByOrNull { scorePlay(view, seat, it) + random.nextDouble() * 0.01 }!!
    }

    fun chooseDrawMatch(view: KoiRound, seat: Int): Int {
        val options = KoiRules.drawChoices(view)
        require(options.isNotEmpty()) { "nothing to choose" }
        if (difficulty == CpuDifficulty.EASY && random.nextInt(100) < 35) return options[random.nextInt(options.size)]
        return options.maxByOrNull { field ->
            val after = KoiRules.chooseDrawMatch(view, field)!!
            scoreOutcome(view, seat, after, takenFromField = listOf(field, view.drawn), discarded = null)
        }!!
    }

    /** True for koi-koi (play on), false for shobu (score now). */
    fun decide(view: KoiRound, seat: Int): Boolean {
        val mine = view.yakuTotal(seat)
        val handLeft = view.hands[seat].size
        val opp = 1 - seat
        val oppTotal = view.yakuTotal(opp)
        if (handLeft == 0) return false
        return when (difficulty) {
            CpuDifficulty.EASY -> if (mine >= 4) false else random.nextBoolean()
            CpuDifficulty.MEDIUM -> !(mine >= 5 || handLeft <= 1)
            CpuDifficulty.HARD -> {
                val threat = potential(view.captured[opp], view.captured[seat], view.yakuSet)
                when {
                    mine >= KoiRules.DOUBLE_AT -> false
                    view.koiCalls[opp] > 0 -> false // winning now is worth double
                    oppTotal > 0 && mine >= 3 -> false
                    threat >= 4.0 && mine >= 3 -> false
                    handLeft >= 4 && mine <= 3 -> true
                    else -> mine < 5 && handLeft >= 3
                }
            }
        }
    }

    // ---- evaluation -----------------------------------------------------------------------------

    private fun scorePlay(view: KoiRound, seat: Int, play: KoiPlay): Double {
        val after = KoiRules.playHand(view, play) ?: return Double.NEGATIVE_INFINITY
        val taken = view.field.filter { it !in after.field }
        val discarded = if (taken.isEmpty()) play.handCard else null
        return scoreOutcome(view, seat, after, takenFromField = if (taken.isEmpty()) emptyList() else taken + play.handCard, discarded = discarded)
    }

    private fun scoreOutcome(view: KoiRound, seat: Int, after: KoiRound, takenFromField: List<Int>, discarded: Int?): Double {
        var score = takenFromField.sumOf { HanafudaCards.worth(HanafudaCards.card(it)).toDouble() }
        val risen = after.yakuTotal(seat) > view.decisionBase[seat]
        if (risen) score += 25.0 + 5.0 * after.yakuTotal(seat)
        if (difficulty == CpuDifficulty.MEDIUM) {
            score += 0.5 * (potential(after.captured[seat], view.captured[1 - seat], view.yakuSet) - potential(view.captured[seat], view.captured[1 - seat], view.yakuSet))
        }
        if (difficulty == CpuDifficulty.HARD) {
            val opp = 1 - seat
            score += 3.0 * (potential(after.captured[seat], view.captured[opp], view.yakuSet) - potential(view.captured[seat], view.captured[opp], view.yakuSet))
            // Denial: cards we take that the opponent's combinations were counting on.
            val before = potential(view.captured[opp], after.captured[seat], view.yakuSet)
            val without = potential(view.captured[opp], view.captured[seat], view.yakuSet)
            score += 2.0 * (without - before).coerceAtLeast(0.0)
            if (discarded != null) {
                val worth = HanafudaCards.worth(HanafudaCards.card(discarded)).toDouble()
                score -= worth * discardRisk(view, seat, discarded)
                // Also dangerous: discarding the match for a card that sits in the opponent's likely reach.
                score -= 0.3 * worth * (if (after.field.count { HanafudaCards.monthOf(it) == HanafudaCards.monthOf(discarded) } > 1) 1 else 0)
            }
        } else if (discarded != null) {
            score -= HanafudaCards.worth(HanafudaCards.card(discarded)).toDouble() * 0.5
        }
        return score
    }

    /** How likely the opponent can use a card we leave on the field: high while its month still has unseen cards. */
    private fun discardRisk(view: KoiRound, seat: Int, card: Int): Double {
        val month = HanafudaCards.monthOf(card)
        val seen = (view.hands[seat] + view.field + view.captured[0] + view.captured[1])
            .count { it >= 0 && HanafudaCards.monthOf(it) == month }
        val unseen = HANAFUDA_CARDS / HANAFUDA_MONTHS - seen
        return if (unseen > 0) 0.7 else 0.1
    }

    /** A smooth estimate (in points) of how close [mine] is to scoring combinations the opponent holding [theirs] has not blocked. */
    internal fun potential(mine: Collection<Int>, theirs: Collection<Int>, set: YakuSet): Double {
        val m = mine.toSet()
        val t = theirs.toSet()
        var total = 0.0
        fun combo(required: List<Int>, points: Int) {
            if (required.any { it in t }) return
            val have = required.count { it in m }
            total += points * (have.toDouble() / required.size).pow(2)
        }
        fun countBased(kind: HanaKind, need: Int, available: Int, points: Int) {
            val mineCount = m.count { HanafudaCards.card(it).kind == kind }
            val theirCount = t.count { HanafudaCards.card(it).kind == kind }
            if (available - theirCount < need) return
            total += points * (min(mineCount, need).toDouble() / need).pow(2)
        }
        val brights = HanafudaCards.brightIds
        val plainBrights = brights.filter { it != HanafudaCards.rainManId }
        total += 10 * (m.count { it in brights }.toDouble() / 5).pow(2) * (if (brights.any { it in t }) 0.4 else 1.0)
        val sankoHave = m.count { it in plainBrights }
        if (plainBrights.count { it in t } <= 1) total += 5 * (min(sankoHave, 3).toDouble() / 3).pow(2)
        combo(listOf(HanaSpecial.BOAR, HanaSpecial.DEER, HanaSpecial.BUTTERFLIES).map { HanafudaCards.idOf(it) }, 5)
        combo(HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }, 5)
        combo(HanafudaCards.all.filter { it.ribbon == HanaRibbon.BLUE }.map { it.id }, 5)
        countBased(HanaKind.ANIMAL, 5, 9, 1)
        countBased(HanaKind.RIBBON, 5, 10, 1)
        countBased(HanaKind.PLAIN, 10, 24, 1)
        if (set == YakuSet.FULL) {
            combo(listOf(HanafudaCards.idOf(HanaSpecial.CURTAIN), HanafudaCards.sakeCupId), 5)
            combo(listOf(HanafudaCards.idOf(HanaSpecial.MOON), HanafudaCards.sakeCupId), 5)
        }
        return total
    }

    /** The play the hint button suggests: the strongest the computer's own search finds, whatever the level. */
    companion object {
        fun hint(view: KoiRound, seat: Int): KoiPlay = KoiBot(CpuDifficulty.HARD, Random(7)).choosePlay(view, seat)
    }
}

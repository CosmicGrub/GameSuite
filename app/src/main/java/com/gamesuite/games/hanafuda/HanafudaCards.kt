package com.gamesuite.games.hanafuda

/**
 * The 48 flower cards of a hanafuda deck: twelve months, four cards each. A card is its Int id
 * 0..47; `id / 4` is the month (0 = pine ... 11 = paulownia) and `id % 4` its place in the month's
 * list below. This file only describes the cards; the rules live in [KoiRules] and the scoring
 * combinations in [KoiYaku].
 */
const val HANAFUDA_CARDS = 48
const val HANAFUDA_MONTHS = 12

/** The four card classes. Their shape and label on screen never depend on colour alone. */
enum class HanaKind { BRIGHT, ANIMAL, RIBBON, PLAIN }

enum class HanaRibbon { NONE, RED_POETRY, RED, BLUE }

/** The named picture on a card (NONE for the plain cards). */
enum class HanaSpecial {
    NONE, CRANE, CURTAIN, MOON, RAIN_MAN, PHOENIX,
    BUSH_WARBLER, CUCKOO, BRIDGE, BUTTERFLIES, BOAR, GEESE, SAKE_CUP, DEER, SWALLOW
}

/** The twelve flowers, in month order. */
enum class HanaMonth { PINE, PLUM, CHERRY, WISTERIA, IRIS, PEONY, BUSH_CLOVER, PAMPAS, CHRYSANTHEMUM, MAPLE, WILLOW, PAULOWNIA }

data class HanaCard(
    val id: Int,
    val month: Int,
    val kind: HanaKind,
    val ribbon: HanaRibbon,
    val special: HanaSpecial
) {
    val flower: HanaMonth get() = HanaMonth.entries[month]
}

object HanafudaCards {
    private fun b(sp: HanaSpecial) = Triple(HanaKind.BRIGHT, HanaRibbon.NONE, sp)
    private fun a(sp: HanaSpecial) = Triple(HanaKind.ANIMAL, HanaRibbon.NONE, sp)
    private fun r(rb: HanaRibbon) = Triple(HanaKind.RIBBON, rb, HanaSpecial.NONE)
    private val p = Triple(HanaKind.PLAIN, HanaRibbon.NONE, HanaSpecial.NONE)

    /** Four entries per month: the class, ribbon type and named picture of each card. */
    private val table: List<List<Triple<HanaKind, HanaRibbon, HanaSpecial>>> = listOf(
        listOf(b(HanaSpecial.CRANE), r(HanaRibbon.RED_POETRY), p, p), // pine
        listOf(a(HanaSpecial.BUSH_WARBLER), r(HanaRibbon.RED_POETRY), p, p), // plum
        listOf(b(HanaSpecial.CURTAIN), r(HanaRibbon.RED_POETRY), p, p), // cherry
        listOf(a(HanaSpecial.CUCKOO), r(HanaRibbon.RED), p, p), // wisteria
        listOf(a(HanaSpecial.BRIDGE), r(HanaRibbon.RED), p, p), // iris
        listOf(a(HanaSpecial.BUTTERFLIES), r(HanaRibbon.BLUE), p, p), // peony
        listOf(a(HanaSpecial.BOAR), r(HanaRibbon.RED), p, p), // bush clover
        listOf(b(HanaSpecial.MOON), a(HanaSpecial.GEESE), p, p), // pampas
        listOf(a(HanaSpecial.SAKE_CUP), r(HanaRibbon.BLUE), p, p), // chrysanthemum
        listOf(a(HanaSpecial.DEER), r(HanaRibbon.BLUE), p, p), // maple
        listOf(b(HanaSpecial.RAIN_MAN), a(HanaSpecial.SWALLOW), r(HanaRibbon.RED), p), // willow
        listOf(b(HanaSpecial.PHOENIX), p, p, p) // paulownia
    )

    val all: List<HanaCard> = List(HANAFUDA_CARDS) { id ->
        val (kind, ribbon, special) = table[id / 4][id % 4]
        HanaCard(id, id / 4, kind, ribbon, special)
    }

    fun card(id: Int): HanaCard = all[id]

    fun monthOf(id: Int): Int = id / 4

    fun isReal(id: Int): Boolean = id in 0 until HANAFUDA_CARDS

    fun ofKind(kind: HanaKind): List<HanaCard> = all.filter { it.kind == kind }

    private val firstId: Map<HanaSpecial, Int> = all.filter { it.special != HanaSpecial.NONE }.associate { it.special to it.id }

    /** The id of the named card (e.g. the crane). */
    fun idOf(special: HanaSpecial): Int = firstId.getValue(special)

    val brightIds: List<Int> = ofKind(HanaKind.BRIGHT).map { it.id }
    val rainManId: Int = idOf(HanaSpecial.RAIN_MAN)
    val sakeCupId: Int = idOf(HanaSpecial.SAKE_CUP)

    /** Points a card is worth to a player choosing between captures (used by the computer opponent and hints). */
    fun worth(card: HanaCard): Int = when (card.kind) {
        HanaKind.BRIGHT -> if (card.special == HanaSpecial.RAIN_MAN) 12 else 20
        HanaKind.ANIMAL -> when (card.special) {
            HanaSpecial.SAKE_CUP -> 15
            HanaSpecial.BOAR, HanaSpecial.DEER, HanaSpecial.BUTTERFLIES -> 12
            else -> 8
        }
        HanaKind.RIBBON -> if (card.ribbon == HanaRibbon.RED) 5 else 7
        HanaKind.PLAIN -> 1
    }
}

/** Which combinations score. Basic is the classic eleven; Full adds the viewing-sake hands, the red-and-blue ribbons and the two dealt-hand specials. */
enum class YakuSet(val id: String) {
    BASIC("basic"), FULL("full");

    companion object {
        fun fromId(id: String?): YakuSet = entries.firstOrNull { it.id == id } ?: BASIC
    }
}

/** A scoring combination ("yaku"). [id] is stable for string lookups. */
enum class Yaku(val id: String, val fullSetOnly: Boolean = false) {
    FIVE_BRIGHTS("five_brights"),
    FOUR_BRIGHTS("four_brights"),
    RAINY_FOUR_BRIGHTS("rainy_four_brights"),
    THREE_BRIGHTS("three_brights"),
    BOAR_DEER_BUTTERFLIES("boar_deer_butterflies"),
    ANIMALS("animals"),
    RED_POETRY_RIBBONS("red_poetry_ribbons"),
    BLUE_RIBBONS("blue_ribbons"),
    RIBBONS("ribbons"),
    PLAINS("plains"),
    FLOWER_VIEWING("flower_viewing", fullSetOnly = true),
    MOON_VIEWING("moon_viewing", fullSetOnly = true),
    RED_AND_BLUE("red_and_blue", fullSetOnly = true)
}

data class YakuScore(val yaku: Yaku, val points: Int)

object KoiYaku {
    private val poetryRibbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
    private val blueRibbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.BLUE }.map { it.id }
    private val boarDeerButterflies = listOf(HanaSpecial.BOAR, HanaSpecial.DEER, HanaSpecial.BUTTERFLIES).map { HanafudaCards.idOf(it) }
    private val curtainId = HanafudaCards.idOf(HanaSpecial.CURTAIN)
    private val moonId = HanafudaCards.idOf(HanaSpecial.MOON)

    /** Every combination [captured] currently makes under [set], with its points. */
    fun evaluate(captured: Collection<Int>, set: YakuSet): List<YakuScore> {
        val have = captured.filter { HanafudaCards.isReal(it) }.toSet()
        val cards = have.map { HanafudaCards.card(it) }
        val out = ArrayList<YakuScore>()
        val brights = cards.count { it.kind == HanaKind.BRIGHT }
        val rain = HanafudaCards.rainManId in have
        when {
            brights == 5 -> out += YakuScore(Yaku.FIVE_BRIGHTS, 10)
            brights == 4 && !rain -> out += YakuScore(Yaku.FOUR_BRIGHTS, 8)
            brights == 4 && rain -> out += YakuScore(Yaku.RAINY_FOUR_BRIGHTS, 7)
            brights == 3 && !rain -> out += YakuScore(Yaku.THREE_BRIGHTS, 5)
        }
        val animals = cards.count { it.kind == HanaKind.ANIMAL }
        if (boarDeerButterflies.all { it in have }) out += YakuScore(Yaku.BOAR_DEER_BUTTERFLIES, 5 + (animals - 3))
        if (animals >= 5) out += YakuScore(Yaku.ANIMALS, 1 + (animals - 5))
        val ribbons = cards.count { it.kind == HanaKind.RIBBON }
        if (poetryRibbons.all { it in have }) out += YakuScore(Yaku.RED_POETRY_RIBBONS, 5 + (ribbons - 3))
        if (blueRibbons.all { it in have }) out += YakuScore(Yaku.BLUE_RIBBONS, 5 + (ribbons - 3))
        if (ribbons >= 5) out += YakuScore(Yaku.RIBBONS, 1 + (ribbons - 5))
        val plains = cards.count { it.kind == HanaKind.PLAIN }
        if (plains >= 10) out += YakuScore(Yaku.PLAINS, 1 + (plains - 10))
        if (set == YakuSet.FULL) {
            if (curtainId in have && HanafudaCards.sakeCupId in have) out += YakuScore(Yaku.FLOWER_VIEWING, 5)
            if (moonId in have && HanafudaCards.sakeCupId in have) out += YakuScore(Yaku.MOON_VIEWING, 5)
            if (poetryRibbons.all { it in have } && blueRibbons.all { it in have }) out += YakuScore(Yaku.RED_AND_BLUE, 10)
        }
        return out
    }

    fun total(captured: Collection<Int>, set: YakuSet): Int = evaluate(captured, set).sumOf { it.points }
}

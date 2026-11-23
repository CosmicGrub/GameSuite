package com.gamesuite.games.mahjong

import kotlin.random.Random

enum class MahjongSuit { DOTS, BAMBOO, CHARACTERS, WIND, DRAGON, FLOWER, SEASON }

/**
 * The 42 tile TYPES of the standard 144-tile set and their matching groups.
 *
 *  - 0..8 dots 1-9, 9..17 bamboo 1-9, 18..26 characters 1-9 (four of each)
 *  - 27..30 winds East South West North, 31..33 dragons Red Green White (four of each)
 *  - 34..37 the four flowers (any flower matches any flower), 38..41 the four seasons (likewise)
 */
object MahjongTiles {
    const val TYPE_COUNT = 42
    const val FIRST_WIND = 27
    const val FIRST_DRAGON = 31
    const val FIRST_FLOWER = 34
    const val FIRST_SEASON = 38

    /** Group ids: a simple type is its own group; every flower is group 34 and every season group 35. */
    const val FLOWER_GROUP = 34
    const val SEASON_GROUP = 35
    const val GROUP_COUNT = 36

    fun group(type: Int): Int = when {
        type < FIRST_FLOWER -> type
        type < FIRST_SEASON -> FLOWER_GROUP
        else -> SEASON_GROUP
    }

    fun matches(a: Int, b: Int): Boolean =
        a in 0 until TYPE_COUNT && b in 0 until TYPE_COUNT && group(a) == group(b)

    fun suit(type: Int): MahjongSuit = when {
        type < 9 -> MahjongSuit.DOTS
        type < 18 -> MahjongSuit.BAMBOO
        type < 27 -> MahjongSuit.CHARACTERS
        type < FIRST_DRAGON -> MahjongSuit.WIND
        type < FIRST_FLOWER -> MahjongSuit.DRAGON
        type < FIRST_SEASON -> MahjongSuit.FLOWER
        else -> MahjongSuit.SEASON
    }

    /** 1-9 for the number suits, 1-4 for winds / flowers / seasons, 1-3 for dragons. */
    fun rank(type: Int): Int = when (suit(type)) {
        MahjongSuit.DOTS -> type + 1
        MahjongSuit.BAMBOO -> type - 8
        MahjongSuit.CHARACTERS -> type - 17
        MahjongSuit.WIND -> type - FIRST_WIND + 1
        MahjongSuit.DRAGON -> type - FIRST_DRAGON + 1
        MahjongSuit.FLOWER -> type - FIRST_FLOWER + 1
        MahjongSuit.SEASON -> type - FIRST_SEASON + 1
    }

    /** The Unicode Mahjong Tiles code point (U+1F000..U+1F029) of [type]. */
    fun codePoint(type: Int): Int = when (suit(type)) {
        MahjongSuit.DOTS -> 0x1F019 + type
        MahjongSuit.BAMBOO -> 0x1F010 + (type - 9)
        MahjongSuit.CHARACTERS -> 0x1F007 + (type - 18)
        MahjongSuit.WIND -> 0x1F000 + (type - FIRST_WIND)
        MahjongSuit.DRAGON -> 0x1F004 + (type - FIRST_DRAGON)
        MahjongSuit.FLOWER -> 0x1F022 + (type - FIRST_FLOWER)
        MahjongSuit.SEASON -> 0x1F026 + (type - FIRST_SEASON)
    }

    fun glyph(type: Int): String = String(Character.toChars(codePoint(type)))

    /** All 144 tiles: 34 simple types x4, the four flowers and the four seasons once each. */
    fun fullSet(): IntArray {
        val out = ArrayList<Int>(144)
        for (t in 0 until FIRST_FLOWER) repeat(4) { out.add(t) }
        for (t in FIRST_FLOWER until TYPE_COUNT) out.add(t)
        return out.toIntArray()
    }

    /** The 72 matching pairs of the full set (flowers and seasons paired in a random order). */
    fun pairPool(random: Random): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(72)
        for (t in 0 until FIRST_FLOWER) {
            out.add(t to t)
            out.add(t to t)
        }
        val flowers = (FIRST_FLOWER until FIRST_SEASON).shuffled(random)
        out.add(flowers[0] to flowers[1])
        out.add(flowers[2] to flowers[3])
        val seasons = (FIRST_SEASON until TYPE_COUNT).shuffled(random)
        out.add(seasons[0] to seasons[1])
        out.add(seasons[2] to seasons[3])
        return out
    }

    /** True when [counts] (tiles per type) never exceeds what the real set holds. */
    fun withinSet(counts: IntArray): Boolean {
        for (t in 0 until FIRST_FLOWER) if (counts[t] > 4) return false
        for (t in FIRST_FLOWER until TYPE_COUNT) if (counts[t] > 1) return false
        return true
    }
}

package com.gamesuite.rewards

/** The three themed sticker sets (tabs in the sticker book). [id] is stable and used in resource names. */
enum class StickerSet(val id: String) { ANIMALS("animals"), SPACE("space"), FOOD("food") }

/** One collectible sticker. [id] is stable (stored in the book and used in resource names `rw_st_<id>`). */
data class Sticker(val id: String, val set: StickerSet, val indexInSet: Int)

/** The 24 original stickers: 3 sets of 8. Their art is drawn in code (StickerArt.kt); names are in rewards_strings.xml. */
object StickerCatalog {
    private val animals = listOf("cat", "dog", "rabbit", "frog", "owl", "fish", "bear", "penguin")
    private val space = listOf("rocket", "planet", "star", "moon", "comet", "alien", "helmet", "ufo")
    private val food = listOf("apple", "banana", "cupcake", "pizza", "icecream", "strawberry", "cookie", "carrot")

    val all: List<Sticker> = buildList {
        StickerSet.values().zip(listOf(animals, space, food)).forEach { (set, names) ->
            names.forEachIndexed { i, n -> add(Sticker("${set.id}_$n", set, i)) }
        }
    }

    const val SET_SIZE = 8
    val size: Int get() = all.size

    private val byIdMap: Map<String, Sticker> = all.associateBy { it.id }

    fun byId(id: String): Sticker? = byIdMap[id]

    fun inSet(set: StickerSet): List<Sticker> = all.filter { it.set == set }

    /** The order stickers are handed out in: one from each set in turn, so the three tabs fill evenly. */
    val awardOrder: List<Sticker> = buildList {
        for (i in 0 until SET_SIZE) for (s in StickerSet.values()) add(all.first { it.set == s && it.indexInSet == i })
    }
}

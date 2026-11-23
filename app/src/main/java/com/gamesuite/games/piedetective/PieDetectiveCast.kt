package com.gamesuite.games.piedetective

/**
 * The twelve original animal suspects (design doc, section 2.5). Trait vector order: Hat, Glasses, Scarf, Bag, Boots,
 * Bow tie. The first four characters are unique across the cast, so any lineup is solvable with the first four traits.
 * Display names live in strings (`pie_detective_suspect_<id>`), not here.
 */
object PieCast {
    val all: List<PieSuspect> = listOf(
        PieSuspect("mouse", "mouse", 0xFFA6A6B0L, "000110"),
        PieSuspect("frog", "frog", 0xFF7CC576L, "001001"),
        PieSuspect("otter", "otter", 0xFF9C6B45L, "001111"),
        PieSuspect("pig", "pig", 0xFFF2A7B8L, "010000"),
        PieSuspect("sheep", "sheep", 0xFFF4EBDDL, "010110"),
        PieSuspect("cat", "cat", 0xFFF0A04BL, "011001"),
        PieSuspect("badger", "badger", 0xFF4A4A55L, "100011"),
        PieSuspect("rabbit", "rabbit", 0xFFE8D8C3L, "100100"),
        PieSuspect("penguin", "penguin", 0xFF2B3A55L, "101010"),
        PieSuspect("goat", "goat", 0xFFD1B48CL, "101101"),
        PieSuspect("duck", "duck", 0xFFF7D44AL, "110011"),
        PieSuspect("squirrel", "squirrel", 0xFFB5532EL, "110100")
    )

    fun byId(id: String?): PieSuspect? = all.firstOrNull { it.id == id }
}

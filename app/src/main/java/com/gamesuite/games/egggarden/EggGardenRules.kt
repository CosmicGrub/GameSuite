package com.gamesuite.games.egggarden

/** One tier of the co-op goal table: every listed condition must hold. [terrainPatches] = terrains whose biggest patch is 3 or more. */
data class CoopGoal(val eggs: Int, val patch: Int, val terrainPatches: Int)

/**
 * Every number of Egg Garden in one place (docs/design/kids-games/egg-garden.md, SCRIPT).
 * Only pure helpers live here; placement, scoring and the deck belong to [EggGardenEngine].
 */
object EggGardenRules {
    const val SAVE_VERSION = 1

    const val GRID_COMPETE = 5
    const val GRID_COOP = 7
    const val TILE_SET_SIZE = 60

    /** Tiles beyond the number of turns, so a deck never runs dry even when tiles are discarded. */
    const val DECK_SPARE = 4

    /** Biggest-patch bonus: 4 points to every gardener tied for the largest patch, if that patch has at least 3 squares. */
    const val PATCH_BONUS = 4
    const val PATCH_BONUS_MIN_SIZE = 3

    /** A peek turn comes every third turn (turns 1, 4, 7 and 10 of 12). */
    const val PEEK_EVERY = 3

    /** Co-op: the team places 24 tiles on the 7 x 7 garden (48 squares plus the nest). */
    const val COOP_TURNS = 24
    const val MIN_SEATS = 2
    const val MAX_SEATS = 4

    fun gridSize(style: GardenStyle): Int = if (style == GardenStyle.COOP) GRID_COOP else GRID_COMPETE

    /** Turns each seat takes (competitive), or the team's total (co-op). */
    fun turnsTotal(style: GardenStyle, length: GardenLength, seats: Int): Int =
        if (style == GardenStyle.COOP) COOP_TURNS else length.tilesEach * seats

    /** Tiles taken from the shuffled set for a game. 28 in co-op; 12 x seats + 4 competitive (full). */
    fun deckSize(style: GardenStyle, length: GardenLength, seats: Int): Int =
        turnsTotal(style, length, seats) + DECK_SPARE

    /** Whether [seat] draws two tiles and keeps one on its [turnIndex] (0-based turn of that seat). Co-op always peeks. */
    fun isPeekTurn(style: GardenStyle, peekFor: String, seat: Int, seatCount: Int, turnIndex: Int): Boolean {
        if (style == GardenStyle.COOP) return true
        if (turnIndex % PEEK_EVERY != 0) return false
        return when {
            peekFor == "all" -> true
            peekFor.length == 2 && peekFor[0] == 'p' && peekFor[1] in '1'..'4' -> (peekFor[1] - '1') == seat && seat < seatCount
            else -> false
        }
    }

    /** The three star tiers of the co-op goal table, easiest first. */
    fun coopGoals(band: GoalBand): List<CoopGoal> = when (band) {
        GoalBand.SEEDLING -> listOf(CoopGoal(26, 0, 0), CoopGoal(31, 7, 0), CoopGoal(35, 9, 3))
        GoalBand.SPROUT -> listOf(CoopGoal(30, 0, 0), CoopGoal(34, 9, 0), CoopGoal(38, 11, 4))
        GoalBand.BLOOM -> listOf(CoopGoal(33, 0, 0), CoopGoal(37, 11, 0), CoopGoal(40, 13, 5))
    }

    /** Stars (0..3) for a finished co-op garden: the highest tier whose every condition holds. */
    fun coopStars(band: GoalBand, eggs: Int, biggestPatch: Int, terrainPatches: Int): Int {
        var stars = 0
        coopGoals(band).forEachIndexed { i, g ->
            if (eggs >= g.eggs && biggestPatch >= g.patch && terrainPatches >= g.terrainPatches) stars = i + 1
        }
        return stars
    }

    /** The bonus a garden earns: [PATCH_BONUS] if its biggest patch is the (tied) largest of all gardens and at least 3. */
    fun patchBonus(myBiggest: Int, allBiggest: List<Int>): Int {
        val top = allBiggest.maxOrNull() ?: return 0
        return if (myBiggest == top && top >= PATCH_BONUS_MIN_SIZE) PATCH_BONUS else 0
    }
}

package com.gamesuite.games.egggarden

import com.gamesuite.settings.CpuDifficulty

// Data model of Egg Garden (id `egg-garden`). Pure Kotlin: no Compose, no Android. Rules and numbers are in
// docs/design/kids-games/egg-garden.md; the numbers live in [EggGardenRules]. Grid coordinates are (row, col), row 0 on top.
// Nothing in this file has been compiled or run.

/**
 * The five terrains. Every one has its own colour AND shape, so colour is never the only cue:
 * [shapeName] is the glyph drawn in the corner of the square (tuft of grass, ripples, tree, flower, peak).
 */
enum class Terrain(val id: String, val code: Char, val shapeName: String, val colorLight: Long) {
    MEADOW("meadow", 'M', "grass tuft", 0xFF7CB342),
    POND("pond", 'P', "ripples", 0xFF1E88E5),
    WOODS("woods", 'W', "tree", 0xFF6D4C41),
    BLOSSOM("blossom", 'B', "flower", 0xFFEC407A),
    HILL("hill", 'H', "peak", 0xFF9E9E9E);

    companion object {
        fun fromId(id: String?): Terrain? = entries.firstOrNull { it.id == id }
    }
}

/** A two-square tile. Square A and square B each have a terrain and 0 to 2 eggs. A tile never shows more than 3 eggs. */
data class EggTile(val id: String, val a: Terrain, val b: Terrain, val eggsA: Int, val eggsB: Int) {
    val eggs: Int get() = eggsA + eggsB
}

enum class GardenStyle(val id: String) {
    COMPETE("compete"), COOP("coop");

    companion object {
        fun fromId(id: String?): GardenStyle = entries.firstOrNull { it.id == id } ?: COMPETE
    }
}

/** Tiles each gardener places in a competitive game. */
enum class GardenLength(val id: String, val tilesEach: Int) {
    SHORT("short", 8), FULL("full", 12);

    companion object {
        fun fromId(id: String?): GardenLength = entries.firstOrNull { it.id == id } ?: FULL
    }
}

/** Which goal table the co-op garden is measured against. */
enum class GoalBand(val id: String) {
    SEEDLING("seedling"), SPROUT("sprout"), BLOOM("bloom");

    companion object {
        fun fromId(id: String?): GoalBand = entries.firstOrNull { it.id == id } ?: SEEDLING
    }
}

data class CellPos(val row: Int, val col: Int)

/**
 * Where a tile goes. [second] must be the orthogonal neighbour of [first]. The tile's square A lies on [first] and square B
 * on [second], unless [flipped], which swaps them. (Turning the tile = choosing another of the four ways to put the pair down.)
 */
data class Placement(val first: CellPos, val second: CellPos, val flipped: Boolean)

enum class PlaceVerdict {
    OK,

    /** Legal only through the free-spot rule (no matching spot existed, or Free placing is on). */
    OK_FREE_SPOT,
    OUT_OF_BOUNDS,
    CELL_TAKEN,
    NOT_A_PAIR,
    NOT_CONNECTED,
    NO_MATCH,
    WRONG_PHASE
}

/** One garden square as the screen sees it. [terrain] is null for an empty square and for the nest. */
data class GardenCell(val terrain: Terrain?, val isNest: Boolean, val eggs: Int)

data class GardenView(val size: Int, val cells: List<GardenCell>) {
    fun at(row: Int, col: Int): GardenCell = cells[row * size + col]
}

/** [total] = [eggs] + [patchBonus]. [biggestPatch] is the size of the largest single-terrain patch (the nest never joins one). */
data class GardenScore(val eggs: Int, val biggestPatch: Int, val patchBonus: Int, val total: Int, val squares: Int)

/** The state machine. SETUP and START are screen states; the engine begins in [DRAW] once started. */
enum class GardenPhase { SETUP, START, DRAW, PEEK, PLACE, MATCH_DONE }

data class EggGardenConfig(
    val style: GardenStyle = GardenStyle.COMPETE,
    val length: GardenLength = GardenLength.FULL,
    val goalBand: GoalBand = GoalBand.SEEDLING,
    /** Which seats get peek turns: "none", "all" or "p1".."p4". Co-op always peeks. */
    val peekFor: String = "none",
    /** Free placing: only the connect rule applies. */
    val freePlacing: Boolean = false,
    val difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
    val seed: Long = 0L
)

data class GardenSeat(val playerId: String, val name: String, val isBot: Boolean, val colorIndex: Int)

data class EggGardenState(
    val phase: GardenPhase,
    val style: GardenStyle,
    val activeSeat: Int,
    /** 1-based turn of the active gardener (competitive) or of the team (co-op). */
    val turn: Int,
    val turnsTotal: Int,
    /** The tile to place (PLACE) or null. */
    val drawn: EggTile?,
    /** The two tiles to choose from (PEEK) or empty. */
    val peekTiles: List<EggTile>,
    val gardens: List<GardenView>,
    val scores: List<GardenScore>,
    val deckLeft: Int,
    /** True when the active tile can only go through the free-spot rule. */
    val freeSpotOnly: Boolean,
    val lastVerdict: PlaceVerdict?,
    /** Co-op stars so far (3 tiers), 0 for competitive. */
    val stars: Int
) {
    companion object {
        val EMPTY = EggGardenState(
            phase = GardenPhase.SETUP, style = GardenStyle.COMPETE, activeSeat = 0, turn = 0, turnsTotal = 0,
            drawn = null, peekTiles = emptyList(), gardens = emptyList(), scores = emptyList(), deckLeft = 0,
            freeSpotOnly = false, lastVerdict = null, stars = 0
        )
    }
}

/** How a finished match ended. [winners] holds every seat that shares the win. */
data class EggGardenSummary(
    val winners: List<Int>,
    val scores: List<GardenScore>,
    val stars: Int,
    val cleared: Boolean,
    /** Which tie-break rung decided it: "score", "eggs", "patch", "squares" or "shared". */
    val decidedBy: String
)

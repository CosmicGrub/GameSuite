package com.gamesuite.games.egggarden

import com.gamesuite.settings.CpuDifficulty

/**
 * The Egg Garden rules, with no Compose and no Android. Turn-based and fully deterministic on an injected seed: the same seed,
 * options and list of choices always give the same deck, the same garden and the same score.
 *
 * Contract (all of it is TODO in the stub):
 *  - [configure] builds the deck as the first [EggGardenRules.deckSize] tiles of a seeded shuffle of the 60-tile set, and one
 *    garden per seat (5 x 5 competitive, one shared 7 x 7 in co-op), each with the nest in the middle.
 *  - A turn: [draw] (to PLACE, or to PEEK on a peek turn), [keepPeek] on a peek turn (the other tile goes to the bottom of
 *    the deck), then [place]; or [skipUnplaceable] when [legalPlacements] is empty (the tile is discarded and the turn is spent).
 *  - Legal placement: both squares in the grid and empty; at least one square touches an occupied square or the nest; and at
 *    least one square touches the same terrain or the nest. Square pairs inside one tile never count. If no placement
 *    satisfies the match rule (or Free placing is on) the free-spot rule applies: connecting is enough ([PlaceVerdict.OK_FREE_SPOT]).
 *  - A rejected [place] changes nothing.
 *  - Score: eggs on placed squares, plus [EggGardenRules.PATCH_BONUS] to every gardener tied for the largest patch (at least 3).
 *    Ties: more eggs, then bigger patch, then more squares filled, then a shared win.
 *  - [stateHash] covers every garden, the deck order, the turn and the RNG state.
 */
interface EggGardenEngine {
    /** False (nothing changed) when the config does not fit the seats (2 to 4). */
    fun configure(config: EggGardenConfig, seats: List<GardenSeat>, tiles: List<EggTile> = EggGardenTileData.TILES): Boolean

    fun start()

    fun state(): EggGardenState

    /** DRAW to PLACE (or PEEK). Returns the tile drawn (the first of two on a peek turn), or null when not in DRAW. */
    fun draw(): EggTile?

    /** On a peek turn: keep tile 0 or 1 of [EggGardenState.peekTiles]. False when not in PEEK or the index is bad. */
    fun keepPeek(index: Int): Boolean

    /** Every legal placement of the drawn tile, each of the four orientations listed once, row-major by first square. */
    fun legalPlacements(): List<Placement>

    /** Judges [placement] for the drawn tile without changing anything. */
    fun verdict(placement: Placement): PlaceVerdict

    /** Places the tile. Returns [PlaceVerdict.OK] or [PlaceVerdict.OK_FREE_SPOT] on success; anything else changed nothing. */
    fun place(placement: Placement): PlaceVerdict

    /** Discards the drawn tile when there is no legal spot. False when a legal spot exists. */
    fun skipUnplaceable(): Boolean

    fun score(seat: Int): GardenScore

    fun summary(): EggGardenSummary?

    fun stateHash(): Long

    fun toSave(): EggGardenSave?

    /** Replays [save] from its seed. False (engine untouched) when it is invalid for these seats. */
    fun restore(save: EggGardenSave): Boolean
}

/** The scaffold engine: every call returns a safe value and changes nothing. Replace in phase 1. */
class StubEggGardenEngine : EggGardenEngine {
    override fun configure(config: EggGardenConfig, seats: List<GardenSeat>, tiles: List<EggTile>): Boolean = false
    override fun start() {}
    override fun state(): EggGardenState = EggGardenState.EMPTY
    override fun draw(): EggTile? = null
    override fun keepPeek(index: Int): Boolean = false
    override fun legalPlacements(): List<Placement> = emptyList()
    override fun verdict(placement: Placement): PlaceVerdict = PlaceVerdict.WRONG_PHASE
    override fun place(placement: Placement): PlaceVerdict = PlaceVerdict.WRONG_PHASE
    override fun skipUnplaceable(): Boolean = false
    override fun score(seat: Int): GardenScore = GardenScore(eggs = 0, biggestPatch = 0, patchBonus = 0, total = 0, squares = 0)
    override fun summary(): EggGardenSummary? = null
    override fun stateHash(): Long = 0L
    override fun toSave(): EggGardenSave? = null
    override fun restore(save: EggGardenSave): Boolean = false
}

/**
 * The computer gardener. Noise is injected from the seed so a replay plays the same match.
 *
 * Placement heuristic (docs: SCRIPT > Bot behaviour): value = 2.0 x same-terrain contacts (nest counts 0.5) + 3.0 x growth of
 * the biggest patch - 4.0 x squares left with no empty neighbour. EASY picks a random legal placement; MEDIUM adds
 * gaussian noise (sigma 2.5) to the value; HARD takes the best value. On a peek turn the bot also adds 3 per egg on the tile
 * and subtracts 5 for a free-spot-only tile, and keeps the better of the two.
 */
interface EggGardenBot {
    /** The index (0 or 1) of the peek tile to keep. */
    fun choosePeek(engine: EggGardenEngine, difficulty: CpuDifficulty, seed: Long): Int

    /** The placement to play, or null to discard (no legal spot). */
    fun choosePlacement(engine: EggGardenEngine, difficulty: CpuDifficulty, seed: Long): Placement?
}

/** The scaffold bot: keeps the first tile and discards. */
class StubEggGardenBot : EggGardenBot {
    override fun choosePeek(engine: EggGardenEngine, difficulty: CpuDifficulty, seed: Long): Int = 0
    override fun choosePlacement(engine: EggGardenEngine, difficulty: CpuDifficulty, seed: Long): Placement? = null
}

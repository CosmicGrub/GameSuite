package com.gamesuite.games.egggarden

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One finished turn, enough to replay it. [peekKeep] is 0 or 1 on a peek turn and -1 otherwise. When [discarded] the
 * placement fields are 0 and ignored.
 */
@Serializable
data class EggLogEntry(
    val seat: Int,
    val peekKeep: Int,
    val r1: Int,
    val c1: Int,
    val r2: Int,
    val c2: Int,
    val flipped: Boolean,
    val discarded: Boolean
)

@Serializable
data class GardenSeatSave(val playerId: String, val isBot: Boolean)

/**
 * The snapshot DTO: the match settings, the seed and the log of every turn so far. The state is rebuilt by replaying the log
 * from the seed and then checked against [stateHash], so a save from another rules version is rejected, never repaired.
 */
@Serializable
data class EggGardenSave(
    val version: Int = EggGardenRules.SAVE_VERSION,
    val style: String,
    val length: String,
    val goalBand: String,
    val peekFor: String,
    val freePlacing: Boolean,
    val difficulty: String,
    val seed: Long,
    val seats: List<GardenSeatSave>,
    val log: List<EggLogEntry>,
    val stateHash: Long
)

/**
 * Encodes, decodes and validates saves. Pure; decoding never throws.
 *
 * TODO(phase 1), in [isValid]:
 *  - version == [EggGardenRules.SAVE_VERSION]
 *  - seats.size in 2..4 and equals the roster; ids and isBot flags match the roster one by one
 *  - style, length, goalBand, difficulty are known ids; peekFor is none, all or p1..p4
 *  - log.size <= 24 + 4 (never more turns than the game has); every seat index is in the roster; peekKeep in -1..1;
 *    every non-discarded entry has both squares inside the grid and orthogonally adjacent
 *  - turns follow the seat order from the first mover (competitive) and never exceed tilesEach per seat
 *  - after replaying, the engine's stateHash equals [EggGardenSave.stateHash] (done by the engine, not here)
 */
object EggGardenSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(save: EggGardenSave): String = json.encodeToString(EggGardenSave.serializer(), save)

    fun decode(text: String, seats: List<GardenSeat>): EggGardenSave? = try {
        val s = json.decodeFromString(EggGardenSave.serializer(), text)
        if (isValid(s, seats)) s else null
    } catch (_: Exception) {
        null
    }

    /** Scaffold: only the version and roster-size gates are enforced. The rest of the list above is TODO. */
    fun isValid(save: EggGardenSave, seats: List<GardenSeat>): Boolean {
        if (save.version != EggGardenRules.SAVE_VERSION) return false
        if (save.seats.size != seats.size) return false
        return true
    }
}

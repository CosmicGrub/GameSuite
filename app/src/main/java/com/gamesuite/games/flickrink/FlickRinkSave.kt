package com.gamesuite.games.flickrink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One finished flick, enough to replay it. [rink] is the 0-based rink of the match, [seat] who flicked.
 */
@Serializable
data class FlickLogEntry(val rink: Int, val seat: Int, val angleDeci: Int, val power: Int)

@Serializable
data class FlickSeatSave(val playerId: String, val isBot: Boolean)

/**
 * The snapshot DTO. A save is the match settings, the seed and the log of every flick so far: the state is rebuilt by
 * replaying the log, then checked against [stateHash]. That keeps the save tiny (under 2 KB for a whole match) and makes a
 * physics change impossible to miss: a different [physicsVersion] or hash rejects the save.
 */
@Serializable
data class FlickSave(
    val version: Int = FlickRinkRules.SAVE_VERSION,
    val physicsVersion: Int = FlickRinkRules.PHYSICS_VERSION,
    val style: String,
    val kind: String,
    val band: String,
    val length: String,
    val relaxed: Boolean,
    val dragAnywhere: Boolean,
    val switchAiming: Boolean,
    val assistFor: String,
    val assist: String,
    val difficulty: String,
    val budget: String,
    val soloRink: Int,
    val seed: Long,
    val seats: List<FlickSeatSave>,
    val coopRetries: Int,
    val log: List<FlickLogEntry>,
    val stateHash: Long
)

/**
 * Encodes, decodes and validates saves. Pure. A save that does not fit the roster or the rules is rejected, never repaired,
 * and decoding never throws.
 *
 * TODO(phase 1), in [isValid]:
 *  - version == [FlickRinkRules.SAVE_VERSION] and physicsVersion == [FlickRinkRules.PHYSICS_VERSION]
 *  - seats.size in 1..4 and equals the roster size; ids and isBot flags match the roster one by one
 *  - style, kind, band, length, assist, difficulty are known ids; assistFor is none, all or p1..p4; budget is plenty, normal or tight
 *  - soloRink in 1..14; the TURNS style needs 2 or more seats, SOLO exactly 1
 *  - log.size <= 400; every entry has rink in 0 until rinkCount, seat in the roster, angleDeci in 0..3599, power in 0..100
 *  - entries are in non-decreasing rink order; coopRetries in 0..20
 *  - after replaying, the engine's stateHash equals [FlickSave.stateHash] (done by the engine, not here)
 */
object FlickSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(save: FlickSave): String = json.encodeToString(FlickSave.serializer(), save)

    fun decode(text: String, seats: List<FlickSeat>): FlickSave? = try {
        val s = json.decodeFromString(FlickSave.serializer(), text)
        if (isValid(s, seats)) s else null
    } catch (_: Exception) {
        null
    }

    /** Scaffold: only the version gates are enforced. The rest of the list above is TODO. */
    fun isValid(save: FlickSave, seats: List<FlickSeat>): Boolean {
        if (save.version != FlickRinkRules.SAVE_VERSION) return false
        if (save.physicsVersion != FlickRinkRules.PHYSICS_VERSION) return false
        if (save.seats.size != seats.size) return false
        return true
    }
}

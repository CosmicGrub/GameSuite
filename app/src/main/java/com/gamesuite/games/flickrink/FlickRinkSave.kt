package com.gamesuite.games.flickrink

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One finished flick, enough to replay it. [rink] is the 0-based rink of the match, [seat] who flicked, [launchX] the bullseye
 * launch point. A [seat] of -1 is not a flick but a move between rinks: [angleDeci] 0 = next rink, 1 = retry (co-op).
 */
@Serializable
data class FlickLogEntry(val rink: Int, val seat: Int, val angleDeci: Int, val power: Int, val launchX: Int = FlickRinkRules.LAUNCH_DEFAULT_X)

@Serializable
data class FlickSeatSave(val playerId: String, val isBot: Boolean)

/**
 * The snapshot DTO. A save is the match settings, the seed and the log of every flick so far: the state is rebuilt by
 * replaying the log, then checked against [stateHash]. That keeps the save tiny (a whole match is a few KB at most) and makes a
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
    val stateHash: Long,
    val firstSeat: Int = 0
)

/**
 * Encodes, decodes and validates saves. Pure. A save that does not fit the roster or the rules is rejected, never repaired,
 * and decoding never throws.
 *
 * [isValid] enforces:
 *  - version and physicsVersion equal the current ones
 *  - seats.size in 1..4 and equals the roster size; ids and isBot flags match the roster one by one
 *  - style, kind, band, length, assist, difficulty are known ids; assistFor is none, all or p1..p4; budget is plenty, normal or tight
 *  - soloRink in 1..14; Turns needs 2 or more seats, Co-op 2 or more (fish run only), Solo exactly 1; firstSeat inside the roster
 *  - log.size <= 400; every flick has rink in 0 until the rink count, a seat in the roster, angleDeci 0..3599, power 0..100 and
 *    launchX 80..520; a move entry (seat -1) has angleDeci 0 or 1, power 0
 *  - entries are in non-decreasing rink order; coopRetries in 0..20
 *  - after replaying, the engine's stateHash equals [FlickSave.stateHash] (checked by the engine, not here)
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

    /** The match settings a save holds (the screen-only settings are part of the save too, so a resume plays as it was set up). */
    fun configOf(s: FlickSave): FlickConfig = FlickConfig(
        style = FlickStyle.fromId(s.style), kind = FlickGameKind.fromId(s.kind), band = FlickBand.fromId(s.band),
        length = FlickLength.fromId(s.length), relaxed = s.relaxed, dragAnywhere = s.dragAnywhere, switchAiming = s.switchAiming,
        assistFor = s.assistFor, assist = AimAssist.fromId(s.assist),
        difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: CpuDifficulty.MEDIUM,
        soloRink = s.soloRink, budget = s.budget, seed = s.seed, firstSeat = s.firstSeat
    )

    fun isValid(save: FlickSave, seats: List<FlickSeat>): Boolean {
        if (save.version != FlickRinkRules.SAVE_VERSION) return false
        if (save.physicsVersion != FlickRinkRules.PHYSICS_VERSION) return false
        if (save.seats.size !in 1..FlickRinkRules.MAX_SEATS || save.seats.size != seats.size) return false
        for (i in seats.indices) {
            if (save.seats[i].playerId != seats[i].playerId || save.seats[i].isBot != seats[i].isBot) return false
        }
        if (!FlickStyle.isKnown(save.style) || !FlickGameKind.isKnown(save.kind) || !FlickBand.isKnown(save.band)) return false
        if (!FlickLength.isKnown(save.length) || !AimAssist.isKnown(save.assist)) return false
        if (CpuDifficulty.entries.none { it.name == save.difficulty }) return false
        if (!validAssistFor(save.assistFor)) return false
        if (save.budget != "plenty" && save.budget != "normal" && save.budget != "tight") return false
        if (save.soloRink !in 1..FlickRinkRules.RINK_COUNT) return false
        if (save.firstSeat !in 0 until seats.size) return false
        val style = FlickStyle.fromId(save.style)
        val kind = FlickGameKind.fromId(save.kind)
        when (style) {
            FlickStyle.TURNS -> if (seats.size < 2) return false
            FlickStyle.COOP -> if (seats.size < 2 || kind != FlickGameKind.FISH_RUN) return false
            FlickStyle.SOLO -> if (seats.size != 1) return false
        }
        if (save.coopRetries !in 0..FlickRinkRules.MAX_COOP_RETRIES) return false
        if (save.log.size > FlickRinkRules.MAX_LOG) return false
        val rinkCount = if (style == FlickStyle.SOLO) 1 else FlickLength.fromId(save.length).rinks
        var lastRink = 0
        for (e in save.log) {
            if (e.rink !in 0 until rinkCount || e.rink < lastRink) return false
            lastRink = e.rink
            if (e.seat == -1) {
                if (e.angleDeci != FlickMatch.ADVANCE_NEXT && e.angleDeci != FlickMatch.ADVANCE_RETRY) return false
                if (e.power != 0) return false
            } else {
                if (e.seat !in 0 until seats.size) return false
                if (e.angleDeci !in 0..3599 || e.power !in 0..100) return false
                if (e.launchX !in FlickRinkRules.LAUNCH_MIN_X..FlickRinkRules.LAUNCH_MAX_X) return false
            }
        }
        return true
    }

    private fun validAssistFor(s: String): Boolean =
        s == "none" || s == "all" || (s.length == 2 && s[0] == 'p' && s[1] in '1'..'4')
}

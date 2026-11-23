package com.gamesuite.games.propertytycoon

import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a saved game holds: the whole [TycoonState] (which carries the RNG state, so dice and shuffles continue
 * exactly) plus the session tally and the computer level. [version] guards future format changes: restore
 * must refuse a version it does not know.
 */
@Serializable
data class PropertyTycoonSnapshot(
    val version: Int = VERSION,
    val state: TycoonState,
    val sessionWins: Map<String, Int> = emptyMap(),
    val difficulty: String = "MEDIUM",
    val nextStartingSeat: Int = 0
) {
    companion object {
        const val VERSION = 1
    }
}

internal val propertyTycoonJson = Json { ignoreUnknownKeys = true }

/**
 * Validates a decoded snapshot against the roster that is resuming it and returns the state to continue
 * from, or null to refuse (the Hub then offers a fresh game; a bad save must never crash or cheat).
 *
 * TODO(phase P0/P1): implement every check below, each with a PropertyTycoonSnapshotTest case.
 *  - version == [PropertyTycoonSnapshot.VERSION]; player count in 2..6 and equal to the roster; playerId and isBot
 *    match the roster seat by seat; seat fields equal their list index.
 *  - cash >= 0 (or the player is bankrupt with cash 0); position in 0..39; timeoutTurns in 0..2 and only with
 *    inTimeout; doublesStreak in 0..2; freePassCards >= 0 and the total held plus deck-resident equals 2.
 *  - holdings: unique tile indexes, every one ownable, owner a non-bankrupt seat, buildings only on PROPERTY tiles,
 *    in 0..5, never on a pledged tile, never with an incomplete colour, even-build holds in every colour, and
 *    cabinsLeft/resortsLeft equal supply minus buildings in play (resort = 1 resort, 0 cabins).
 *  - decks: tideDraw + tideDiscard + held cards is exactly the 16 tide ids once each (same for guild).
 *  - phase consistency: AWAIT_DEBT needs debt != null, AWAIT_AUCTION needs auction != null, AWAIT_BUY needs landedTile on
 *    an unowned ownable tile, AWAIT_CARD_ACK needs pendingCardId in the decks, GAME_OVER iff over, phaseStack length <= 3
 *    and never contains an interrupt phase twice; currentSeat is not bankrupt unless over.
 *  - auction/debt/trade seats exist and are active; trade tiles are owned by the stated side; trade ids < nextTradeId.
 *  - round >= 1 and <= the length's roundCap; jackpot >= 0 and only when the house rule is on.
 *  - sessionWins keys are roster ids with values >= 0; nextStartingSeat in range.
 */
object PropertyTycoonSnapshotCodec {
    fun encode(snapshot: PropertyTycoonSnapshot): String =
        propertyTycoonJson.encodeToString(PropertyTycoonSnapshot.serializer(), snapshot)

    /** Decoded snapshot, or null when the text is not valid JSON of the expected shape. */
    fun decode(text: String): PropertyTycoonSnapshot? = try {
        propertyTycoonJson.decodeFromString(PropertyTycoonSnapshot.serializer(), text)
    } catch (_: Exception) {
        null
    }

    /** Stub: refuses every snapshot until the validation above exists. */
    fun validate(snapshot: PropertyTycoonSnapshot, roster: List<PlayerInfo>): TycoonState? = null
}

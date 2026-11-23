package com.gamesuite.core

import com.gamesuite.profiles.Profile
import com.gamesuite.profiles.ProfileKind

/** Who a game is meant for. ADULTS = adult profiles only; FAMILY = shared by adults and kids; KIDS = made for children. */
enum class GameAudience { ADULTS, FAMILY, KIDS }

/**
 * Games a KID profile never sees, whatever their audience tag says: simulated-gambling themes
 * (docs/audit/03-safety-privacy.md section 8). Adults still see them.
 */
val KID_BLOCKED_GAME_IDS: Set<String> = setOf("blackjack", "texas-holdem", "slot-cars", "hanafuda", "pig-dice")

/** The audience a spec effectively has: its own tag if it set one, else the [GameAudienceTable] entry, else ADULTS. */
fun effectiveAudience(spec: GameHubSpec): GameAudience =
    if (spec.audience != GameAudience.ADULTS) spec.audience
    else GameAudienceTable.entryFor(spec.gameId)?.audience ?: GameAudience.ADULTS

/** The effective minimum age: the spec's own value if set (> 0), else the table's, else 0. */
fun effectiveMinAge(spec: GameHubSpec): Int =
    if (spec.minAge > 0) spec.minAge else GameAudienceTable.entryFor(spec.gameId)?.minAge ?: 0

/** [spec] without its Nearby / Online modes (and with a valid default mode). */
fun GameHubSpec.withoutNetworkModes(): GameHubSpec {
    if (modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE }) return this
    val kept = modes.filter { it.kind != HubModeKind.NEARBY && it.kind != HubModeKind.ONLINE }
    if (kept.isEmpty()) return this
    return copy(modes = kept, defaultModeId = if (kept.any { it.id == defaultModeId }) defaultModeId else kept.first().id)
}

/** Whether a KID profile may see [spec] at all (before the parent's per-game toggles). */
fun kidMaySee(spec: GameHubSpec, maxAge: Int): Boolean =
    spec.gameId !in KID_BLOCKED_GAME_IDS &&
        effectiveAudience(spec) != GameAudience.ADULTS &&
        effectiveMinAge(spec) <= maxAge

/**
 * The specs [profile] may see, in the original order.
 *  - ADULT: everything; Nearby/Online modes are stripped only when [onlineEnabled] is false.
 *  - KID: only KIDS/FAMILY games with minAge <= the age band's top age, never the [KID_BLOCKED_GAME_IDS],
 *    never a game in [hiddenGameIds] (the parent's per-game toggles), and never any Nearby/Online mode.
 */
fun visibleFor(
    profile: Profile,
    specs: List<GameHubSpec>,
    onlineEnabled: Boolean = true,
    hiddenGameIds: Set<String> = emptySet()
): List<GameHubSpec> = when (profile.kind) {
    ProfileKind.ADULT -> if (onlineEnabled) specs else specs.mapNotNull { it.withoutNetworkModes().takeUnless { s -> s.isNetworked } }
    ProfileKind.KID -> {
        val maxAge = profile.ageBand?.maxAge ?: com.gamesuite.profiles.AgeBand.AGE_4_6.maxAge
        // A spec whose modes are ALL Nearby / Online keeps them after stripping (withoutNetworkModes cannot leave a
        // spec with no mode), so it is dropped here instead of showing a network-only Hub.
        specs.filter { kidMaySee(it, maxAge) && it.gameId !in hiddenGameIds }
            .mapNotNull { it.withoutNetworkModes().takeUnless { s -> s.isNetworked } }
    }
}

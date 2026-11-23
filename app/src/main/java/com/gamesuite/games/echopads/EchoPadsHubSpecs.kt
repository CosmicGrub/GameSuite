package com.gamesuite.games.echopads

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Echo Pads, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (see integration-notes.md in this folder). Every text below has a `hubKey(text)` string
 * in res/values{,-es}/echo_pads_hub_strings.xml (or already exists elsewhere); `EchoPadsHubSpecsTest` proves it.
 *
 * Routes: Solo and Pass and play open "echo-pads" (a lone player is Solo), the head-to-head duel opens
 * "echo-pads-duel", so the screen knows which board to draw. This is a SAME-DEVICE game: do NOT list its id in
 * `GameHubSpecs.networkedGames`.
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `speed` ("slow" | "normal" | "fast"), `forgiving`
 * ("true" | "false"), `sound` ("true" | "false"), and for the duel `orient`. Seat counts use the standard `humans` option.
 */
object EchoPadsHubSpecs {
    const val GAME_ID = "echo-pads"
    const val ROUTE = "echo-pads"
    const val DUEL_ROUTE = "echo-pads-duel"

    private val speed = HubOption.Choice(
        key = "speed", label = "Pattern speed", default = "normal",
        help = "How fast the pads light up. The game speed in Settings changes it too.",
        choices = listOf(
            HubChoice("slow", "Slow", "Long, gentle flashes: best for little ones."),
            HubChoice("normal", "Normal", "A steady pace."),
            HubChoice("fast", "Fast", "Quick flashes for sharp memories.")
        )
    )

    private val forgiving = HubOption.Toggle(
        key = "forgiving", label = "Easy mode", default = false,
        help = "Every turn gets one free try: the first wrong pad just shows the pattern again."
    )

    private val sound = HubOption.Toggle(
        key = "sound", label = "Sound", default = true,
        help = "Each pad plays its own note. Switch off to play with lights and shapes only."
    )

    private val orient = HubOption.Toggle(
        key = "orient", label = "Turn halves to face players", default = true,
        help = "Each player's half turns to face them. Switch off to keep both halves upright."
    )

    val echoPads: GameHubSpec = GameHubSpec(
        gameId = GAME_ID, title = "Echo Pads",
        tagline = "Watch the lights, hear the notes, then echo the pattern back.",
        family = HubFamily.ARCADE_PUZZLE, resumable = true,
        audience = GameAudience.KIDS, minAge = 4,
        modes = listOf(
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Solo",
                blurb = "Copy a pattern that grows by one every round. How long can you go?",
                route = ROUTE, start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(speed, forgiving, sound)
            ),
            HubMode(
                id = "party", kind = HubModeKind.PASS_AND_PLAY, title = "Pass and play",
                blurb = "Take turns copying the pattern. Miss and you are out. The last one in wins.",
                route = ROUTE, start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 6,
                options = listOf(HubOptions.humans(2, 6, 2), speed, forgiving, sound)
            ),
            HubMode(
                id = "duel", kind = HubModeKind.PASS_AND_PLAY, title = "Head to head",
                blurb = "Two players, one screen, a half each. Copy the shared pattern in turn: whoever slips up loses.",
                route = DUEL_ROUTE, start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 2,
                options = listOf(speed, forgiving, sound, orient)
            )
        )
    )

    val all: List<GameHubSpec> = listOf(echoPads)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_echo_pads_1..3` strings
     * in values/echo_pads_strings.xml; values-es/ has the Spanish.
     */
    val tips: Map<String, List<String>> = mapOf(
        GAME_ID to listOf(
            "Watch and listen while the pads light up, then tap the same pads in the same order.",
            "Every pad has its own shape, colour and note, so you can learn them by sight or by ear.",
            "Little ones? Turn on Easy mode and Slow speed in the setup for a free second try."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        GAME_ID to (0xFF5D4DB8L to 0xFFB3A8F5L)
    )
}

package com.gamesuite.games.trailtogether

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
 * The Game Hub spec of the cooperative Trail Together, kept next to the game. NOT registered anywhere: the
 * coordinator adds it to `GameHubSpecs.baseSpecs()` (design doc, section 4.3). NOT networked: do not list
 * "trail-together" in `GameHubSpecs.networkedGames`.
 *
 * The texts below are English literals. Registration also needs `trail_together_hub_strings.xml` in both locales
 * (hubKey style) or HubLocalizationTest will fail (design doc, risk R9).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `band` (little | medium | big), `handicap` (none | p1..p4),
 * `hints`, `rotate`. The helper pals are the computer seats (`cpus`) and `difficulty` is their level.
 */
object TrailTogetherHubSpecs {
    private val band = HubOption.Choice(
        key = "band", label = "Trail size", default = "medium",
        help = "How long the trail is, how many colours it has and how fast the fog comes.",
        choices = listOf(
            HubChoice("little", "Little trail", "A short trail with four colours: great for the youngest players."),
            HubChoice("medium", "Medium trail", "A good mix for most families."),
            HubChoice("big", "Big trail", "A long trail with six colours for practised players.")
        )
    )
    private val handicap = HubOption.Choice(
        key = "handicap", label = "Extra card for", default = "none",
        help = "Give one player a fourth card so different ages can play side by side.",
        choices = listOf(
            HubChoice("none", "Nobody, all equal"),
            HubChoice("p1", "Player 1"),
            HubChoice("p2", "Player 2"),
            HubChoice("p3", "Player 3"),
            HubChoice("p4", "Player 4")
        )
    )
    private val hints = HubOption.Toggle(
        key = "hints", label = "Show helper glow", default = true,
        help = "A soft glow on a good card. The Help me choose button always works, even when this is off."
    )
    private val rotate = HubOption.Toggle(
        key = "rotate", label = "Turn the far hands around", default = true,
        help = "Players sitting opposite each other read their own cards the right way up. Switch off for one person holding the device."
    )
    private val helperLevel = HubOptions.difficulty().copy(label = "Helper pal level")

    val spec: GameHubSpec = GameHubSpec(
        gameId = "trail-together", title = "Trail Together",
        tagline = "Light the trail and get home before the fog!",
        family = HubFamily.BOARD,
        audience = GameAudience.KIDS, minAge = 4,
        modes = listOf(
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Play on my own",
                blurb = "You move two hedgehog pals home along the trail.",
                route = "trail-together", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(band, hints)
            ),
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "Play with helper pals",
                blurb = "You and 1 to 3 friendly helper pals get everyone home together.",
                route = "trail-together", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(band, HubOptions.cpus(1, 3, 1).copy(label = "Helper pals", help = "Helper pals take their own turns, so a small team always has a full table."), helperLevel, hints)
            ),
            HubMode(
                id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                blurb = "Two to four people play together on one screen. Helper pals can fill empty seats.",
                route = "trail-together", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    band, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0).copy(label = "Helper pals", help = "Helper pals take their own turns, so a small team always has a full table."),
                    helperLevel, hints, handicap, rotate
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "trail-together" to (0xFF2E6B3FL to 0xFF8FD6A0L)
    )
}

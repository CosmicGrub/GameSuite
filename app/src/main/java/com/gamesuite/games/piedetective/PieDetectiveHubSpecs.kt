package com.gamesuite.games.piedetective

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
 * The Game Hub spec of the cooperative Pie Detective, kept next to the game. NOT registered anywhere: the coordinator
 * adds it to `GameHubSpecs.baseSpecs()` (design doc, section 4.3). NOT networked: do not list "pie-detective" in
 * `GameHubSpecs.networkedGames`.
 *
 * The texts below are English literals. Registration also needs `pie_detective_hub_strings.xml` in both locales
 * (hubKey style) or HubLocalizationTest will fail (design doc, risk R9).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `band` (little | medium | big), `hints`, `autoflip`,
 * `handicap` (none | p1..p4), `rotate`. The Sidekicks are the computer seats (`cpus`) and `difficulty` is their level.
 */
object PieDetectiveHubSpecs {
    private val band = HubOption.Choice(
        key = "band", label = "Case size", default = "medium",
        help = "How many animals are in the lineup, how many clues you can ask for and how fast the thief sneaks.",
        choices = listOf(
            HubChoice("little", "Little case", "Eight animals, four clues and a slow thief: great for the youngest detectives."),
            HubChoice("medium", "Medium case", "Ten animals, five clues. A good mix for most families."),
            HubChoice("big", "Big case", "Twelve animals, six clues and a quick thief for practised detectives.")
        )
    )
    private val hints = HubOption.Toggle(
        key = "hints", label = "Sidekick hints", default = true,
        help = "A soft glow on a helpful clue. The Sidekick hint button always works, even when this is off."
    )
    private val autoFlip = HubOption.Toggle(
        key = "autoflip", label = "Sidekick flips for us", default = false,
        help = "After a clue is shown, the Sidekick turns over the animals that do not match, so you only choose, peek and tell."
    )
    private val handicap = HubOption.Choice(
        key = "handicap", label = "Lucky peek for", default = "none",
        help = "Give one player a Lucky peek in each case: after it, the thief does not sneak that turn.",
        choices = listOf(
            HubChoice("none", "Nobody, all equal"),
            HubChoice("p1", "Player 1"),
            HubChoice("p2", "Player 2"),
            HubChoice("p3", "Player 3"),
            HubChoice("p4", "Player 4")
        )
    )
    private val rotate = HubOption.Toggle(
        key = "rotate", label = "Turn the lens for the far player", default = true,
        help = "A player sitting opposite sees the lens and trait buttons the right way up. Switch off for one person holding the device."
    )
    private val sidekickLevel = HubOptions.difficulty().copy(label = "Sidekick level")

    val spec: GameHubSpec = GameHubSpec(
        gameId = "pie-detective", title = "Pie Detective",
        tagline = "Find the pie thief before it reaches the back gate!",
        family = HubFamily.ARCADE_PUZZLE,
        audience = GameAudience.KIDS, minAge = 5,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "Solve it with the Sidekick",
                blurb = "You and 1 to 3 friendly Sidekicks hunt the pie thief together.",
                route = "pie-detective", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(band, HubOptions.cpus(1, 3, 1).copy(label = "Sidekicks", help = "Sidekicks take their own turns, so a small team always has a full table."), sidekickLevel, hints, autoFlip)
            ),
            HubMode(
                id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                blurb = "Two to four detectives pass the device around. Sidekicks can fill empty seats.",
                route = "pie-detective", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    band, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0).copy(label = "Sidekicks", help = "Sidekicks take their own turns, so a small team always has a full table."),
                    sidekickLevel, hints, autoFlip, handicap, rotate
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "pie-detective" to (0xFF8A2F5BL to 0xFFF2A6C8L)
    )
}

package com.gamesuite.games.toyboxing

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Toy Boxing and Toy Baseball, kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim) and lists both ids in
 * `GameHubSpecs.networkedGames` (both engines are host-authoritative; the Nearby and Online modes are added to
 * the specs automatically). Every text below has a `hubKey(text)` string in
 * res/values{,-es}/toy_boxing_strings.xml and toy_baseball_strings.xml (or already exists elsewhere);
 * `ToyBoxingBaseballHubSpecsTest` proves it.
 *
 * Option keys reach the game screens as `LaunchOptions.extras`: Toy Boxing reads `rounds` ("1" | "3" | "5"),
 * `knockouts` ("true" | "false") and `assist` ("true"); Toy Baseball reads `innings` ("3" | "5" | "9"). The
 * "who moves first" choice becomes `LaunchOptions.firstMoverIndex`, which Toy Baseball uses as the seat that bats
 * first (the away side).
 */
object ToyBoxingBaseballHubSpecs {
    val boxing: GameHubSpec = run {
        val rounds = HubOption.Choice(
            key = "rounds", label = "Rounds", default = "3",
            help = "More rounds make a longer bout. Each round lasts one minute.",
            choices = listOf(HubChoice("1", "1 round"), HubChoice("3", "3 rounds"), HubChoice("5", "5 rounds"))
        )
        val knockouts = HubOption.Toggle(
            key = "knockouts", label = "Knockouts", default = true,
            help = "Floor your rival for a ten-count. Switch off to decide the bout on points only."
        )
        val assist = HubOption.Toggle(
            key = "assist", label = "Turn-based assist", default = false,
            help = "Take turns choosing a punch and then a defence, with no clock. For players who can't play in real time."
        )
        GameHubSpec(
            gameId = "toy-boxing", title = "Toy Boxing",
            tagline = "Two wind-up toy boxers in a ring. Read the tell, then strike.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Box the computer over a few rounds.",
                    route = "toy-boxing", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(HubOptions.difficulty(), rounds, knockouts, assist)
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Two people share the screen, one on each side.",
                    route = "toy-boxing", start = HubStart.READY_SCREEN,
                    options = listOf(rounds, knockouts, assist)
                )
            )
        )
    }

    val baseball: GameHubSpec = run {
        val innings = HubOption.Choice(
            key = "innings", label = "Innings", default = "3",
            help = "How many innings each side plays.",
            choices = listOf(HubChoice("3", "3 innings"), HubChoice("5", "5 innings"), HubChoice("9", "9 innings"))
        )
        GameHubSpec(
            gameId = "toy-baseball", title = "Toy Baseball",
            tagline = "Pitcher against batter, one pitch at a time. Outguess your rival.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Play a full game against the computer, pitching and batting in turn.",
                    route = "toy-baseball", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(HubOptions.difficulty(), innings, HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Two people, one device. Hand it over for each pitch.",
                    route = "toy-baseball", start = HubStart.READY_SCREEN,
                    options = listOf(innings, HubOptions.firstMover("Player 2", "Player 1"))
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(boxing, baseball)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_toy_boxing_1..3` /
     * `tip_toy_baseball_1..3` strings in values/; values-es/ has the Spanish.
     */
    val tips: Map<String, List<String>> = mapOf(
        "toy-boxing" to listOf(
            "Watch for the yellow ! over a boxer's head: a hook is coming. Dodge it, or jab to cancel it.",
            "Hold Block to guard against jabs. Mashing punches drains stamina, and a tired boxer hits softly.",
            "Can't play in real time? Turn on Turn-based assist in the setup."
        ),
        "toy-baseball" to listOf(
            "Pitcher: pick a pitch and a spot. Batter: pick which pitch you're ready for and where to aim.",
            "The middle 3x3 of the grid is the strike zone. Take pitches outside it to draw a walk.",
            "The pitch history shows what your rival throws or swings at. Spot the pattern."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; ToyBoxingBaseballHubSpecsTest checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "toy-boxing" to (0xFF9A3412L to 0xFFFB8A5CL),
        "toy-baseball" to (0xFF1E3A8AL to 0xFF93AEF2L)
    )
}

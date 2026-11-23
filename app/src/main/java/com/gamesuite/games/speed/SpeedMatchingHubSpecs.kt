package com.gamesuite.games.speed

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Speed and Matching, kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). Every text below has a `hub_*`
 * string in res/values{,-es}/speed_matching_strings.xml (or already exists elsewhere);
 * `SpeedMatchingHubSpecsTest` proves it.
 *
 * Option keys reach the game screens as `LaunchOptions.extras`: Speed reads `assist` ("true"),
 * Matching reads `cards` ("12".."40") and `theme` ("critters" | "symbols" | "cards").
 */
object SpeedMatchingHubSpecs {
    val speed: GameHubSpec = run {
        val assist = HubOption.Toggle(
            key = "assist", label = "Turn-based assist", default = false,
            help = "Take turns instead of racing, with no countdown. For players who can't play at speed."
        )
        GameHubSpec(
            gameId = "speed", title = "Speed",
            tagline = "Play cards onto the centre piles, one rank up or down, faster than your rival.",
            family = HubFamily.CARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Race the computer to empty your cards.",
                    route = "speed", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(HubOptions.difficulty(), assist)
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Two players race on one device, one on each side.",
                    route = "speed", start = HubStart.READY_SCREEN,
                    options = listOf(assist)
                )
            )
        )
    }

    val matching: GameHubSpec = run {
        val cards = HubOption.Choice(
            key = "cards", label = "Cards", default = "20",
            help = "More cards make a longer, harder board.",
            choices = listOf(
                HubChoice("12", "12 cards"),
                HubChoice("20", "20 cards"),
                HubChoice("30", "30 cards"),
                HubChoice("40", "40 cards")
            )
        )
        val theme = HubOption.Choice(
            key = "theme", label = "Picture set", default = "critters",
            help = "What is drawn on the cards.",
            choices = listOf(
                HubChoice("critters", "Critters", "Friendly animal faces."),
                HubChoice("symbols", "Symbols", "Bold shapes that never rely on colour alone."),
                HubChoice("cards", "Playing cards", "Pairs of the same rank and colour.")
            )
        )
        GameHubSpec(
            gameId = "matching", title = "Matching",
            tagline = "Flip two cards to find pairs. Remember where everything is.",
            family = HubFamily.CARD,
            modes = listOf(
                HubMode(
                    id = "solo", kind = HubModeKind.SOLO, title = "Solo",
                    blurb = "Find every pair in as few moves as you can.",
                    route = "matching", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                    options = listOf(cards, theme)
                ),
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "Find more pairs than 1 to 3 computer players.",
                    route = "matching", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(cards, theme, HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "matching", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(
                        cards, theme, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(),
                        HubOptions.firstMover("Player 2", "Player 1")
                    )
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(speed, matching)
}

package com.gamesuite.games.ludo

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Chinese Checkers and Ludo, kept next to the games until the integrator adds
 * them to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). Every text below has a
 * `hub_*` string in res/values{,-es}/chinese_ludo_strings.xml; `ChineseLudoHubSpecsTest` proves it.
 */
object ChineseLudoHubSpecs {
    val chineseCheckers: GameHubSpec = run {
        val pieces = HubOption.Choice(
            key = "pieces", label = "Pieces each", default = "standard",
            help = "Ten is the full game; six is a shorter race.",
            choices = listOf(
                HubChoice("standard", "10 pieces", "The full game."),
                HubChoice("short", "6 pieces", "A shorter race.")
            )
        )
        GameHubSpec(
            gameId = "chinese-checkers", title = "Chinese Checkers",
            tagline = "Hop your marbles across the star to the opposite corner.",
            family = HubFamily.BOARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "Race 1 to 5 computer players.",
                    route = "chinese-checkers", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 6,
                    options = listOf(pieces, HubOptions.cpus(1, 5, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "chinese-checkers", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 6,
                    options = listOf(
                        pieces, HubOptions.humans(2, 6, 2), HubOptions.cpus(0, 4, 0), HubOptions.difficulty(),
                        HubOptions.firstMover("Player 2", "Player 1")
                    )
                )
            )
        )
    }

    val ludo: GameHubSpec = run {
        val rules = listOf(
            HubOption.Toggle(
                key = "needSix", label = "Need a six to start", default = true,
                help = "On: a token leaves the yard only on a six. Off: any roll brings one out."
            ),
            HubOption.Toggle(
                key = "exactFinish", label = "Exact roll to finish", default = true,
                help = "On: the last step needs the exact number. Off: any roll that reaches home counts."
            )
        )
        GameHubSpec(
            gameId = "ludo", title = "Ludo",
            tagline = "Roll, race four tokens home and send rivals back to the yard.",
            family = HubFamily.BOARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "Race 1 to 3 computer players.",
                    route = "ludo", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = rules + listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "ludo", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = rules + listOf(
                        HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(),
                        HubOptions.firstMover("Player 2", "Player 1")
                    )
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(chineseCheckers, ludo)
}

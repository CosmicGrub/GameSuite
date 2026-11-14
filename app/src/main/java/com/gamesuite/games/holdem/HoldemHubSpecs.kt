package com.gamesuite.games.holdem

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Texas Hold'em, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (the entry there is `HoldemHubSpecs.holdem`) and lists "texas-holdem" in
 * `networkedGames`. Every text below has a `hub_*` string in res/values{,-es}/holdem_strings.xml (or one the
 * shared Hub options already own); `HoldemHubSpecsTest` proves it. Chips are play chips only.
 */
object HoldemHubSpecs {
    const val KEY_CHIPS = "startingChips"
    const val KEY_BLINDS = "blindSpeed"
    const val KEY_STYLE = "cpuStyle"

    private val chips = HubOption.Choice(
        key = KEY_CHIPS, label = "Starting chips", default = "1000",
        help = "Everyone starts with the same stack. Chips are play money only.",
        choices = listOf(
            HubChoice("500", "500 chips"),
            HubChoice("1000", "1,000 chips"),
            HubChoice("2000", "2,000 chips"),
            HubChoice("5000", "5,000 chips")
        )
    )

    private val blinds = HubOption.Choice(
        key = KEY_BLINDS, label = "Blinds rise", default = "normal",
        help = "The forced bets grow so a game always ends.",
        choices = listOf(
            HubChoice("off", "Never", "The blinds never change."),
            HubChoice("slow", "Slowly", "Every 12 hands."),
            HubChoice("normal", "Normally", "Every 8 hands."),
            HubChoice("fast", "Quickly", "Every 4 hands.")
        )
    )

    private fun style(visibleWhen: ((com.gamesuite.core.HubChoices) -> Boolean)? = null) = HubOption.Choice(
        key = KEY_STYLE, label = "Computer style", default = "mixed",
        help = "How the computer players lean.",
        choices = listOf(
            HubChoice("balanced", "Balanced", "Plays a sound, even game."),
            HubChoice("tight", "Tight", "Plays few hands and bets the strong ones."),
            HubChoice("loose", "Loose", "Plays many hands and bluffs more."),
            HubChoice("mixed", "Mixed", "Each computer plays differently.")
        ),
        visibleWhen = visibleWhen
    )

    val holdem: GameHubSpec = GameHubSpec(
        gameId = "texas-holdem", title = "Texas Hold'em",
        tagline = "Play for chips only. Outlast every rival at the table.",
        family = HubFamily.CARD,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Take on 1 to 5 computer players.",
                route = "texas-holdem", start = HubStart.IMMEDIATE, defaultCpus = 3, minSeats = 2, maxSeats = 6,
                options = listOf(chips, blinds, HubOptions.cpus(1, 5, 3), style(), HubOptions.difficulty())
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "texas-holdem", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 6,
                options = listOf(
                    chips, blinds, HubOptions.humans(2, 6, 2), HubOptions.cpus(0, 4, 0),
                    style { it.int(HubKeys.CPUS, 0) > 0 },
                    HubOptions.difficulty().copy(visibleWhen = { it.int(HubKeys.CPUS, 0) > 0 })
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(holdem)
}

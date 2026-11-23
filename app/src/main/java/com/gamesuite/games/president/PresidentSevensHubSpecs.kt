package com.gamesuite.games.president

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of President and Sevens, kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim; both ids go in `networkedGames`
 * and, since both support watchers, in `spectatorGames`). Every text below has a `hub_*` string in
 * res/values{,-es}/president_sevens_strings.xml (or the shared Hub strings); `PresidentSevensHubSpecsTest`
 * proves it.
 *
 * The option keys are what the screens read from `LaunchOptions.extras`: President `revolutions`, `ties`,
 * `exchange` (true/false) and `rounds` ("3", "5", "10"); Sevens `passPenalty`.
 */
object PresidentSevensHubSpecs {
    val president: GameHubSpec = run {
        val rules = listOf(
            HubOption.Toggle(
                key = "revolutions", label = "Revolutions", default = true,
                help = "Four of a kind turns the order of the cards upside down until another four is played."
            ),
            HubOption.Toggle(
                key = "ties", label = "Ties skip a player", default = true,
                help = "Playing the same rank as the last play is allowed and skips the next player."
            ),
            HubOption.Toggle(
                key = "exchange", label = "Card exchange", default = true,
                help = "Each round starts with the Scum giving the President its best cards and getting worse ones back."
            ),
            HubOption.Choice(
                key = "rounds", label = "Rounds", default = "5",
                help = "A session lasts this many rounds. The most points wins.",
                choices = listOf(HubChoice("3", "3 rounds"), HubChoice("5", "5 rounds"), HubChoice("10", "10 rounds"))
            )
        )
        GameHubSpec(
            gameId = "president", title = "President",
            tagline = "Be first to play out your cards. Beat the last play, become President and make the Scum pay.",
            family = HubFamily.CARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "Take on 2 to 5 computer players.",
                    route = "president", start = HubStart.IMMEDIATE, defaultCpus = 3, minSeats = 3, maxSeats = 6,
                    options = listOf(HubOptions.cpus(2, 5, 3), HubOptions.difficulty()) + rules
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "president", start = HubStart.READY_SCREEN, minSeats = 3, maxSeats = 6,
                    options = listOf(HubOptions.humans(2, 6, 3), HubOptions.cpus(0, 4, 0), HubOptions.difficulty()) + rules
                )
            )
        )
    }

    val sevens: GameHubSpec = run {
        val rules = listOf(
            HubOption.Toggle(
                key = "passPenalty", label = "Pass penalty", default = false,
                help = "Each time you are forced to pass, you add 5 points to your score."
            )
        )
        GameHubSpec(
            gameId = "sevens", title = "Sevens",
            tagline = "Build every suit out from the sevens and be first to empty your hand.",
            family = HubFamily.CARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "Take on 2 to 5 computer players.",
                    route = "sevens", start = HubStart.IMMEDIATE, defaultCpus = 3, minSeats = 3, maxSeats = 6,
                    options = listOf(HubOptions.cpus(2, 5, 3), HubOptions.difficulty()) + rules
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "sevens", start = HubStart.READY_SCREEN, minSeats = 3, maxSeats = 6,
                    options = listOf(HubOptions.humans(2, 6, 3), HubOptions.cpus(0, 4, 0), HubOptions.difficulty()) + rules
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(president, sevens)
}

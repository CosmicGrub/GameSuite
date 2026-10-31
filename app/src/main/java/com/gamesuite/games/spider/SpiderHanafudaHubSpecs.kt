package com.gamesuite.games.spider

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Spider Solitaire and Hanafuda, kept next to the games until the integrator
 * adds them to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). Every text below has
 * a string named `hubKey(text)` in res/values{,-es}/spider_hanafuda_strings.xml, except the handful of
 * texts the Hub already owns ("Play", "vs Computer", "Pass & Play" and the shared option labels);
 * `SpiderHanafudaHubSpecsTest` proves it. Hanafuda is meant to be listed in
 * `GameHubSpecs.networkedGames` (the engine is host-authoritative); the integrator adds its Nearby and
 * Online modes from that list.
 */
object SpiderHanafudaHubSpecs {
    private val suitsOption = HubOption.Choice(
        key = SpiderOptions.SUITS, label = "Number of suits", default = "1",
        help = "Fewer suits make runs easier to build.",
        choices = listOf(
            HubChoice("1", "One suit", "The easiest game: every card is a spade."),
            HubChoice("2", "Two suits", "A balanced challenge."),
            HubChoice("4", "Four suits", "The full, hard game.")
        )
    )

    val spiderSolitaire: GameHubSpec = GameHubSpec(
        gameId = "spider-solitaire", title = "Spider Solitaire",
        tagline = "Clear the table with King-to-Ace runs in ten columns.",
        family = HubFamily.CARD,
        modes = listOf(
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Play", blurb = "Play on your own.",
                route = "spider-solitaire", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(
                    suitsOption,
                    HubOption.Toggle(
                        key = SpiderOptions.WINNABLE, label = "Winnable deals", default = false,
                        help = "On: the deal is built so it can be won. Off: a fair random shuffle."
                    )
                )
            ),
            HubMode(
                id = "daily", kind = HubModeKind.DAILY, title = "Today's deal",
                blurb = "The same deal for everyone today, always winnable.",
                route = "spider-solitaire-daily", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(suitsOption)
            )
        )
    )

    val hanafuda: GameHubSpec = run {
        val rounds = HubOption.Choice(
            key = "rounds", label = "Match length", default = "3",
            help = "A match lasts this many rounds, one month each.",
            choices = listOf(
                HubChoice("3", "Three rounds", "A short match."),
                HubChoice("6", "Six rounds", "Half a year."),
                HubChoice("12", "Twelve rounds", "A full year, one round per month.")
            )
        )
        val yakuSet = HubOption.Choice(
            key = "yakuSet", label = "Scoring hands", default = "basic",
            help = "Which card combinations score points.",
            choices = listOf(
                HubChoice("basic", "Basic set", "Lights, animals, ribbons and plain cards."),
                HubChoice("full", "Full set", "Adds the viewing hands, red and blue ribbons and special dealt hands.")
            )
        )
        GameHubSpec(
            gameId = "hanafuda", title = "Hanafuda",
            tagline = "Koi-Koi, the Japanese flower-card game: match months, make combinations.",
            family = HubFamily.CARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer", blurb = "Play against the computer.",
                    route = "hanafuda", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(rounds, yakuSet, HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play", blurb = "Two people, one device.",
                    route = "hanafuda", start = HubStart.READY_SCREEN,
                    options = listOf(rounds, yakuSet, HubOptions.firstMover("Player 2", "Player 1"))
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(spiderSolitaire, hanafuda)
}

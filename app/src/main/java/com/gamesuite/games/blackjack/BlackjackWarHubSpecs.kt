package com.gamesuite.games.blackjack

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Blackjack and War, kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). Every text below has a `hub_*`
 * string in res/values{,-es}/blackjack_war_strings.xml (or already exists elsewhere, e.g. "Pass & Play");
 * `BlackjackWarHubSpecsTest` proves it.
 */
object BlackjackWarHubSpecs {
    val blackjack: GameHubSpec = run {
        val rules = listOf(
            HubOption.Choice(
                key = "decks", label = "Decks in the shoe", default = "6",
                help = "More decks make the shoe harder to count. One deck is the friendliest game.",
                choices = listOf(
                    HubChoice("6", "6 decks"), HubChoice("4", "4 decks"), HubChoice("2", "2 decks"), HubChoice("1", "1 deck")
                )
            ),
            HubOption.Choice(
                key = "chips", label = "Starting chips", default = "1000",
                help = "Play chips only. There is no real money in this game.",
                choices = listOf(
                    HubChoice("500", "500 chips"), HubChoice("1000", "1,000 chips"),
                    HubChoice("2500", "2,500 chips"), HubChoice("5000", "5,000 chips")
                )
            ),
            HubOption.Choice(
                key = "payout", label = "Blackjack pays", default = "3:2",
                help = "What a natural 21 wins. 3 to 2 is the fair classic; 6 to 5 favors the dealer.",
                choices = listOf(HubChoice("3:2", "3 to 2"), HubChoice("6:5", "6 to 5"))
            ),
            HubOption.Toggle(
                key = "hitSoft17", label = "Dealer hits soft 17", default = false,
                help = "On: the dealer draws on a 17 that counts an Ace as 11. Off: the dealer stands on every 17."
            ),
            HubOption.Toggle(
                key = "insurance", label = "Offer insurance", default = true,
                help = "When the dealer shows an Ace you may bet half your stake that the dealer has blackjack."
            ),
            HubOption.Toggle(
                key = "surrender", label = "Allow late surrender", default = true,
                help = "Give up your first two cards after the dealer checks for blackjack, and get half your bet back."
            )
        )
        GameHubSpec(
            gameId = "blackjack", title = "Blackjack",
            tagline = "Beat the dealer to 21 with play chips. No real money, ever.",
            family = HubFamily.CARD,
            modes = listOf(
                HubMode(
                    id = "solo", kind = HubModeKind.SOLO, title = "Solo",
                    blurb = "You against the dealer, one round at a time.",
                    route = "blackjack", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                    options = rules
                ),
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "Share the table with 1 to 3 computer players.",
                    route = "blackjack", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = rules + listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty())
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device and each play against the dealer. Computers can fill empty seats.",
                    route = "blackjack", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = rules + listOf(HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty())
                )
            )
        )
    }

    val war: GameHubSpec = run {
        val variant = HubOption.Choice(
            key = "variant", label = "Game style", default = "classic",
            help = "Classic is pure luck. Choose 3 adds a decision every round.",
            choices = listOf(
                HubChoice("classic", "Classic", "Everyone flips the top card. Highest wins the pile."),
                HubChoice("choose3", "Choose 3", "Secretly pick one of your top three cards each round.")
            )
        )
        val warDown = HubOption.Choice(
            key = "warDown", label = "Cards put down in a war", default = "3",
            help = "When top cards tie, each tied player puts these cards face down, then flips a deciding card.",
            choices = listOf(HubChoice("3", "3 face down"), HubChoice("1", "1 face down"))
        )
        val limit = HubOption.Choice(
            key = "maxRounds", label = "Round limit", default = "300",
            help = "After this many rounds the game is called and the bigger pile wins.",
            choices = listOf(HubChoice("150", "150 rounds"), HubChoice("300", "300 rounds"), HubChoice("600", "600 rounds"))
        )
        val level = HubOptions.difficulty().copy(
            help = "How well the computer picks its card in Choose 3.",
            visibleWhen = { it.string("variant", "classic") == "choose3" }
        )
        GameHubSpec(
            gameId = "war", title = "War",
            tagline = "Flip, compare and win the whole pile. A tie sends you to war.",
            family = HubFamily.CARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "Play 1 to 3 computer players.",
                    route = "war", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(variant, warDown, limit, HubOptions.cpus(1, 3, 1), level)
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "war", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(variant, warDown, limit, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), level)
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(blackjack, war)
}

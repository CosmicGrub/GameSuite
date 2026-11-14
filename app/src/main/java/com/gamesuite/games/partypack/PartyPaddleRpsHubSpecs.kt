package com.gamesuite.games.partypack

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Paddle Duel (`paddle-duel`) and Rock-Paper-Showdown (`rps-showdown`), kept next to the
 * games until the integrator adds them to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim).
 * Both are SAME-SCREEN games: 2 to 4 seats around one device, and computer seats fill any seat nobody takes.
 * They have NO Nearby or Online mode in this version, so neither id goes in `GameHubSpecs.networkedGames`. Every
 * text below has a `hubKey(text)` string in res/values{,-es}/party_paddle_rps_hub_strings.xml (or already exists
 * elsewhere); `PartyPaddleRpsHubSpecsTest` proves it.
 *
 * Modes: "vs Computer" (VS_CPU; one person, 1 to 3 computers) and "Same screen" (PASS_AND_PLAY; 2 to 4 people, up
 * to 2 more computers, never more than 4 seats in all).
 *
 * Option keys reach the game screens as `LaunchOptions.extras`:
 *  - Paddle Duel: `format` (p5 | p7 | p11 | l3 | l5), `powerups`, `relaxed`, `controls` (drag | switch), `facing`.
 *  - Rock-Paper-Showdown: `variant` (classic | lizard), `format` (bo3 | bo5 | bo7 | elim), `timer` (off | 10 | 20 |
 *    30 seconds), `relaxed`, `facing`.
 * The computer level is `LaunchOptions.cpuDifficulty` (Settings default, Easy, Medium or Hard).
 */
object PartyPaddleRpsHubSpecs {
    private val facing = HubOption.Toggle(
        key = "facing", label = "Face each player", default = true,
        help = "Turn each player's labels toward their own edge. Switch off to keep everything upright."
    )

    private fun sameScreen(id: String, blurb: String, options: List<HubOption>) = HubMode(
        id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen", blurb = blurb,
        route = id, start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
        options = listOf(HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0)) + options
    )

    val paddleDuel: GameHubSpec = run {
        val format = HubOption.Choice(
            key = "format", label = "Match format", default = "p7",
            help = "First to a number of points, or last paddle standing with lives. With three or four players, lives work best.",
            choices = listOf(
                HubChoice("p5", "First to 5 points"), HubChoice("p7", "First to 7 points"), HubChoice("p11", "First to 11 points"),
                HubChoice("l3", "3 lives"), HubChoice("l5", "5 lives")
            )
        )
        val powerups = HubOption.Toggle(
            key = "powerups", label = "Power-ups", default = false,
            help = "A wide paddle, a slow ball or extra balls appear on the court. The last player to touch the ball gets the prize."
        )
        val relaxed = HubOption.Toggle(
            key = "relaxed", label = "Relaxed assist", default = false,
            help = "A slower ball, wider paddles and a longer countdown, for players who find fast games hard."
        )
        val controls = HubOption.Choice(
            key = "controls", label = "Paddle control", default = "drag",
            help = "Drag with a thumb (arrow buttons work too), or use one switch: the paddle follows the ball by itself and the switch changes your return angle.",
            choices = listOf(HubChoice("drag", "Drag or buttons"), HubChoice("switch", "One switch"))
        )
        val shared = listOf(format, powerups, relaxed, controls, facing)
        GameHubSpec(
            gameId = "paddle-duel", title = "Paddle Duel",
            tagline = "Slide your paddle, add spin and outlast the other edges. Up to four on one screen.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Duel the computer. Add more computers to fill the other edges.",
                    route = "paddle-duel", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.difficulty(), HubOptions.cpus(1, 3, 1)) + shared
                ),
                sameScreen("paddle-duel", "Two to four people on one screen, each with a thumb on their own edge.", listOf(HubOptions.difficulty()) + shared)
            )
        )
    }

    val rpsShowdown: GameHubSpec = run {
        val variant = HubOption.Choice(
            key = "variant", label = "Moves", default = "classic",
            help = "Classic is rock, paper and scissors. Lizard-Spock adds two more moves, so ties are rarer.",
            choices = listOf(HubChoice("classic", "Classic (3 moves)"), HubChoice("lizard", "Lizard-Spock (5 moves)"))
        )
        val format = HubOption.Choice(
            key = "format", label = "Match format", default = "bo3",
            help = "Best of 3, 5 or 7 rounds, or Elimination: the losers of each round drop out and the last player standing wins. Ties replay.",
            choices = listOf(HubChoice("bo3", "Best of 3"), HubChoice("bo5", "Best of 5"), HubChoice("bo7", "Best of 7"), HubChoice("elim", "Elimination"))
        )
        val timer = HubOption.Choice(
            key = "timer", label = "Pick timer", default = "off",
            help = "How long players have to lock in a move. When time runs out a random move is picked.",
            choices = listOf(HubChoice("off", "No timer"), HubChoice("10", "10 seconds"), HubChoice("20", "20 seconds"), HubChoice("30", "30 seconds"))
        )
        val relaxed = HubOption.Toggle(
            key = "relaxed", label = "Relaxed assist", default = false,
            help = "No time pressure: a doubled pick timer, a slower countdown, and the result stays until someone taps Next round."
        )
        val shared = listOf(variant, format, timer, relaxed, facing)
        GameHubSpec(
            gameId = "rps-showdown", title = "Rock-Paper-Showdown",
            tagline = "Everybody picks in secret, then the hands are revealed together.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Outguess the computer. Add more computers for a bigger showdown.",
                    route = "rps-showdown", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.difficulty(), HubOptions.cpus(1, 3, 1)) + shared
                ),
                sameScreen("rps-showdown", "Two to four people on one screen. Everyone picks on their own side and nobody can peek.", listOf(HubOptions.difficulty()) + shared)
            )
        )
    }

    val all: List<GameHubSpec> = listOf(paddleDuel, rpsShowdown)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_paddle_duel_1..3` /
     * `tip_rps_showdown_1..3` strings in values/; values-es/ has the Spanish.
     */
    val tips: Map<String, List<String>> = mapOf(
        "paddle-duel" to listOf(
            "Slide a thumb along your edge and the paddle follows. Hit the ball with the paddle end to send it sideways.",
            "A moving paddle puts spin on the ball, and the ball speeds up every rally. Drift back to the middle between returns.",
            "Want a gentler game? Turn on the Relaxed assist, or use the arrow buttons or one switch."
        ),
        "rps-showdown" to listOf(
            "Tap a move to lock it in. Your neighbours only ever see a lock, never your pick. Tap Undo to change your mind.",
            "When everyone is locked in, a countdown runs and all moves flip face up together. Ties are played again.",
            "Against the computer, Hard learns your habits. Mix up your moves and do not repeat what just won."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; `PartyPaddleRpsHubSpecsTest` checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "paddle-duel" to (0xFF711F9EL to 0xFFC779F2L),
        "rps-showdown" to (0xFF9E1F78L to 0xFFFF66D1L)
    )
}

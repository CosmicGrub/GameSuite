package com.gamesuite.games.toysoccer

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Toy Soccer and Toy Tennis (wave 4, "toy sports"), kept next to the games until
 * the integrator adds them to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim) and
 * lists both ids in `networkedGames` (the engines are host-authoritative). Every text below has a
 * `hub_*` string in res/values{,-es}/toysports_strings.xml (or already exists elsewhere);
 * `ToySportsHubSpecsTest` proves it.
 *
 * Option keys reach the screens as `LaunchOptions.extras`: Soccer reads `match` ("first3", "first5",
 * "time2", "time4") and `assist` ("true"); Tennis reads `format` ("points7", "points11", "set4", "set6",
 * "best3") and `assist`. The 2v2 modes seat four; seats alternate between the teams (seat % 2).
 */
object ToySportsHubSpecs {
    val toySoccer: GameHubSpec = run {
        val match = HubOption.Choice(
            key = "match", label = "Match length", default = "first3",
            help = "Race to the goals, or play against the clock. A tie goes to sudden death.",
            choices = listOf(
                HubChoice("first3", "First to 3 goals"),
                HubChoice("first5", "First to 5 goals"),
                HubChoice("time2", "2 minutes"),
                HubChoice("time4", "4 minutes")
            )
        )
        val assist = HubOption.Toggle(
            key = "assist", label = "Turn-based assist", default = false,
            help = "Teams take turns to flick one figure, and the table settles before the next turn. No clock, for players who can't play at speed."
        )
        GameHubSpec(
            gameId = "toy-soccer", title = "Toy Soccer",
            tagline = "Flick your toy players and score before the clock runs out.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Flick it out with a computer opponent.",
                    route = "toy-soccer", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(match, assist, HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "cpu2", kind = HubModeKind.VS_CPU, title = "2v2 with Computers",
                    blurb = "You and a computer team mate against two computers.",
                    route = "toy-soccer", start = HubStart.IMMEDIATE, defaultCpus = 3, minSeats = 4, maxSeats = 4,
                    options = listOf(match, assist, HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Two players share this device, one at each end of the pitch.",
                    route = "toy-soccer", start = HubStart.READY_SCREEN,
                    options = listOf(match, assist, HubOptions.firstMover("Player 2", "Player 1"))
                ),
                HubMode(
                    id = "pass2", kind = HubModeKind.PASS_AND_PLAY, title = "2v2 Pass & Play",
                    blurb = "Up to four players share this device. Computers fill empty places.",
                    route = "toy-soccer", start = HubStart.READY_SCREEN, minSeats = 4, maxSeats = 4,
                    options = listOf(match, assist, HubOptions.humans(2, 4, 2), HubOptions.difficulty())
                )
            )
        )
    }

    val toyTennis: GameHubSpec = run {
        val format = HubOption.Choice(
            key = "format", label = "Match format", default = "points7",
            help = "A short race to points, or real games and sets.",
            choices = listOf(
                HubChoice("points7", "First to 7 points"),
                HubChoice("points11", "First to 11 points"),
                HubChoice("set4", "One set to 4 games"),
                HubChoice("set6", "One set to 6 games"),
                HubChoice("best3", "Best of 3 short sets")
            )
        )
        val assist = HubOption.Toggle(
            key = "assist", label = "Relaxed timing", default = false,
            help = "The ball waits for your swing and your player is always in place, so only direction and power matter. For players who can't play in real time."
        )
        GameHubSpec(
            gameId = "toy-tennis", title = "Toy Tennis",
            tagline = "Swipe to serve and rally, and win the point.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Rally against a computer opponent.",
                    route = "toy-tennis", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(format, assist, HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Two players share this device, one at each end of the court.",
                    route = "toy-tennis", start = HubStart.READY_SCREEN,
                    options = listOf(format, assist, HubOptions.firstMover("Player 2", "Player 1"))
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(toySoccer, toyTennis)

    /** First-run tips (3 lines each). The English lines must equal `tip_toy_soccer_1..3` / `tip_toy_tennis_1..3`. */
    val tips: Map<String, List<String>> = mapOf(
        "toy-soccer" to listOf(
            "Pull back from one of your figures and let go to flick it at the ball. A longer pull is a harder kick.",
            "A figure can be flicked again once it stops. Keep your keeper near the goal.",
            "Too fast? Turn on Turn-based assist in the setup to take turns without a clock."
        ),
        "toy-tennis" to listOf(
            "Your player runs to the ball by itself. Swipe toward the net just as the ring closes.",
            "Swipe left or right of straight for the angle, and faster for a harder, deeper shot.",
            "Too fast? Turn on Relaxed timing in the setup and the ball waits for your swing."
        )
    )
}

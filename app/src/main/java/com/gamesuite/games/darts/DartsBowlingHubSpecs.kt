package com.gamesuite.games.darts

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Darts and Bowling, kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). Every text below has a `hub_*`
 * string in res/values{,-es}/darts_bowling_strings.xml (or already in the shared Hub strings);
 * `DartsBowlingHubSpecsTest` proves it. Both games are in `networkedGames`: Nearby and Online modes
 * are generated from these specs.
 */
object DartsBowlingHubSpecs {
    private val assist = HubOption.Choice(
        key = "assist", label = "Your hand", default = "normal",
        help = "How steady you throw. A steadier hand means less wobble.",
        choices = listOf(
            HubChoice("steady", "Rock steady", "Very little wobble."),
            HubChoice("normal", "Pub regular", "A little wobble, like a keen amateur."),
            HubChoice("shaky", "Nervous", "A lot of wobble: a real challenge.")
        )
    )

    private val dartsRules = listOf(
        HubOption.Choice(
            key = "game", label = "Darts game", default = "501",
            help = "Count down to zero, or close the Cricket numbers.",
            choices = listOf(
                HubChoice("501", "501", "Count down to exactly zero. The classic match."),
                HubChoice("301", "301", "A shorter count down to zero."),
                HubChoice("cricket", "Cricket", "Close 20 to 15 and the bull, then out-score your rivals.")
            )
        ),
        HubOption.Toggle(
            key = "doubleOut", label = "Double out", default = true,
            help = "On: finish on a double or the bull. Off: any dart can finish.",
            visibleWhen = { it.string("game", "501") != "cricket" }
        ),
        HubOption.Toggle(
            key = "doubleIn", label = "Double in", default = false,
            help = "On: your score only starts once you hit a double.",
            visibleWhen = { it.string("game", "501") != "cricket" }
        ),
        assist
    )

    private val bowlingRules = listOf(
        HubOption.Toggle(
            key = "bumpers", label = "Bumpers", default = false,
            help = "Rails along the lane keep the ball out of the gutters."
        ),
        HubOption.Toggle(
            key = "guide", label = "Aim guide", default = true,
            help = "Shows the path your ball will take down the lane."
        ),
        assist
    )

    val darts: GameHubSpec = GameHubSpec(
        gameId = "darts", title = "Darts",
        tagline = "Flick darts at the board: count down from 501 or play Cricket.",
        family = HubFamily.ARCADE_PUZZLE,
        modes = listOf(
            HubMode(
                id = "practice", kind = HubModeKind.SOLO, title = "Practice",
                blurb = "Throw on your own and beat your best average.",
                route = "darts", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1, options = dartsRules
            ),
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Throw against 1 to 3 computer players.",
                route = "darts", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = dartsRules + listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "darts", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = dartsRules + listOf(
                    HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val bowling: GameHubSpec = GameHubSpec(
        gameId = "bowling", title = "Bowling",
        tagline = "Aim, swipe and curve the ball into the pocket for a strike.",
        family = HubFamily.ARCADE_PUZZLE,
        modes = listOf(
            HubMode(
                id = "practice", kind = HubModeKind.SOLO, title = "Practice",
                blurb = "Bowl on your own and chase a perfect 300.",
                route = "bowling", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1, options = bowlingRules
            ),
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Bowl against 1 to 3 computer players.",
                route = "bowling", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = bowlingRules + listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "bowling", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = bowlingRules + listOf(
                    HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(darts, bowling)

    /** First-run tips (3 lines each); the EN text equals the `tip_<id>_<n>` string resource. */
    val tips: Map<String, List<String>> = mapOf(
        "darts" to listOf(
            "Drag on the board to move the reticle, then flick up to throw. Soft flicks sag low.",
            "In 501 you must finish exactly on zero with a double. Go too far and the visit is a bust.",
            "In Cricket, close 20 to 15 and the bull with three marks, then score on numbers rivals leave open."
        ),
        "bowling" to listOf(
            "Slide the ball to set where it starts, then swipe up. Curve your swipe to hook the ball.",
            "Aim for the pocket beside the head pin for the best chance of a strike.",
            "A spare scores the next ball as a bonus, a strike the next two. The tenth frame can give a third ball."
        )
    )
}

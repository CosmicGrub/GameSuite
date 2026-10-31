package com.gamesuite.games.billiards

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Billiards and Mini Golf (wave 4, toy sports), kept next to the games until the
 * integrator adds them to `GameHubSpecs.baseSpecs()`. Every text below has a `hub_*` string in
 * res/values{,-es}/billiards_strings.xml or mini_golf_strings.xml; `BilliardsGolfHubSpecsTest` proves it.
 * Nearby and Online modes are NOT listed here: the integrator adds both ids to `networkedGames` and
 * `buildAll` appends them.
 */
object BilliardsGolfHubSpecs {
    val billiards: GameHubSpec = run {
        val variant = HubOption.Choice(
            key = "variant", label = "Pool game", default = BilliardsVariant.EIGHT,
            help = "8-ball: pocket your solids or stripes, then the 8. 9-ball: always hit the lowest ball first and pocket the 9.",
            choices = listOf(
                HubChoice(BilliardsVariant.EIGHT, "8-ball", "Solids and stripes, then the 8 ball."),
                HubChoice(BilliardsVariant.NINE, "9-ball", "Lowest ball first; pocket the 9 to win.")
            )
        )
        GameHubSpec(
            gameId = "billiards", title = "Billiards",
            tagline = "Aim, add spin and pocket the balls in 8-ball or 9-ball.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer", blurb = "Play against the computer.",
                    route = "billiards", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(variant, HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play", blurb = "Two people, one device.",
                    route = "billiards", start = HubStart.READY_SCREEN,
                    options = listOf(variant, HubOptions.firstMover("Player 2", "Player 1"))
                )
            )
        )
    }

    val miniGolf: GameHubSpec = run {
        val course = HubOption.Choice(
            key = "holes", label = "Holes to play", default = "9",
            help = "Nine original holes with bumpers, sand, water and ramps.",
            choices = listOf(
                HubChoice("9", "9 holes", "The whole course."),
                HubChoice("3", "3 holes", "A quick round on the first three holes.")
            )
        )
        val limit = HubOption.Choice(
            key = "limit", label = "Stroke limit", default = "6",
            help = "A hole ends when you reach this many strokes, and that is what it scores.",
            choices = listOf(
                HubChoice("6", "6 strokes", "A quicker round."),
                HubChoice("8", "8 strokes", "More room to recover.")
            )
        )
        GameHubSpec(
            gameId = "mini-golf", title = "Mini Golf",
            tagline = "Nine original holes of bumpers, sand, water and ramps.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "solo", kind = HubModeKind.SOLO, title = "Solo round", blurb = "Play the course on your own.",
                    route = "mini-golf", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                    options = listOf(course, limit)
                ),
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers", blurb = "Play against 1 to 3 computers.",
                    route = "mini-golf", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(course, limit, HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "mini-golf", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(
                        course, limit, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(),
                        HubOptions.firstMover("Player 2", "Player 1")
                    )
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(billiards, miniGolf)

    /** The first-run tips (three lines each); the integrator copies these into `GameTips.tips`. */
    val tips: Map<String, List<String>> = mapOf(
        "billiards" to listOf(
            "Drag on the table to aim, set the power and spin, then press Shoot.",
            "8-ball: pocket all your solids or stripes, then the 8. 9-ball: always hit the lowest ball first.",
            "A foul, like a scratch or hitting the wrong ball, gives your opponent ball in hand."
        ),
        "mini-golf" to listOf(
            "Drag back from the ball like a slingshot, then let go to putt. The farther you drag, the harder you hit.",
            "Sand slows the ball, water costs a stroke, and ramps push it along.",
            "The fewest strokes wins. A hole ends when you reach the stroke limit."
        )
    )
}

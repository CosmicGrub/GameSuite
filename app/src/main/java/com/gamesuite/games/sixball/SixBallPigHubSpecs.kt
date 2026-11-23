package com.gamesuite.games.sixball

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart
import com.gamesuite.ui.GameAccent

/**
 * The Game Hub specs of Six-Ball Puzzle and Pig, kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` (the entries there are `SixBallPigHubSpecs.sixBall` and `SixBallPigHubSpecs.pig`)
 * and lists "six-ball-puzzle" and "pig-dice" in `networkedGames`. Every text below has a `hubKey(text)` string in
 * res/values{,-es}/sixball_pig_strings.xml (or one the shared Hub already owns); `SixBallPigHubSpecsTest` proves it.
 */
object SixBallPigHubSpecs {
    const val KEY_START = "start"
    const val KEY_VARIANT = "variant"
    const val KEY_TARGET = "target"

    private val startLevel = HubOption.Choice(
        key = KEY_START, label = "Start at", default = "next",
        help = "You can switch to any level from the level picker once you are playing.",
        choices = listOf(
            HubChoice("next", "Next unsolved level", "Opens the first level you have not solved yet."),
            HubChoice("first", "Level 1", "Starts from the very beginning.")
        )
    )

    val sixBall: GameHubSpec = GameHubSpec(
        gameId = "six-ball-puzzle", title = "Six-Ball Puzzle",
        tagline = "Tilt the tray and roll six balls onto their slots.",
        family = HubFamily.ARCADE_PUZZLE,
        modes = listOf(
            HubMode(
                id = "free", kind = HubModeKind.SOLO, title = "Levels",
                blurb = "48 trays from easy to fiendish. Earn stars by beating par.",
                route = "six-ball-puzzle", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(startLevel)
            ),
            HubMode(
                id = "daily", kind = HubModeKind.DAILY, title = "Today's tray",
                blurb = "The same tray for everyone today.",
                route = "six-ball-puzzle-daily", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Race the same tray in turns: the fewest tilts wins.",
                route = "six-ball-puzzle", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(HubOptions.humans(2, 4, 2), startLevel)
            )
        )
    )

    private val variant = HubOption.Choice(
        key = KEY_VARIANT, label = "Game", default = "classic",
        help = "Four ways to push your luck.",
        choices = listOf(
            HubChoice("classic", "Classic Pig", "One die. A 1 loses your turn total."),
            HubChoice("bigpig", "Big Pig", "Two dice. One 1 busts, two 1s score 25, doubles count twice."),
            HubChoice("hog", "Hog", "One roll per turn: you choose how many dice, and any 1 scores nothing."),
            HubChoice("twentyone", "Twenty-One Plus", "Classic, but a turn total of exactly 21 banks itself with a 10-point bonus.")
        )
    )

    private val goal = HubOption.Choice(
        key = KEY_TARGET, label = "Play to", default = "100",
        help = "The first player to reach the goal wins.",
        choices = listOf(
            HubChoice("50", "50 points", "A quick game."),
            HubChoice("100", "100 points", "The classic goal."),
            HubChoice("150", "150 points", "A long game.")
        )
    )

    val pig: GameHubSpec = GameHubSpec(
        gameId = "pig-dice", title = "Pig",
        tagline = "Push your luck: roll or hold, but never roll a one.",
        family = HubFamily.BOARD,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Race 1 to 5 computer players to the goal.",
                route = "pig-dice", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 6,
                options = listOf(variant, goal, HubOptions.cpus(1, 5, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "pig-dice", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 6,
                options = listOf(
                    variant, goal, HubOptions.humans(2, 6, 2), HubOptions.cpus(0, 4, 0),
                    HubOptions.difficulty().copy(visibleWhen = { it.int(HubKeys.CPUS, 0) > 0 }),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(sixBall, pig)

    /**
     * First-run tips for `GameTips.tips` (the integrator pastes these literals; each must equal the string
     * `tip_<id with _>_<n>` in res/values/sixball_pig_tips_strings.xml, which the spec test checks).
     */
    val tips: Map<String, List<String>> = mapOf(
        "six-ball-puzzle" to listOf(
            "Swipe the tray or press an arrow: every ball rolls until something stops it.",
            "Put each ball on the slot with its own symbol and colour.",
            "Beat par for three stars. Undo is unlimited and Hint shows the next tilt."
        ),
        "pig-dice" to listOf(
            "Roll as often as you like; the numbers pile up in the pot.",
            "Hold to bank the pot. Roll a 1 and you lose it and your turn.",
            "First to the goal wins. Hint shows the best move and your chance."
        )
    )

    /** Proposed `GameThemes.accents` entries (distinct from every existing pair; contrast is checked in the spec test). */
    val accents: Map<String, GameAccent> = mapOf(
        "six-ball-puzzle" to GameAccent(0xFF96438E, 0xFFB766AF),
        "pig-dice" to GameAccent(0xFFA55B76, 0xFFB2768C)
    )

    /** Proposed `gameGlyph` entries. */
    val glyphs: Map<String, String> = mapOf("six-ball-puzzle" to "🟣", "pig-dice" to "🐷")

    /** Coach card step counts for `GameCoach.entries`. */
    val coachSteps: Map<String, Int> = mapOf("six-ball-puzzle" to 5, "pig-dice" to 5)
}

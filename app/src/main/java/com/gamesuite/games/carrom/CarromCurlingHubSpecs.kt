package com.gamesuite.games.carrom

import com.gamesuite.core.CoachEntry
import com.gamesuite.core.CoachMotif
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart
import com.gamesuite.R

/**
 * The Game Hub specs of Carrom and Curling (wave 4, toy sports), kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` and lists "carrom" and "curling" in `networkedGames`. Every text below has a `hub_*` string in
 * res/values{,-es}/carrom_curling_strings.xml (or one the shared Hub options already own); CarromCurlingSpecsTest proves it.
 * [tips] are the first-run lines (res `tip_<id>_<n>`), [coach] the "How to play" entries (res `coach_<id>_s1..sN` and `_fact`).
 */
object CarromCurlingHubSpecs {
    const val KEY_TARGET = "target"
    const val KEY_ENDS = "ends"
    const val KEY_STONES = "stones"

    private val target = HubOption.Choice(
        key = KEY_TARGET, label = "Play to", default = "0",
        help = "How long a Carrom match lasts.",
        choices = listOf(
            HubChoice("0", "One board", "A single board decides the match."),
            HubChoice("15", "First to 15 points", "Boards add up until a side reaches 15."),
            HubChoice("25", "First to 25 points", "Boards add up until a side reaches 25.")
        )
    )

    val carrom: GameHubSpec = GameHubSpec(
        gameId = "carrom", title = "Carrom",
        tagline = "Flick the striker and pocket your coins before your rivals do.",
        family = HubFamily.ARCADE_PUZZLE,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Flick against 1 to 3 computer players.",
                route = "carrom", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(target, HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "carrom", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    target, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0),
                    HubOptions.difficulty().copy(visibleWhen = { it.int("cpus", 0) > 0 }),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    private val ends = HubOption.Choice(
        key = KEY_ENDS, label = "Ends", default = "4",
        help = "How many ends make a curling match.",
        choices = listOf(HubChoice("4", "4 ends"), HubChoice("5", "5 ends"), HubChoice("6", "6 ends"))
    )

    private val stones = HubOption.Choice(
        key = KEY_STONES, label = "Stones each", default = "4",
        help = "How many stones each team slides every end.",
        choices = listOf(
            HubChoice("4", "4 stones", "A quick end."),
            HubChoice("8", "8 stones", "The full end.")
        )
    )

    val curling: GameHubSpec = GameHubSpec(
        gameId = "curling", title = "Curling",
        tagline = "Slide, curl and sweep your stones to the button.",
        family = HubFamily.ARCADE_PUZZLE,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Slide against 1 to 3 computer players.",
                route = "curling", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(ends, stones, HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "curling", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    ends, stones, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0),
                    HubOptions.difficulty().copy(visibleWhen = { it.int("cpus", 0) > 0 }),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(carrom, curling)

    /** First-run tip lines (English literals; each equals the `tip_<id>_<n>` string resource). */
    val tips: Map<String, List<String>> = mapOf(
        "carrom" to listOf(
            "Slide the striker along your line, then drag back from it and let go to flick.",
            "Pocket your own colour to shoot again. Cover the queen with one of your coins.",
            "A pocketed striker is a foul: a coin goes back on the board."
        ),
        "curling" to listOf(
            "Tap the ice where you want the stone to stop, then press Throw.",
            "Turn the handle to curl left or right, and sweep while it slides to carry it further.",
            "After every stone is thrown, the stone nearest the button scores."
        )
    )

    /** The "How to play" coach entries, ready for `GameCoach.entries`. */
    val coach: List<CoachEntry> = listOf(
        CoachEntry(
            "carrom",
            listOf(R.string.coach_carrom_s1, R.string.coach_carrom_s2, R.string.coach_carrom_s3, R.string.coach_carrom_s4, R.string.coach_carrom_s5),
            R.string.coach_carrom_fact, CoachMotif.BALL
        ),
        CoachEntry(
            "curling",
            listOf(R.string.coach_curling_s1, R.string.coach_curling_s2, R.string.coach_curling_s3, R.string.coach_curling_s4, R.string.coach_curling_s5),
            R.string.coach_curling_fact, CoachMotif.PATH
        )
    )
}

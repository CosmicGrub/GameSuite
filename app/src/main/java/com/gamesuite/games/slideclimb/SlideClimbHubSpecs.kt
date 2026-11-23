package com.gamesuite.games.slideclimb

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Slide and Climb, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (the entry there is this, verbatim). It is NOT networked in this version: do not
 * list "chutes-race" in `GameHubSpecs.networkedGames`. Every text below has a `hubKey(text)` string in
 * res/values{,-es}/slide_climb_hub_strings.xml, or already exists elsewhere (the shared mode titles and the
 * Ludo texts); `SlideClimbHubSpecsTest` proves it.
 *
 * Option keys reach the screen as `LaunchOptions.extras`:
 *  - `exactFinish` ("true" / "false", default false: overshooting 100 bounces back)
 *  - `handicap` (2 seats) / `handicap3` / `handicap4` ("none" | "all" | "1".."n": that player, 1-based, holds
 *    one spare roll; only the one for the table size is visible; see [slideClimbHandicapFromExtras])
 * The person and computer counts become the roster (`humans` / `cpus`). The computer level only matters
 * (the computer decides when to spend a spare roll) once a spare roll is in play, so it is hidden until then.
 */
object SlideClimbHubSpecs {
    /** The identity colours the integrator adds to `GameThemes.accents["chutes-race"]` (contrast-checked by the tests). */
    const val ACCENT_LIGHT: Long = 0xFF541380
    const val ACCENT_DARK: Long = 0xFFAF85CC

    /** The menu glyph the integrator adds to `GameCatalog` for "chutes-race". */
    const val GLYPH: String = "🎢"

    private val exactFinish = HubOption.Toggle(
        key = "exactFinish", label = "Exact roll to finish", default = false,
        help = "On: land on 100 exactly. Off: bounce back."
    )

    /** The seats at the table in "vs Computers" (you plus 1 to 3 computers) and in "Pass & Play" (people plus computers, at most 4). */
    private fun seatsVsCpu(c: HubChoices): Int = (1 + c.int(HubKeys.CPUS, 1)).coerceIn(2, 4)
    private fun seatsPass(c: HubChoices): Int =
        (c.int(HubKeys.HUMANS, 2).coerceAtLeast(1) + c.int(HubKeys.CPUS, 0).coerceAtLeast(0)).coerceIn(2, 4)

    /**
     * The spare-roll choice is offered once per table size, so it never names a player who is not at the table:
     * `handicap` (2 seats), `handicap3` (3 seats), `handicap4` (4 seats); exactly one of them is visible, and the
     * hidden ones never reach the game (see [slideClimbHandicapFromExtras]).
     */
    private fun handicapFor(seats: Int, seatsOf: (HubChoices) -> Int) = HubOption.Choice(
        key = if (seats == 2) "handicap" else "handicap$seats", label = "Spare roll", default = "none",
        help = "One extra roll to use instead of a bad one.",
        choices = listOf(HubChoice("none", "No spare roll"), HubChoice("all", "Everyone has one")) +
            (1..seats).map { HubChoice("$it", "Player $it only") },
        visibleWhen = { seatsOf(it) == seats }
    )

    private fun handicaps(seatsOf: (HubChoices) -> Int) = listOf(2, 3, 4).map { handicapFor(it, seatsOf) }

    /** The spare-roll value that applies at the table size [seats] (the visible one). */
    internal fun activeHandicap(c: HubChoices, seats: Int): String =
        c.string(if (seats <= 2) "handicap" else "handicap$seats", "none")

    private fun level(seatsOf: (HubChoices) -> Int) = HubOptions.difficulty().copy(
        visibleWhen = { activeHandicap(it, seatsOf(it)) != "none" }
    )

    val slideClimb: GameHubSpec = GameHubSpec(
        gameId = "chutes-race", title = "Slide and Climb",
        tagline = "Roll the dice and race to square 100.",
        family = HubFamily.BOARD,
        audience = GameAudience.KIDS, minAge = 4,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Race 1 to 3 computer players.",
                route = "chutes-race", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(exactFinish) + handicaps({ seatsVsCpu(it) }) +
                    listOf(HubOptions.cpus(1, 3, 1), level({ seatsVsCpu(it) }), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "chutes-race", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(exactFinish) + handicaps({ seatsPass(it) }) + listOf(
                    HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), level({ seatsPass(it) }),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(slideClimb)
}

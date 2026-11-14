package com.gamesuite.games.bumprun

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Bump and Run, kept next to the game. It is NOT registered anywhere: the integrator adds
 * [bumpRun] to `GameHubSpecs.baseSpecs()` (BOARD section) and must NOT list "bump-run" in
 * `GameHubSpecs.networkedGames` (no network engine in the MVP). Every text below has a `hubKey(text)` string in
 * res/values{,-es}/bump_run_strings.xml, or already exists elsewhere ("Team play", mode titles, blurbs, steppers).
 *
 * Option keys reach the screen as `LaunchOptions.extras` (`teams` is consumed by `buildHubLaunch` into
 * `PlayerInfo.teamId`, seats 0 and 2 against 1 and 3):
 *  - `length` ("quick" | "classic" | "long", default quick; see [BumpLength])
 *  - `pads` ("true" / "false", default true)
 *  - `autoPlay` ("true" / "false", default true: a single legal move is played for you)
 *  - `helper` ("none" | "all" | "1".."4": that player, 1-based, gets the helper pack; see [parseBumpRunHelper])
 */
object BumpRunHubSpecs {
    /** Identity colours for `GameThemes.accents["bump-run"]` (contrast vs the reference surfaces: light 7.6 / 6.0, dark 9.6 / 6.9). */
    const val ACCENT_LIGHT: Long = 0xFF0B5A6B
    const val ACCENT_DARK: Long = 0xFF6FD0DE

    /** The menu glyph for `GameCatalog` ("bump-run"); unused by any other game at the time of writing. */
    const val GLYPH: String = "🏃"

    private val length = HubOption.Choice(
        key = "length", label = "Race length", default = "quick",
        help = "More pawns each makes a longer race.",
        choices = listOf(
            HubChoice("quick", "Quick race", "2 pawns each."),
            HubChoice("classic", "Classic race", "3 pawns each."),
            HubChoice("long", "Long race", "4 pawns each.")
        )
    )

    private val pads = HubOption.Toggle(
        key = "pads", label = "Spring pads", default = true,
        help = "On: landing on a pad hops the pawn 3 squares ahead."
    )

    private val autoPlay = HubOption.Toggle(
        key = "autoPlay", label = "Skip obvious moves", default = true,
        help = "On: when only one move is possible, it is played for you."
    )

    private val helper = HubOption.Choice(
        key = "helper", label = "Helper pack", default = "none",
        help = "Three do-overs and one pawn already out of the Nest.",
        choices = listOf(
            HubChoice("none", "No helper pack"),
            HubChoice("all", "Helper pack for everyone"),
            HubChoice("1", "Helper pack: Player 1"),
            HubChoice("2", "Helper pack: Player 2"),
            HubChoice("3", "Helper pack: Player 3"),
            HubChoice("4", "Helper pack: Player 4")
        )
    )

    private fun teamsCpu() = HubOption.Toggle(
        key = HubKeys.TEAMS, label = "Team play", default = false,
        visibleWhen = { it.int(HubKeys.CPUS, 1) >= 3 }
    )

    private fun teamsPass() = HubOption.Toggle(
        key = HubKeys.TEAMS, label = "Team play", default = false,
        visibleWhen = { it.int(HubKeys.HUMANS, 2) + it.int(HubKeys.CPUS, 0) >= 4 }
    )

    val bumpRun: GameHubSpec = GameHubSpec(
        gameId = "bump-run", title = "Bump and Run",
        tagline = "Draw a card, race your pawns home and bump your rivals back.",
        family = HubFamily.BOARD,
        audience = GameAudience.FAMILY, minAge = 6,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Race 1 to 3 computer players.",
                route = "bump-run", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(
                    length, pads, autoPlay, helper, teamsCpu(),
                    HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer")
                )
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "bump-run", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    length, pads, autoPlay, helper, teamsPass(),
                    HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(bumpRun)
}

package com.gamesuite.games.tankbattle

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Tank Battle and Shooting Gallery, kept next to the games until the integrator
 * adds them to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim) and lists both ids in
 * `GameHubSpecs.networkedGames` (both engines are host-authoritative; the Nearby and Online modes are
 * added to the specs automatically). Every text below has a `hubKey(text)` string in
 * res/values{,-es}/tank_gallery_strings.xml (or already exists elsewhere); `TankGalleryHubSpecsTest`
 * proves it.
 *
 * Option keys reach the game screens as `LaunchOptions.extras`: Tank Battle reads `wind` and `specials`
 * ("true" | "false"); Shooting Gallery reads `rounds` ("1" | "3" | "5"), `pace` ("normal" | "relaxed") and
 * `format` ("turns" | "race"). "Who moves first" becomes `LaunchOptions.firstMoverIndex`.
 */
object TankGalleryHubSpecs {
    private val tankRules = listOf(
        HubOption.Toggle(
            key = "wind", label = "Wind", default = true,
            help = "On: a new wind blows every turn and pushes your shells. Off: calm air."
        ),
        HubOption.Toggle(
            key = "specials", label = "Special shells", default = true,
            help = "On: each tank also has 2 big shells and 3 bouncers. Off: plain shells only."
        )
    )

    val tank: GameHubSpec = GameHubSpec(
        gameId = "tank-battle", title = "Tank Battle",
        tagline = "Aim, allow for the wind and blast the hills out from under your rivals.",
        family = HubFamily.ARCADE_PUZZLE,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Duel 1 to 3 computer tanks.",
                route = "tank-battle", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = tankRules + listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "tank-battle", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = tankRules + listOf(
                    HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    private val rounds = HubOption.Choice(
        key = "rounds", label = "Rounds", default = "3",
        help = "Every player shoots the same range each round. The highest total wins.",
        choices = listOf(HubChoice("1", "1 round"), HubChoice("3", "3 rounds"), HubChoice("5", "5 rounds"))
    )

    private val pace = HubOption.Choice(
        key = "pace", label = "Pace", default = "normal",
        help = "Relaxed gives slower, larger targets, a longer turn and a quicker reload, for players who can't tap fast.",
        choices = listOf(
            HubChoice("normal", "Normal pace", "Targets move at full speed."),
            HubChoice("relaxed", "Relaxed pace", "Slower, larger targets and more time.")
        )
    )

    private fun format(visible: (com.gamesuite.core.HubChoices) -> Boolean) = HubOption.Choice(
        key = "format", label = "Game format", default = "turns",
        help = "Take turns on the same range, or race side by side on this device.",
        choices = listOf(
            HubChoice("turns", "Take turns", "One shooter at a time."),
            HubChoice("race", "Split-screen race", "Both shoot at once, each on half of the screen.")
        ),
        visibleWhen = visible
    )

    val gallery: GameHubSpec = GameHubSpec(
        gameId = "shooting-gallery", title = "Shooting Gallery",
        tagline = "Tap moving targets before they pass: chase combos and spare the decoys.",
        family = HubFamily.ARCADE_PUZZLE,
        modes = listOf(
            HubMode(
                id = "practice", kind = HubModeKind.SOLO, title = "Practice",
                blurb = "Shoot the range on your own and beat your best score.",
                route = "shooting-gallery", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(rounds, pace)
            ),
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Shoot against 1 to 3 computer shooters.",
                route = "shooting-gallery", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(rounds, pace, HubOptions.cpus(1, 3, 1), format { it.int("cpus", 1) == 1 }, HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "shooting-gallery", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    rounds, pace, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0),
                    format { it.int("humans", 2) + it.int("cpus", 0) == 2 },
                    HubOptions.difficulty(), HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(tank, gallery)

    /** First-run tips (3 lines each); the EN text equals the `tip_<id>_<n>` string resource (dashes as underscores). */
    val tips: Map<String, List<String>> = mapOf(
        "tank-battle" to listOf(
            "Set the angle and power with the sliders, or drag back from your tank. The arrow shows the wind.",
            "A big shell digs a wide crater, and a bouncer hops over hills. Each tank carries only a few.",
            "Craters change the ground: dig out a rival's footing or bury your own tank. Last tank standing wins."
        ),
        "shooting-gallery" to listOf(
            "Tap a target to shoot it. Six shots fill the magazine, then it reloads. Spare shots are wasted.",
            "Hit targets in a row to build a combo up to times four. A miss or a decoy breaks it.",
            "Can't tap fast? Choose Relaxed pace: slower, larger targets, more time and a quicker reload."
        )
    )

    /** Proposed identity colours (light, dark) for `GameThemes.accents`; TankGalleryHubSpecsTest checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "tank-battle" to (0xFF5F6B1FL to 0xFFC8D86AL),
        "shooting-gallery" to (0xFFB23A5CL to 0xFFFF92A8L)
    )
}

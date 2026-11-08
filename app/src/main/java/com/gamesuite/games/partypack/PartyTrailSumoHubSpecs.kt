package com.gamesuite.games.partypack

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
 * The Game Hub specs of Light-Trail Duel and Sumo Push, kept next to the games until the integrator adds them to
 * `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). Do NOT list either id in
 * `GameHubSpecs.networkedGames`: both are same-screen only in this version (the Nearby and Online modes are not
 * offered). Every text below has a `hubKey(text)` string in res/values{,-es}/trail_sumo_hub_strings.xml (or already
 * exists elsewhere); `PartyTrailSumoHubSpecsTest` proves it.
 *
 * Option keys reach the screens as `LaunchOptions.extras`: `rounds` ("3" | "5"), `arena` ("SMALL" | "MEDIUM" | "LARGE"),
 * `powerups` ("true" | "false"), `relaxed` ("true" | "false"), `controls` ("pads" | "one" for the trail, "stick" |
 * "dpad" | "one" for sumo) and, in the Same screen mode, `zones` ("true" | "false": face each player).
 */
object PartyTrailSumoHubSpecs {
    private fun rounds() = HubOption.Choice(
        key = "rounds", label = "Rounds", default = "3",
        help = "Best of 3 or best of 5. A drawn round is played again.",
        choices = listOf(HubChoice("3", "Best of 3"), HubChoice("5", "Best of 5"))
    )

    private fun arena(help: String) = HubOption.Choice(
        key = "arena", label = "Arena size", default = "MEDIUM", help = help,
        choices = listOf(HubChoice("SMALL", "Small"), HubChoice("MEDIUM", "Medium"), HubChoice("LARGE", "Large"))
    )

    private fun powerUps(help: String) = HubOption.Toggle(key = "powerups", label = "Power-ups", default = false, help = help)

    private fun relaxed(help: String) = HubOption.Toggle(key = "relaxed", label = "Relaxed", default = false, help = help)

    private val zones = HubOption.Toggle(
        key = "zones", label = "Face each player", default = true,
        help = "Turn each player's controls to face their edge of the screen. Switch off to share the bottom edge."
    )

    /** The computer-level choice, shown in Same screen only when a computer plays. */
    private fun levelWhenCpus() = HubOptions.difficulty().copy(visibleWhen = { it.int(HubKeys.CPUS, 0) > 0 })

    val lightTrail: GameHubSpec = run {
        val controls = HubOption.Choice(
            key = "controls", label = "Controls", default = "pads",
            help = "How each player steers. One button is for players who cannot use two pads.",
            choices = listOf(
                HubChoice("pads", "Two pads", "A Left pad and a Right pad for each player."),
                HubChoice("one", "One button", "One button per player. It turns toward the side shown on it, and the side switches every moment.")
            )
        )
        val common = listOf(
            rounds(),
            arena("A bigger arena gives more room to run and takes longer to fill."),
            powerUps("Glowing orbs: Gap lets you slip through walls for a few cells, Boost doubles your speed for a moment."),
            relaxed("Slower riders, a wider arena, and the game steers you away from a wall now and then. For players who find fast games hard."),
            controls
        )
        GameHubSpec(
            gameId = "light-trail", title = "Light-Trail Duel",
            tagline = "Glowing walls, one survivor. Steer left or right and trap your rivals.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Out-ride the computer. Computers fill the other zones.",
                    route = "light-trail", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty()) + common
                ),
                HubMode(
                    id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                    blurb = "2 to 4 people on one screen, each with their own pads. Computers can fill empty zones.",
                    route = "light-trail", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), levelWhenCpus()) + common + zones
                )
            )
        )
    }

    val sumoPush: GameHubSpec = run {
        val controls = HubOption.Choice(
            key = "controls", label = "Controls", default = "stick",
            help = "How each player steers. One button is for players who cannot use a stick.",
            choices = listOf(
                HubChoice("stick", "Stick", "Drag a thumb-stick to steer your sumo."),
                HubChoice("dpad", "Direction pad", "Hold a direction pad to steer your sumo."),
                HubChoice("one", "One button", "Your sumo steers itself toward the nearest rival. You only press Dash.")
            )
        )
        val common = listOf(
            rounds(),
            arena("A bigger ring takes more pushing to clear."),
            powerUps("Orbs in the ring: Heavy makes you hard to shove, Sprint makes you quicker."),
            relaxed("Slower sumos, a bigger ring that shrinks slowly, and a rope grip that stops you steering off the edge. For players who find fast games hard."),
            controls
        )
        GameHubSpec(
            gameId = "sumo-push", title = "Sumo Push",
            tagline = "Round toy sumos in a shrinking ring. Shove everyone else out.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Push the computer out of the ring. Computers fill the other zones.",
                    route = "sumo-push", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty()) + common
                ),
                HubMode(
                    id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                    blurb = "2 to 4 people on one screen, each with their own stick. Computers can fill empty zones.",
                    route = "sumo-push", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), levelWhenCpus()) + common + zones
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(lightTrail, sumoPush)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_light_trail_1..3` /
     * `tip_sumo_push_1..3` strings in values/; values-es/ has the Spanish.
     */
    val tips: Map<String, List<String>> = mapOf(
        "light-trail" to listOf(
            "Your Left and Right pads turn the way the rider is facing, not the way you are. Watch the glowing head.",
            "Leave yourself room: a wall you can see is a wall you can turn away from. Trap rivals by cutting across in front of them.",
            "Hard to play fast? Turn on Relaxed or One button in the setup."
        ),
        "sumo-push" to listOf(
            "Hold Dash to charge, release to shove. A charged dash is heavy, but then you wait for the cooldown.",
            "The ring shrinks over time. Stay near the middle and let rivals push each other out.",
            "Hard to steer? Turn on Relaxed or One button in the setup."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "light-trail" to (0xFF00669BL to 0xFF5BD2FFL),
        "sumo-push" to (0xFFA6402BL to 0xFFF5927AL)
    )
}

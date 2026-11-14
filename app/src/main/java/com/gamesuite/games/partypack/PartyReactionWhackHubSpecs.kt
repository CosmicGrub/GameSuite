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
 * The Game Hub specs of Reaction Duel and Whack-a-Mole Race, kept next to the games until the integrator adds them
 * to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). Both are SAME-SCREEN games: do NOT list
 * their ids in `GameHubSpecs.networkedGames` (no Nearby or Online mode in this version; the engines have no
 * sync protocol, and a shared signal moment cannot be fair across two devices without one).
 *
 * Every text below has a `hubKey(text)` string in res/values{,-es}/reaction_whack_hub_strings.xml (or already
 * exists elsewhere); `PartyReactionWhackHubSpecsTest` proves it.
 *
 * Option keys reach the screens as `LaunchOptions.extras`. Reaction Duel: `variant` ("classic" | "colour" | "echo"),
 * `best` ("3" | "5" | "7"), `relaxed`, `oneSwitch`, `orient` ("true" | "false"). Whack-a-Mole: `time` ("60" | "90" |
 * "120" seconds), `grid` ("3" | "4"), `relaxed`, `oneSwitch`, `orient`. The seat counts are the standard `humans` and
 * `cpus` options (2 to 4 seats in all; computers fill empty zones), the level the standard `difficulty`.
 */
object PartyReactionWhackHubSpecs {
    private val needsLevel: (com.gamesuite.core.HubChoices) -> Boolean =
        { it.int(HubKeys.CPUS, 0) > 0 || it.int(HubKeys.HUMANS, 2) < 2 }

    private val orient = HubOption.Toggle(
        key = "orient", label = "Turn zones to face players", default = true,
        help = "Each zone turns to face the player around the device. Switch off to keep every zone upright."
    )

    private val sameScreenHumans = HubOption.Stepper(
        key = HubKeys.HUMANS, label = "People playing", min = 1, max = 4, default = 2,
        help = "People who play at the same time, each in a zone of their own."
    )

    // ---- Reaction Duel ----

    val reactionDuel: GameHubSpec = run {
        val variant = HubOption.Choice(
            key = "variant", label = "Duel type", default = "classic",
            help = "Classic: press when GO appears. Colour match: press only on the target shape. Echo: tap the pattern back first.",
            choices = listOf(
                HubChoice("classic", "Classic go-signal", "Wait, then press the moment GO appears. Pressing early puts you out of the round."),
                HubChoice("colour", "Colour match", "Shapes flash past. Press only when the target shape shows: the shape tells it apart, not the colour."),
                HubChoice("echo", "Echo", "Watch a pattern of four shapes, then be the first to tap it back without a mistake.")
            )
        )
        val best = HubOption.Choice(
            key = "best", label = "Duel length", default = "3",
            help = "How many rounds the duel is played over. The first to win more than half takes it.",
            choices = listOf(HubChoice("3", "Best of 3"), HubChoice("5", "Best of 5"), HubChoice("7", "Best of 7"))
        )
        val relaxed = HubOption.Toggle(
            key = "relaxed", label = "Relaxed", default = false,
            help = "For players who find quick presses hard: longer windows and no penalty for pressing early or wrongly."
        )
        val oneSwitch = HubOption.Toggle(
            key = "oneSwitch", label = "One-switch play", default = false,
            help = "Echo scans the four pads for you and one press picks the lit one. The other duels already need one button."
        )
        GameHubSpec(
            gameId = "reaction-duel", title = "Reaction Duel",
            tagline = "Wait for the signal, then press first. Jump the gun and you are out of the round.",
            family = HubFamily.ARCADE_PUZZLE, resumable = true,
            modes = listOf(
                HubMode(
                    id = "screen", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                    blurb = "Everyone plays at once on this screen, each in a zone of their own. Computers fill any empty zone.",
                    route = "reaction-duel", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(
                        sameScreenHumans, HubOptions.cpus(0, 3, 0),
                        HubOptions.difficulty().copy(visibleWhen = needsLevel),
                        variant, best, relaxed, oneSwitch, orient
                    )
                ),
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Race one to three computers, each in a zone of its own.",
                    route = "reaction-duel", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(
                        HubOptions.difficulty(), HubOptions.cpus(1, 3, 1),
                        variant, best, relaxed, oneSwitch, orient
                    )
                )
            )
        )
    }

    // ---- Whack-a-Mole Race ----

    val whackAMole: GameHubSpec = run {
        val time = HubOption.Choice(
            key = "time", label = "Round length", default = "90",
            help = "The round ends when the clock runs out. The highest score wins.",
            choices = listOf(HubChoice("60", "60 seconds"), HubChoice("90", "90 seconds"), HubChoice("120", "120 seconds"))
        )
        val grid = HubOption.Choice(
            key = "grid", label = "Holes per zone", default = "3",
            help = "Every zone gets the same critters at the same moments, so the race is fair.",
            choices = listOf(
                HubChoice("3", "3 x 3 holes", "Bigger holes, easier to hit."),
                HubChoice("4", "4 x 4 holes", "More critters at once. On a small screen with three or four players the grid shrinks so every hole stays big enough to tap.")
            )
        )
        val relaxed = HubOption.Toggle(
            key = "relaxed", label = "Relaxed", default = false,
            help = "For players who find fast taps hard: slower critters that stay up longer, big 3 x 3 holes, and no penalties."
        )
        val oneSwitch = HubOption.Toggle(
            key = "oneSwitch", label = "One-switch play", default = false,
            help = "A cursor sweeps over your holes and one press whacks the hole it is on. Critters stay up much longer."
        )
        GameHubSpec(
            gameId = "whack-a-mole", title = "Whack-a-Mole Race",
            tagline = "Everyone whacks the same critters at once. Spare the spiky ones.",
            family = HubFamily.ARCADE_PUZZLE, resumable = true,
            modes = listOf(
                HubMode(
                    id = "screen", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                    blurb = "Everyone plays at once on this screen, each in a zone of their own. Computers fill any empty zone.",
                    route = "whack-a-mole", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(
                        sameScreenHumans, HubOptions.cpus(0, 3, 0),
                        HubOptions.difficulty().copy(visibleWhen = needsLevel),
                        time, grid, relaxed, oneSwitch, orient
                    )
                ),
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Race one to three computers, each in a zone of its own.",
                    route = "whack-a-mole", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(
                        HubOptions.difficulty(), HubOptions.cpus(1, 3, 1),
                        time, grid, relaxed, oneSwitch, orient
                    )
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(reactionDuel, whackAMole)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_reaction_duel_1..3` /
     * `tip_whack_a_mole_1..3` strings in values/; values-es/ has the Spanish.
     */
    val tips: Map<String, List<String>> = mapOf(
        "reaction-duel" to listOf(
            "Rest a finger by your button, but do not press until the signal. An early press puts you out of the round.",
            "In Colour match only the target shape counts. Check the shape, never just the colour.",
            "Finding it hard? Turn on Relaxed in the setup for longer windows and no penalties."
        ),
        "whack-a-mole" to listOf(
            "Spiky urchins cost points, so tap only the round-faced critters. Golden ones are worth triple.",
            "Whack in a row without missing to build a streak: five makes it times two, ten times three.",
            "Every zone gets the same critters at the same moments, so it is a fair race."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "reaction-duel" to (0xFF12507AL to 0xFF86C8F5L),
        "whack-a-mole" to (0xFF9C5638L to 0xFFF0B096L)
    )
}

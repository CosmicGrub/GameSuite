package com.gamesuite.games.slotcars

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of Slot Cars and Fishing (wave 4b, "arcade"), kept next to the games until the
 * integrator adds them to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim) and
 * lists both ids in `networkedGames` (both engines are host-authoritative). Every text below has a
 * `hub_*` string in res/values{,-es}/slotcars_fishing_hub_strings.xml (or already exists elsewhere);
 * `SlotCarsFishingHubSpecsTest` proves it.
 *
 * Option keys reach the screens as `LaunchOptions.extras`: Slot Cars reads `laps` ("3", "5", "10"),
 * `track` ("meadow", "hill", "twist") and `assist` ("off", "slow", "step"); Fishing reads `lake`
 * ("pond", "lake", "reservoir"), `rounds` ("3", "5", "8") and `assist` ("true").
 */
object SlotCarsFishingHubSpecs {
    val slotCars: GameHubSpec = run {
        val laps = HubOption.Choice(
            key = "laps", label = "Laps to race", default = "3",
            help = "How many laps make the race.",
            choices = listOf(HubChoice("3", "3 laps"), HubChoice("5", "5 laps"), HubChoice("10", "10 laps"))
        )
        val track = HubOption.Choice(
            key = "track", label = "Track", default = "meadow",
            help = "Three original layouts. Bends ahead are marked with amber signs.",
            choices = listOf(
                HubChoice("meadow", "Meadow Oval", "A friendly oval with one bend that needs care."),
                HubChoice("hill", "Hairpin Hill", "Long straights and a tight hairpin."),
                HubChoice("twist", "Twister", "A loop of S bends.")
            )
        )
        val assist = HubOption.Choice(
            key = "assist", label = "Race speed", default = "off",
            help = "For players who can't play in real time: slow motion halves the speed, step by step lets you plan each half second.",
            choices = listOf(
                HubChoice("off", "Real time"),
                HubChoice("slow", "Slow motion", "The whole race runs at half speed."),
                HubChoice("step", "Step by step", "Turn-based: choose throttle or coast for each half second, then press Go.")
            )
        )
        GameHubSpec(
            gameId = "slot-cars", title = "Slot Cars",
            tagline = "Hold to speed up and brake before the bends.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Race against computer drivers.",
                    route = "slot-cars", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.cpus(1, 3, 1), laps, track, assist, HubOptions.difficulty())
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Two to four drivers share this device, one pad each.",
                    route = "slot-cars", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.humans(2, 4, 2), laps, track, assist)
                )
            )
        )
    }

    val fishing: GameHubSpec = run {
        val lake = HubOption.Choice(
            key = "lake", label = "Lake", default = "pond",
            help = "Where you fish decides which fish are biting.",
            choices = listOf(
                HubChoice("pond", "Willow Pond", "Shallow and friendly: small and medium fish."),
                HubChoice("lake", "Clear Lake", "A bit of everything."),
                HubChoice("reservoir", "Deep Reservoir", "Deep water with big, rare fish.")
            )
        )
        val rounds = HubOption.Choice(
            key = "rounds", label = "Casts each", default = "3",
            help = "How many casts every angler gets. The most weight wins.",
            choices = listOf(HubChoice("3", "3 casts"), HubChoice("5", "5 casts"), HubChoice("8", "8 casts"))
        )
        val relaxed = HubOption.Toggle(
            key = "assist", label = "Relaxed fishing", default = false,
            help = "Longer bite and reel windows and gentler fish, for players who can't react quickly."
        )
        GameHubSpec(
            gameId = "fishing", title = "Fishing",
            tagline = "Cast, hook and reel in the biggest catch.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "solo", kind = HubModeKind.SOLO, title = "Solo Fishing",
                    blurb = "Fish on your own and beat your best haul.",
                    route = "fishing", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                    options = listOf(lake, rounds, relaxed)
                ),
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "A fishing contest against computer anglers.",
                    route = "fishing", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.cpus(1, 3, 1), lake, rounds, relaxed, HubOptions.difficulty())
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Anglers take turns on one device.",
                    route = "fishing", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.humans(2, 4, 2), lake, rounds, relaxed)
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(slotCars, fishing)

    /** First-run tips (3 lines each). The English lines must equal `tip_slot_cars_1..3` / `tip_fishing_1..3`. */
    val tips: Map<String, List<String>> = mapOf(
        "slot-cars" to listOf(
            "Hold your pad to speed up and let go to brake. Slow down before the amber signs.",
            "Too fast in a bend and the car leaves the slot and loses time. Watch the grip bar.",
            "Too quick? Turn on Slow motion or Step by step in the setup."
        ),
        "fishing" to listOf(
            "Pick a spot, hold the button and let go to cast. The farther out, the bigger the fish.",
            "Press the button when the bobber plunges, not for small nibbles.",
            "Hold to reel in, let go when the fish pulls, and keep the tension bar in the Good zone."
        )
    )
}

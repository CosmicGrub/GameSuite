package com.gamesuite.games.bridgebuilders

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
 * The Game Hub spec of the cooperative Bridge Builders, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (see integration-notes.md). NOT networked: do not list "bridge-builders" in
 * `GameHubSpecs.networkedGames` (the engine is same-screen only).
 *
 * Every text has a `hubKey(text)` string in res/values{,-es}/bridge_builders_hub_strings.xml, except the handful
 * that already exist elsewhere (the shared "Same screen" title, the difficulty words, the rotate toggle's two texts).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `band` (little | medium | big), `relaxed`, `handicap`
 * (none | p1..p2 at a 2-lane table), `handicap3` (none | p1..p3, 3 lanes), `handicap4` (none | p1..p4, 4 lanes) (only
 * the one for the table size is on offer; see [BridgeHandicap.fromExtras]), `rotate`. The helper robots are the computer seats (`cpus`) and `difficulty` is their level.
 */
object BridgeBuildersHubSpecs {
    private val band = HubOption.Choice(
        key = "band", label = "Bridge size", default = "medium",
        help = "How many planks the bridge needs, how big the green spots are and how fast the water rises.",
        choices = listOf(
            HubChoice("little", "Little bridge", "A short bridge, wide green spots and slow water: great for the youngest builders."),
            HubChoice("medium", "Medium bridge", "A good mix for most families."),
            HubChoice("big", "Big bridge", "A long bridge, small green spots and quick water for practised builders.")
        )
    )
    private val relaxed = HubOption.Toggle(
        key = "relaxed", label = "Relaxed building", default = false,
        help = "Every tap places a plank, even outside the green spot, and the cart and the water are slower. Tapping nearer the middle still makes a sturdier plank."
    )
    /** Lanes at the table: you plus 1 to 3 robots, or people plus robots; always 2 to 4 (the Hub pads and trims the same way). */
    private fun lanesVsCpu(c: HubChoices): Int = (1 + c.int(HubKeys.CPUS, 1)).coerceIn(2, 4)
    private fun lanesSame(c: HubChoices): Int =
        (c.int(HubKeys.HUMANS, 2).coerceAtLeast(1) + c.int(HubKeys.CPUS, 0).coerceAtLeast(0)).coerceIn(2, 4)

    /** One handicap choice per table size, so it never offers a lane that does not exist. Exactly one is visible. */
    private fun handicapFor(lanes: Int, lanesOf: (HubChoices) -> Int) = HubOption.Choice(
        key = if (lanes == 2) "handicap" else "handicap$lanes", label = "Bigger green spot for", default = "none",
        help = "Give one builder a wider green spot so different ages can build side by side.",
        choices = listOf(HubChoice("none", "Nobody, all equal")) + (1..lanes).map { HubChoice("p$it", "Lane $it") },
        visibleWhen = { lanesOf(it) == lanes }
    )

    private fun handicaps(lanesOf: (HubChoices) -> Int) = listOf(2, 3, 4).map { handicapFor(it, lanesOf) }
    private val rotate = HubOption.Toggle(
        key = "rotate", label = "Turn the far lanes around", default = true,
        help = "Players sitting opposite each other read their own lane the right way up. Switch off for one person holding the device."
    )
    private val robotLevel = HubOptions.difficulty().copy(label = "Helper robot level")

    val spec: GameHubSpec = GameHubSpec(
        gameId = "bridge-builders", title = "Bridge Builders",
        tagline = "Build a bridge together before the water rises.",
        family = HubFamily.ARCADE_PUZZLE,
        audience = GameAudience.KIDS, minAge = 5,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "Build with helper robots",
                blurb = "You and 1 to 3 friendly helper robots build one bridge together. Each of you has a lane.",
                route = "bridge-builders", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(
                    band, HubOptions.cpus(1, 3, 1).copy(label = "Helper robots", help = "Robots build a lane of their own, so a small team always has enough builders."),
                    robotLevel, relaxed
                ) + handicaps({ lanesVsCpu(it) }) + listOf(rotate)
            ),
            HubMode(
                id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                blurb = "Two to four people build one bridge on the same screen, each in their own lane. Helper robots can fill empty lanes.",
                route = "bridge-builders", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    band, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0).copy(label = "Helper robots", help = "Robots build a lane of their own, so a small team always has enough builders."),
                    robotLevel, relaxed
                ) + handicaps({ lanesSame(it) }) + listOf(rotate)
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_bridge_builders_1..3` strings
     * in values/polish_strings.xml; values-es/ has the Spanish. GameTipsTest reads only polish_strings.xml,
     * so move the three strings there when you paste these.
     */
    val tips: Map<String, List<String>> = mapOf(
        "bridge-builders" to listOf(
            "Tap your lane when the sliding cart is inside the green spot. The nearer the middle, the sturdier the plank.",
            "Nobody can build more than two planks ahead of the slowest lane, so wait for your friends: the rope is tight.",
            "Finish every lane before the water covers the bridge, then everybody crosses together."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "bridge-builders" to (0xFF8A5A00L to 0xFFF2C46BL)
    )
}

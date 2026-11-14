package com.gamesuite.games.trailtogether

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of the cooperative Trail Together, kept next to the game. NOT registered anywhere: the
 * coordinator adds it to `GameHubSpecs.baseSpecs()` (see integration-notes.md). NOT networked: do not list
 * "trail-together" in `GameHubSpecs.networkedGames`.
 *
 * Every text has a `hubKey(text)` string in res/values{,-es}/trail_together_hub_strings.xml, except the handful that
 * already exist elsewhere (the shared "Same screen" title, the difficulty words, "Nobody, all equal" ...).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `band` (little | medium | big), `hints` (auto | on | off;
 * "auto" is the design's rule: the helper glow is on for the Little trail only), `rotate`, and the handicap for the table
 * size: `handicap` (none | p1..p2, 2 seats), `handicap3` (none | p1..p3), `handicap4` (none | p1..p4); only the one for the
 * table size is on offer (see [TrailHandicap.fromExtras]). The helper pals are the computer seats (`cpus`) and
 * `difficulty` is their level.
 */
object TrailTogetherHubSpecs {
    private val band = HubOption.Choice(
        key = "band", label = "Trail size", default = "medium",
        help = "How long the trail is, how many colours it has and how fast the fog comes.",
        choices = listOf(
            HubChoice("little", "Little trail", "A short trail with four colours: great for the youngest players."),
            HubChoice("medium", "Medium trail", "A good mix for most families."),
            HubChoice("big", "Big trail", "A long trail with six colours for practised players.")
        )
    )

    private val hints = HubOption.Choice(
        key = "hints", label = "Helper glow", default = "auto",
        help = "A soft glow on a good card. The Help me choose button always works, whatever you pick here.",
        choices = listOf(
            HubChoice("auto", "Little trail only", "The glow shows on the Little trail and stays off on the bigger ones."),
            HubChoice("on", "Always", "The glow shows on every trail."),
            HubChoice("off", "Never", "No glow. Use Help me choose when you want a hint.")
        )
    )

    private val rotate = HubOption.Toggle(
        key = "rotate", label = "Turn the far hands around", default = true,
        help = "Players sitting opposite each other read their own cards the right way up. Switch off for one person holding the device."
    )

    private val helperLevel = HubOptions.difficulty().copy(label = "Helper pal level")

    private val helperPals = HubOptions.cpus(0, 2, 0).copy(
        label = "Helper pals", help = "Helper pals take their own turns, so a small team always has a full table."
    )

    /** Seats at the table: you plus 1 to 3 helpers, or people plus helpers; always 2 to 4 (the Hub pads and trims the same way). */
    private fun seatsVsCpu(c: HubChoices): Int = (1 + c.int(HubKeys.CPUS, 1)).coerceIn(2, 4)
    private fun seatsSame(c: HubChoices): Int =
        (c.int(HubKeys.HUMANS, 2).coerceAtLeast(1) + c.int(HubKeys.CPUS, 0).coerceAtLeast(0)).coerceIn(2, 4)

    /** One handicap choice per table size, so it never offers a seat that does not exist. Exactly one is visible. */
    private fun handicapFor(seats: Int, seatsOf: (HubChoices) -> Int) = HubOption.Choice(
        key = TrailHandicap.keyFor(seats), label = "Extra card for", default = "none",
        help = "Give one player a fourth card so different ages can play side by side.",
        choices = listOf(HubChoice("none", "Nobody, all equal")) + (1..seats).map { HubChoice("p$it", "Player $it") },
        visibleWhen = { seatsOf(it) == seats }
    )

    private fun handicaps(seatsOf: (HubChoices) -> Int) = listOf(2, 3, 4).map { handicapFor(it, seatsOf) }

    val spec: GameHubSpec = GameHubSpec(
        gameId = "trail-together", title = "Trail Together",
        tagline = "Light the trail and get home before the fog!",
        family = HubFamily.BOARD,
        audience = GameAudience.KIDS, minAge = 4,
        modes = listOf(
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Play on my own",
                blurb = "You move two hedgehog pals home along the trail.",
                route = "trail-together", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(band, hints)
            ),
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "Play with helper pals",
                blurb = "You and 1 to 3 friendly helper pals get everyone home together.",
                route = "trail-together", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(band, HubOptions.cpus(1, 3, 1).copy(label = "Helper pals", help = helperPals.help), helperLevel, hints) +
                    handicaps { seatsVsCpu(it) } + listOf(rotate)
            ),
            HubMode(
                id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                blurb = "Two to four people play together on one screen. Helper pals can fill empty seats.",
                route = "trail-together", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(band, HubOptions.humans(2, 4, 2), helperPals, helperLevel, hints) +
                    handicaps { seatsSame(it) } + listOf(rotate)
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_trail_together_1..3` strings
     * in values/trail_together_strings.xml; values-es/ has the Spanish. GameTipsTest reads only polish_strings.xml,
     * so move the three strings there when you paste these.
     */
    val tips: Map<String, List<String>> = mapOf(
        "trail-together" to listOf(
            "A hop card sends a pal to the next stone of the same colour. A stone card lights a new stone at the end of the trail.",
            "Every colour has its own shape, so you can match stones and cards by shape too.",
            "Talk together before you pick a card: which one helps a friend the most?"
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "trail-together" to (0xFF2E6B3FL to 0xFF8FD6A0L)
    )
}

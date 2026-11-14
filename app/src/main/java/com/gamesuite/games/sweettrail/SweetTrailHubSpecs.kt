package com.gamesuite.games.sweettrail

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
 * The Game Hub spec of Sweet Trail, kept next to the game. It is NOT registered anywhere: the integrator adds
 * [sweetTrail] to `GameHubSpecs.baseSpecs()` (BOARD section) and must NOT list "sweet-trail" in
 * `GameHubSpecs.networkedGames` (see integration-notes.md). Every text below has a `hubKey(text)` string in
 * res/values{,-es}/sweet_trail_strings.xml, or already exists elsewhere (the shared mode titles, blurbs and
 * stepper labels).
 *
 * Option keys reach the screen as `LaunchOptions.extras`:
 *  - `length` ("short" | "standard" | "long", default standard; see [TrailLength])
 *  - `sticky` ("true" / "false", default true: the Gooey Puddle costs one turn)
 *  - `wish` (2 seats) / `wish3` (3 seats) / `wish4` (4 seats): "none" | "all" | "1".."seats": that player,
 *    1-based, holds one wish card; see [sweetTrailWishFromExtras]. Exactly one of the three is visible, so the
 *    Hub never offers a seat that is not at the table.
 * The computer level only matters once a wish is in play, so it is hidden until then.
 */
object SweetTrailHubSpecs {
    /**
     * Identity colours for `GameThemes.accents["sweet-trail"]`. Contrast vs the reference surfaces: light 12.9 / 10.2,
     * dark 6.3 / 4.5; label text on the accent: white 13.2 (light), near-black 7.0 (dark). Nearest existing accent by
     * plain RGB distance: light 53 (texas-holdem), dark 42 (darts), the largest gap a search found at these contrasts
     * for a pink / berry hue (the scaffold's first pair was only 16 / 10 from hanafuda).
     */
    const val ACCENT_LIGHT: Long = 0xFF660027
    const val ACCENT_DARK: Long = 0xFFFF4BFF

    /** The menu glyph for `GameCatalog` ("sweet-trail"); unused by any other game at the time of writing. */
    const val GLYPH: String = "🍬"

    private val length = HubOption.Choice(
        key = "length", label = "Trail length", default = "standard",
        help = "A shorter or longer race.",
        choices = listOf(
            HubChoice("short", "Short trail", "Start part-way along."),
            HubChoice("standard", "Full trail", "The whole trail."),
            HubChoice("long", "Long trail", "No shortcuts and one extra puddle.")
        )
    )

    private val puddle = HubOption.Toggle(
        key = "sticky", label = "Gooey Puddle", default = true,
        help = "On: landing in it means waiting one turn. Off: it is a plain square."
    )

    /** The seats at the table in "vs Computers" (you plus 1 to 3 computers) and in "Pass & Play" (people plus computers, at most 4). */
    private fun seatsVsCpu(c: HubChoices): Int = (1 + c.int(HubKeys.CPUS, 1)).coerceIn(2, 4)
    private fun seatsPass(c: HubChoices): Int =
        (c.int(HubKeys.HUMANS, 2).coerceAtLeast(1) + c.int(HubKeys.CPUS, 0).coerceAtLeast(0)).coerceIn(2, 4)

    private fun playerChoice(n: Int) = HubChoice("$n", "Wish: Player $n")

    private fun wishFor(seats: Int, seatsOf: (HubChoices) -> Int) = HubOption.Choice(
        key = if (seats == 2) "wish" else "wish$seats", label = "Wish card", default = "none",
        help = "One wish per game lets a player swap a card they drew.",
        choices = listOf(HubChoice("none", "No wish"), HubChoice("all", "Wish for everyone")) + (1..seats).map { playerChoice(it) },
        visibleWhen = { seatsOf(it) == seats }
    )

    private fun wishes(seatsOf: (HubChoices) -> Int) = listOf(2, 3, 4).map { wishFor(it, seatsOf) }

    /** The wish value that applies at the table size [seats] (the visible option's). */
    internal fun activeWish(c: HubChoices, seats: Int): String =
        c.string(if (seats <= 2) "wish" else "wish$seats", "none")

    private fun level(seatsOf: (HubChoices) -> Int) = HubOptions.difficulty().copy(
        visibleWhen = { activeWish(it, seatsOf(it)) != "none" }
    )

    val sweetTrail: GameHubSpec = GameHubSpec(
        gameId = "sweet-trail", title = "Sweet Trail",
        tagline = "Draw a colour card and race along the trail.",
        family = HubFamily.BOARD,
        audience = GameAudience.KIDS, minAge = 3,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Race 1 to 3 computer players.",
                route = "sweet-trail", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(length, puddle) + wishes({ seatsVsCpu(it) }) +
                    listOf(HubOptions.cpus(1, 3, 1), level({ seatsVsCpu(it) }), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "sweet-trail", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(length, puddle) + wishes({ seatsPass(it) }) + listOf(
                    HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), level({ seatsPass(it) }),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    /**
     * The three first-run tips for `GameTips.tips["sweet-trail"]`. The English literals equal the resources
     * `draft_tip_sweet_trail_1..3` in sweet_trail_strings.xml; when the integrator registers them it renames those
     * resources to `tip_sweet_trail_1..3` (both locales), because GameTipsTest rejects `tip_*` strings that no
     * `GameTips` entry owns. See integration-notes.md.
     */
    val tips: Map<String, List<String>> = mapOf(
        "sweet-trail" to listOf(
            "Tap the card pile to draw. Your piece moves for you.",
            "Look for shortcuts: bridges, ladders and ziplines!",
            "Gooey Puddle: wait one turn. A wish lets you swap one card."
        )
    )

    val all: List<GameHubSpec> = listOf(sweetTrail)
}

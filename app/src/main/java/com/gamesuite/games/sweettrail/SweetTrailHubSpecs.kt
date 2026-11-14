package com.gamesuite.games.sweettrail

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Sweet Trail, kept next to the game. It is NOT registered anywhere: the integrator adds
 * [sweetTrail] to `GameHubSpecs.baseSpecs()` (BOARD section) and must NOT list "sweet-trail" in
 * `GameHubSpecs.networkedGames`. Every text below has a `hubKey(text)` string in
 * res/values{,-es}/sweet_trail_strings.xml, or already exists elsewhere (the shared mode titles, blurbs and
 * stepper labels).
 *
 * Option keys reach the screen as `LaunchOptions.extras`:
 *  - `length` ("short" | "standard" | "long", default standard; see [TrailLength])
 *  - `sticky` ("true" / "false", default true: the Gooey Puddle costs one turn)
 *  - `wish` ("none" | "all" | "1".."4": that player, 1-based, holds one wish card; see [parseSweetTrailWish])
 * The computer level only matters once a wish is in play, so it is hidden until then.
 */
object SweetTrailHubSpecs {
    /** Identity colours for `GameThemes.accents["sweet-trail"]` (contrast vs the reference surfaces: light 7.6 / 6.0, dark 7.6 / 5.4). */
    const val ACCENT_LIGHT: Long = 0xFF9C1B52
    const val ACCENT_DARK: Long = 0xFFF08FB4

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

    private val wish = HubOption.Choice(
        key = "wish", label = "Wish card", default = "none",
        help = "One wish per game lets a player swap a card they drew.",
        choices = listOf(
            HubChoice("none", "No wish"),
            HubChoice("all", "Wish for everyone"),
            HubChoice("1", "Wish: Player 1"),
            HubChoice("2", "Wish: Player 2"),
            HubChoice("3", "Wish: Player 3"),
            HubChoice("4", "Wish: Player 4")
        )
    )

    private fun level() = HubOptions.difficulty().copy(
        visibleWhen = { it.string("wish", "none") != "none" }
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
                options = listOf(length, puddle, wish, HubOptions.cpus(1, 3, 1), level(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "sweet-trail", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    length, puddle, wish, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), level(),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(sweetTrail)
}

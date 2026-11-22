package com.gamesuite.games.gemcascade

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Gem Cascade, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (see integration-notes.md). It is NOT networked: do not list "gem-cascade" in
 * `GameHubSpecs.networkedGames`. Every text has a `hubKey(text)` string in res/values{,-es}, either in
 * gem_cascade_hub_strings.xml or already defined elsewhere (the shared "Play", "Classic", "Board size" ...).
 *
 * Option keys reach the screen as `LaunchOptions.extras`:
 *  - `type`: a [GemMode] id (classic, zen, lightning, quest, twist, mine, butterflies, countdown)
 *  - `level`: Quest level 1..30 (only meaningful for `type = quest`)
 *  - `size`: board size 6..10 (ignored by Quest, whose levels fix their own size)
 *  - `switchMode`: "true" turns on the one-switch scanning input
 * The Daily mode opens the route [DAILY_ROUTE]; its screen passes today's UTC date to the game.
 */
object GemCascadeHubSpecs {
    const val GAME_ID = "gem-cascade"
    const val DAILY_ROUTE = "gem-cascade-daily"

    /** Identity colours for `GameThemes.accents["gem-cascade"]` (contrast-checked by the tests). */
    const val ACCENT_LIGHT: Long = 0xFF0B6B73
    const val ACCENT_DARK: Long = 0xFF5CD6D0

    /** The menu glyph for `GameCatalog`. */
    const val GLYPH: String = "💎"

    private fun isQuest(c: HubChoices) = c.string("type", "classic") == "quest"

    private val type = HubOption.Choice(
        key = "type", label = "Game type", default = "classic",
        help = "Pick how you want to play.",
        choices = listOf(
            HubChoice("classic", "Classic", "Level up until no moves are left."),
            HubChoice("zen", "Zen", "Relax: no timer and no ending."),
            HubChoice("lightning", "Lightning", "Beat the clock: combos add time."),
            HubChoice("quest", "Quest", "Puzzle levels with goals and obstacles."),
            HubChoice("twist", "Twist", "Turn 2 by 2 blocks to make matches."),
            HubChoice("mine", "Mine", "Dig down to bring treasure to the bottom."),
            HubChoice("butterflies", "Butterflies", "Guide butterflies to the top and stop the spiders."),
            HubChoice("countdown", "Countdown", "Defuse the bombs before they go off.")
        )
    )

    private val level = HubOption.Stepper(
        key = "level", label = "Quest level", min = 1, max = 30, default = 1,
        help = "Which puzzle level to play.", visibleWhen = { isQuest(it) }
    )

    private val size = HubOption.Choice(
        key = "size", label = "Board size", default = "8",
        help = "Bigger boards have more gems.",
        choices = listOf(
            HubChoice("6", "6 by 6"), HubChoice("7", "7 by 7"), HubChoice("8", "8 by 8"),
            HubChoice("9", "9 by 9"), HubChoice("10", "10 by 10")
        ),
        visibleWhen = { !isQuest(it) }
    )

    private val switchMode = HubOption.Toggle(
        key = "switchMode", label = "One-switch mode", default = false,
        help = "The board scans by itself so one button can play."
    )

    val gemCascade: GameHubSpec = GameHubSpec(
        gameId = GAME_ID, title = "Gem Cascade",
        tagline = "Match gems, chain cascades and reach the goals.",
        family = HubFamily.ARCADE_PUZZLE,
        audience = GameAudience.FAMILY, minAge = 6,
        modes = listOf(
            HubMode(
                id = "play", kind = HubModeKind.SOLO, title = "Play", blurb = "Play on your own.",
                route = GAME_ID, start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(type, level, size, switchMode)
            ),
            HubMode(
                id = "daily", kind = HubModeKind.DAILY, title = "Daily challenge",
                blurb = "The same puzzle for everyone today.",
                route = DAILY_ROUTE, start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(switchMode)
            )
        )
    )

    val all: List<GameHubSpec> = listOf(gemCascade)
}

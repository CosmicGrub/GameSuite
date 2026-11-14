package com.gamesuite.games.territorywars

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
 * The Game Hub spec of Territory Wars. NOT REGISTERED ANYWHERE: the integrator adds [territoryWars] to
 * `GameHubSpecs.baseSpecs()` (BOARD section) when the game is playable; do not list "territory-wars" in
 * `GameHubSpecs.networkedGames` (no host or message type exists in the MVP). Every text has a `hubKey(text)` string in
 * res/values{,-es}/territory_wars_strings.xml, or already exists in strings.xml (the shared mode titles, the
 * Pass and Play blurb, the computer-level and first-mover texts).
 *
 * Option keys reach the screen as `LaunchOptions.extras` and are parsed by [TerritoryWarsOptions.fromExtras]:
 *  - `length`   "blitz" | "standard" (default) | "long"
 *  - `fortify`  "connected" (default) | "adjacent"
 *  - `handicap` "none" (default) | "you6" | "cpu6"   (vs Computers only)
 *  - `autoPlace` "true" | "false" (default; always on in Blitz)
 * Blitz with 5 or 6 seats cannot use a half map; it plays the full map with Blitz caps (see the design doc).
 */
object TerritoryWarsHubSpecs {
    const val GAME_ID = "territory-wars"
    const val KEY_LENGTH = "length"
    const val KEY_FORTIFY = "fortify"
    const val KEY_HANDICAP = "handicap"
    const val KEY_AUTO_PLACE = "autoPlace"

    /** Identity colours for `GameThemes.accents["territory-wars"]` (slate blue; contrast checked in the design doc). */
    const val ACCENT_LIGHT: Long = 0xFF3B4D8A
    const val ACCENT_DARK: Long = 0xFF7F95CC

    /** The menu glyph for `GameCatalog` (world map emoji, present on every Android 8+ device). */
    const val GLYPH: String = "🗺"

    private val length = HubOption.Choice(
        key = KEY_LENGTH, label = "War length", default = "standard",
        help = "Blitz plays half the map. Long plays to total conquest.",
        choices = listOf(
            HubChoice("blitz", "Blitz (about 15 min)"),
            HubChoice("standard", "Standard (about 45 min)"),
            HubChoice("long", "Long (about 90 min)")
        )
    )

    private val fortify = HubOption.Choice(
        key = KEY_FORTIFY, label = "Fortify reach", default = "connected",
        help = "Move armies along any chain of your territories, or only next door.",
        choices = listOf(
            HubChoice("connected", "Any connected chain"),
            HubChoice("adjacent", "Next door only")
        )
    )

    private val handicap = HubOption.Choice(
        key = KEY_HANDICAP, label = "Starting handicap", default = "none",
        help = "Extra armies at the start to even out a mixed table.",
        choices = listOf(
            HubChoice("none", "No handicap"),
            HubChoice("you6", "I start with 6 extra armies"),
            HubChoice("cpu6", "Computers start with 6 extra armies")
        )
    )

    private val autoPlace = HubOption.Toggle(
        key = KEY_AUTO_PLACE, label = "Auto-place armies", default = false,
        help = "Skips the opening placement round."
    )

    val territoryWars: GameHubSpec = GameHubSpec(
        gameId = GAME_ID, title = "Territory Wars",
        tagline = "Reinforce, roll the dice and conquer the map.",
        family = HubFamily.BOARD,
        audience = GameAudience.ADULTS, minAge = 10,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Conquer the map against 1 to 5 computers.",
                route = GAME_ID, start = HubStart.IMMEDIATE, defaultCpus = 2, minSeats = 2, maxSeats = 6,
                options = listOf(
                    length, fortify, handicap, autoPlace,
                    HubOptions.cpus(1, 5, 2), HubOptions.difficulty(), HubOptions.firstMover("The computer")
                )
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = GAME_ID, start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 6,
                options = listOf(
                    length, fortify, autoPlace,
                    HubOptions.humans(2, 6, 2), HubOptions.cpus(0, 4, 0), HubOptions.difficulty(),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(territoryWars)
}

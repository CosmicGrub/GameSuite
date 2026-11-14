package com.gamesuite.games.egggarden

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
 * The Game Hub spec of Egg Garden. NOT registered anywhere: the integrator adds [spec] to `GameHubSpecs.baseSpecs()`
 * (see docs/design/kids-games/egg-garden.md, section "Integration"). NOT networked: do not list "egg-garden" in
 * `GameHubSpecs.networkedGames` (the engine is same-screen only in this version).
 *
 * Every text below has a `hubKey(text)` string in res/values{,-es}/egg_garden_strings.xml, or already exists elsewhere (the
 * shared mode titles, the person and computer steppers, the first-mover choice, the difficulty words).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `style` (hidden: compete | coop, set by the mode), `length`
 * (short | full), `peekFor` (none | all | p1..p4), `freePlacing`, `goalBand` (seedling | sprout | bloom, co-op only).
 * The person and computer counts become the roster (`humans` / `cpus`).
 */
object EggGardenHubSpecs {
    /** The menu glyph for `GameCatalog` (the integrator adds it). */
    const val GLYPH: String = "🥚"

    /** Proposed identity colours for `GameThemes.accents["egg-garden"]` (light, dark). GameThemeTest has the last word on contrast. */
    const val ACCENT_LIGHT: Long = 0xFF2E6B1F
    const val ACCENT_DARK: Long = 0xFF9AD68A

    private fun style(id: String, label: String) = HubOption.Choice(
        key = "style", label = "Garden style", default = id,
        choices = listOf(HubChoice(id, label)),
        visibleWhen = { false }
    )

    private val length = HubOption.Choice(
        key = "length", label = "Garden size", default = "full",
        help = "How many tiles each gardener places.",
        choices = listOf(
            HubChoice("short", "Short garden: 8 tiles"),
            HubChoice("full", "Full garden: 12 tiles")
        )
    )

    private val peekFor = HubOption.Choice(
        key = "peekFor", label = "Peek turns for", default = "none",
        help = "Every third turn, draw two tiles and keep the one you like.",
        choices = listOf(
            HubChoice("none", "Nobody"),
            HubChoice("all", "All gardeners"),
            HubChoice("p1", "Gardener 1"),
            HubChoice("p2", "Gardener 2"),
            HubChoice("p3", "Gardener 3"),
            HubChoice("p4", "Gardener 4")
        )
    )

    private val freePlacing = HubOption.Toggle(
        key = "freePlacing", label = "Free placing", default = false,
        help = "Tiles may go anywhere next to your garden, matching or not."
    )

    private val goalBand = HubOption.Choice(
        key = "goalBand", label = "Team goals", default = "seedling",
        help = "How many eggs and how big a patch earn the stars.",
        choices = listOf(
            HubChoice("seedling", "Seedling goals"),
            HubChoice("sprout", "Sprout goals"),
            HubChoice("bloom", "Bloom goals")
        )
    )

    private fun gardenerLevel() = HubOptions.difficulty().copy(label = "Computer gardener level")

    val spec: GameHubSpec = GameHubSpec(
        gameId = "egg-garden", title = "Egg Garden",
        tagline = "Build a garden and collect the eggs.",
        family = HubFamily.BOARD,
        audience = GameAudience.KIDS, minAge = 5,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Grow gardens against 1 to 3 computer gardeners.",
                route = "egg-garden", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(
                    style("compete", "Everyone for themselves"), length, HubOptions.cpus(1, 3, 1), gardenerLevel(),
                    peekFor, freePlacing, HubOptions.firstMover("The computer")
                )
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                blurb = "Take turns on one screen. Computer gardeners can join in.",
                route = "egg-garden", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    style("compete", "Everyone for themselves"), length, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), gardenerLevel(),
                    peekFor, freePlacing, HubOptions.firstMover("Player 2", "Player 1")
                )
            ),
            HubMode(
                id = "coop", kind = HubModeKind.PASS_AND_PLAY, title = "Build one garden together",
                blurb = "Everyone adds tiles to one big garden. Reach for the stars!",
                route = "egg-garden", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    style("coop", "Building together"), goalBand, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), gardenerLevel(),
                    freePlacing
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)
}

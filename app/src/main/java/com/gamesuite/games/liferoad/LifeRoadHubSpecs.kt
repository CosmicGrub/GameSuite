package com.gamesuite.games.liferoad

import com.gamesuite.core.GameAudience
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
 * The Game Hub spec of Life Road, NOT registered anywhere: the integrator adds `LifeRoadHubSpecs.lifeRoad` to
 * `GameHubSpecs.baseSpecs()` (BOARD section, next to Ludo). It is NOT networked in the first release: do not list
 * "life-road" in `GameHubSpecs.networkedGames`. Every text below has a `hubKey(text)` string in
 * res/values{,-es}/life_road_strings.xml, or already exists in strings.xml (the shared mode titles, "The computer",
 * "Player 1" and "Player 2").
 *
 * Audience: FAMILY, minAge 8 (Standard rules). Junior rules (6+) are a CHOICE inside this spec for now; a KID profile
 * of age band 4-6 only sees specs with minAge <= 6, so showing Junior to them needs a second spec (open question OQ3).
 *
 * Option keys reach the screen as `LaunchOptions.extras`:
 *  - `rules` ("standard" | "junior"; Junior also sets `length` to "quick" through the choice's applies map)
 *  - `length` ("quick" | "standard" | "long")
 *  - `handicap` ("none" | "small" | "big") and `handicapFor` ("all" | "1".."6", 1-based seats; see [LifeRoadHandicap.parseSeats])
 *  - `catchUp` ("true" | "false", default true)
 * The person and CPU counts become the roster (`humans` / `cpus`).
 */
object LifeRoadHubSpecs {
    /** The identity colours the integrator adds to `GameThemes.accents["life-road"]` (contrast checked by the tests). */
    const val ACCENT_LIGHT: Long = 0xFF971A0C
    const val ACCENT_DARK: Long = 0xFFDE867C

    /** The menu glyph the integrator adds to `GameCatalog` for "life-road" (motorway U+1F6E3, no other game uses it). */
    const val GLYPH: String = "\uD83D\uDEE3\uFE0F"

    private val rules = HubOption.Choice(
        key = "rules", label = "Life Road rules", default = "standard",
        choices = listOf(
            HubChoice("standard", "Standard rules (ages 8+)", "Loans, cover and fresh starts."),
            HubChoice("junior", "Junior rules (ages 6+)", "No loans, no bad surprises. Quick road.", applies = mapOf("length" to "quick"))
        )
    )

    private val length = HubOption.Choice(
        key = "length", label = "Road length", default = "standard",
        choices = listOf(
            HubChoice("quick", "Quick road (about 15 min)"),
            HubChoice("standard", "Standard road (about 40 min)"),
            HubChoice("long", "Long road (about 60 min)")
        )
    )

    private val handicap = HubOption.Choice(
        key = "handicap", label = "Starting boost", default = "none",
        help = "Lucky coins and savings for newer players.",
        choices = listOf(
            HubChoice("none", "No boost"),
            HubChoice("small", "Small: 1 lucky coin"),
            HubChoice("big", "Big: 2 lucky coins and 50 coins")
        )
    )

    private val handicapFor = HubOption.Choice(
        key = "handicapFor", label = "Starting boost for", default = "all",
        choices = listOf(
            HubChoice("all", "Every player"),
            HubChoice("1", "Only seat 1"), HubChoice("2", "Only seat 2"), HubChoice("3", "Only seat 3"),
            HubChoice("4", "Only seat 4"), HubChoice("5", "Only seat 5"), HubChoice("6", "Only seat 6")
        ),
        visibleWhen = { it.string("handicap", "none") != "none" }
    )

    private val catchUp = HubOption.Toggle(
        key = "catchUp", label = "Catch-up boost", default = true,
        help = "Whoever is far behind spins twice, keeps the best."
    )

    /** The CPU level always matters once a CPU is seated; in Pass & Play it is hidden until a CPU is added. */
    private fun levelWhenCpus() = HubOptions.difficulty().copy(
        visibleWhen = { it.int(HubKeys.CPUS, 0) > 0 }
    )

    val lifeRoad: GameHubSpec = GameHubSpec(
        gameId = "life-road", title = "Life Road",
        tagline = "Spin, travel the road, retire with the most.",
        family = HubFamily.BOARD,
        audience = GameAudience.FAMILY, minAge = 8,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Travel the road against 1 to 5 CPU players.",
                route = "life-road", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 6,
                options = listOf(rules, length, handicap, handicapFor, catchUp, HubOptions.cpus(1, 5, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Share the device. CPUs can fill empty seats.",
                route = "life-road", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 6,
                options = listOf(
                    rules, length, handicap, handicapFor, catchUp, HubOptions.humans(2, 6, 2), HubOptions.cpus(0, 4, 0),
                    levelWhenCpus(), HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(lifeRoad)
}

package com.gamesuite.games.flickrink

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
 * The Game Hub spec of Flick Rink. NOT registered anywhere: the integrator adds [spec] to `GameHubSpecs.baseSpecs()`
 * (see docs/design/kids-games/flick-rink.md, section "Integration"). NOT networked: do not list "flick-rink" in
 * `GameHubSpecs.networkedGames` (the engine is same-screen only in this version).
 *
 * Every text below has a `hubKey(text)` string in res/values{,-es}/flick_rink_strings.xml, or already exists elsewhere (the
 * shared mode titles, the person and computer steppers, the first-mover choice, the difficulty words).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `style` (hidden: turns | coop | solo, set by the mode), `game`
 * (fishrun | bullseye), `band` (little | medium | big), `length` (quick | standard | long), `assistFor` (none | all | p1..p4),
 * `assist` (short | medium | long), `relaxed`, `dragAnywhere`, `switchAiming`, `budget` (plenty | normal | tight, co-op only),
 * `soloRink` (1..14, solo only). The person and computer counts become the roster (`humans` / `cpus`).
 */
object FlickRinkHubSpecs {
    /** The menu glyph for `GameCatalog` (the integrator adds it). */
    const val GLYPH: String = "🐧"

    /** Proposed identity colours for `GameThemes.accents["flick-rink"]` (light, dark). GameThemeTest has the last word on contrast. */
    const val ACCENT_LIGHT: Long = 0xFF00607A
    const val ACCENT_DARK: Long = 0xFF7CD4EC

    private fun style(id: String, label: String) = HubOption.Choice(
        key = "style", label = "Play style", default = id,
        choices = listOf(HubChoice(id, label)),
        visibleWhen = { false }
    )

    private val game = HubOption.Choice(
        key = "game", label = "Game", default = "fishrun",
        choices = listOf(
            HubChoice("fishrun", "Fish run", "Collect fish and bring them home."),
            HubChoice("bullseye", "Bullseye", "Flick discs onto the target.")
        )
    )

    private val band = HubOption.Choice(
        key = "band", label = "Rink size", default = "little",
        help = "How tricky the rinks are.",
        choices = listOf(
            HubChoice("little", "Little rinks", "Few obstacles and wide, friendly spaces."),
            HubChoice("medium", "Medium rinks"),
            HubChoice("big", "Big rinks", "Gates, sliding walls and puddles for practised flickers.")
        )
    )

    private val length = HubOption.Choice(
        key = "length", label = "Match length", default = "standard",
        choices = listOf(
            HubChoice("quick", "Quick: 2 rinks"),
            HubChoice("standard", "Standard: 3 rinks"),
            HubChoice("long", "Long: 5 rinks")
        )
    )

    private val assistFor = HubOption.Choice(
        key = "assistFor", label = "Aim helper for", default = "none",
        help = "Give some players a guide line so different ages can play together.",
        choices = listOf(
            HubChoice("none", "No one"),
            HubChoice("all", "Everyone"),
            HubChoice("p1", "Player 1"),
            HubChoice("p2", "Player 2"),
            HubChoice("p3", "Player 3"),
            HubChoice("p4", "Player 4")
        )
    )

    private val assistForSolo = HubOption.Choice(
        key = "assistFor", label = "Aim helper for", default = "none",
        help = "Give some players a guide line so different ages can play together.",
        choices = listOf(HubChoice("none", "No one"), HubChoice("all", "Everyone"))
    )

    private val assist = HubOption.Choice(
        key = "assist", label = "Guide line length", default = "medium",
        choices = listOf(HubChoice("short", "Short"), HubChoice("medium", "Medium"), HubChoice("long", "Long")),
        visibleWhen = { it.string("assistFor", "none") != "none" }
    )

    private val relaxed = HubOption.Toggle(
        key = "relaxed", label = "Relaxed flicking", default = false,
        help = "Bigger igloo and fish, gentler bounces and no flick limit."
    )

    private val dragAnywhere = HubOption.Toggle(
        key = "dragAnywhere", label = "Drag anywhere", default = true,
        help = "Pull back from anywhere on the ice, not only from your pal."
    )

    private val switchAiming = HubOption.Toggle(
        key = "switchAiming", label = "Switch aiming", default = false,
        help = "One tap locks the aim, one locks the power, one flicks."
    )

    private val budget = HubOption.Choice(
        key = "budget", label = "Team flicks", default = "normal",
        help = "How many flicks the team shares on each rink.",
        choices = listOf(HubChoice("plenty", "Plenty"), HubChoice("normal", "Normal"), HubChoice("tight", "Tight"))
    )

    private val soloRink = HubOption.Stepper(
        key = "soloRink", label = "Rink number", min = 1, max = 14, default = 1,
        help = "Pick a rink from 1 to 14."
    )

    private fun palLevel() = HubOptions.difficulty().copy(label = "Computer pal level")

    val spec: GameHubSpec = GameHubSpec(
        gameId = "flick-rink", title = "Flick Rink",
        tagline = "Flick your puck pal across the ice for fish.",
        family = HubFamily.ARCADE_PUZZLE,
        audience = GameAudience.KIDS, minAge = 5,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Race 1 to 3 computer pals to bring home the fish.",
                route = "flick-rink", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(
                    style("turns", "Taking turns"), game, band, length, HubOptions.cpus(1, 3, 1), palLevel(),
                    assistFor, assist, relaxed, dragAnywhere, switchAiming, HubOptions.firstMover("The computer")
                )
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                blurb = "Take turns on one screen. Computer pals can join in.",
                route = "flick-rink", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    style("turns", "Taking turns"), game, band, length, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), palLevel(),
                    assistFor, assist, relaxed, dragAnywhere, switchAiming, HubOptions.firstMover("Player 2", "Player 1")
                )
            ),
            HubMode(
                id = "coop", kind = HubModeKind.PASS_AND_PLAY, title = "Co-op Relay",
                blurb = "Work as one team to bring home every fish.",
                route = "flick-rink", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(
                    style("coop", "Playing together"), game, band, length, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), palLevel(),
                    budget, assistFor, assist, relaxed, dragAnywhere, switchAiming
                )
            ),
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Solo Practice",
                blurb = "Flick at your own speed and earn stars.",
                route = "flick-rink", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(
                    style("solo", "Practising"), game, soloRink, assistForSolo, assist, relaxed, dragAnywhere, switchAiming
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)
}

package com.gamesuite.games.botbastion

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Bot Bastion, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (see integration-notes.md). It is NOT networked: never list "bot-bastion" in
 * `GameHubSpecs.networkedGames`. Every text has a `hubKey(text)` string in res/values{,-es}
 * (bot_bastion_hub_strings.xml, or an existing shared one such as "Play").
 *
 * Option keys reach the screen as `LaunchOptions.extras`:
 *  - `tier`: a [Tier] id (cadet, captain, hero, insanity, endless)
 *  - `players`: 1..4 (the screen caps it at 2 on a phone)
 *  - `map`: optional map id; without it the screen opens its own map picker (which knows the unlocks)
 */
object BotBastionHubSpecs {
    const val GAME_ID = "bot-bastion"

    /** Identity colours for `GameThemes.accents["bot-bastion"]` (contrast-checked by the tests). */
    const val ACCENT_LIGHT: Long = 0xFF8A3A00
    const val ACCENT_DARK: Long = 0xFFFFB066

    /** The menu glyph for `GameCatalog`. */
    const val GLYPH: String = "🛸"

    private val tier = HubOption.Choice(
        key = "tier", label = "Skill level", default = "captain",
        help = "Cadet is gentle; Endless never stops.",
        choices = listOf(
            HubChoice("cadet", "Cadet", "Softer ships and extra Scrap."),
            HubChoice("captain", "Captain", "The standard challenge."),
            HubChoice("hero", "Galactic Hero", "Tough waves and swarm attacks."),
            HubChoice("insanity", "Insanity", "For experts: fast, strong and pricey."),
            HubChoice("endless", "Endless", "Hold on as long as you can.")
        )
    )

    private val players = HubOption.Stepper(
        key = "players", label = "Crew size", min = 1, max = BbRules.MAX_PLAYERS, default = 1,
        help = "People sharing this device."
    )

    val botBastion: GameHubSpec = GameHubSpec(
        gameId = GAME_ID, title = "Bot Bastion",
        tagline = "Build bots and defend the Core.",
        family = HubFamily.ARCADE_PUZZLE,
        audience = GameAudience.FAMILY, minAge = 10,
        modes = listOf(
            HubMode(
                id = "play", kind = HubModeKind.SOLO, title = "Play",
                blurb = "Defend the Core. Friends can fly pilot ships.",
                route = GAME_ID, start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(tier, players)
            )
        )
    )

    val all: List<GameHubSpec> = listOf(botBastion)
}

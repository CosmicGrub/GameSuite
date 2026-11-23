package com.gamesuite.games.towerdefence

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Tower Defence. It replaces the plain `solo("tower-defence", ...)` entry in
 * `GameHubSpecs.baseSpecs()` (one line, see integration-notes.md). The mode id stays "solo" and the
 * route stays "tower-defence", so existing hub preferences keep working. Option keys reach the
 * screen as `LaunchOptions.extras`:
 *  - `map`: castle-pass (default), river-bend, twin-gates, switchback, spiral, gauntlet
 *  - `theme`: castle (default), space, toy (a skin only: it never changes balance or stats)
 *  - `gentle`: "true" for the Gentle tier
 * Difficulty (easy, medium, hard) still comes from the player's default-difficulty setting.
 */
object TowerDefenceHubSpecs {
    const val GAME_ID = "tower-defence"

    private val map = HubOption.Choice(
        key = "map", label = "Map", default = "castle-pass",
        help = "Castle Siege maps let you build anywhere. Classic maps keep their fixed tower spots.",
        choices = listOf(
            HubChoice("castle-pass", "Castle Pass", "An open meadow with a pond and one entrance."),
            HubChoice("river-bend", "River Bend", "A river with a bridge and a muddy ford. Two entrances."),
            HubChoice("twin-gates", "Twin Gates", "A long wall with two gaps. Two entrances."),
            HubChoice("switchback", "Classic Switchback", "The original fixed-lane map."),
            HubChoice("spiral", "Classic Spiral Keep", "The original spiral map."),
            HubChoice("gauntlet", "Classic Zigzag Gauntlet", "The original zigzag map.")
        )
    )

    private val theme = HubOption.Choice(
        key = "theme", label = "Theme", default = "castle",
        help = "Only changes how it looks, never how it plays.",
        choices = listOf(
            HubChoice("castle", "Castle Siege", "Medieval pass, archers and mages."),
            HubChoice("space", "Space Station", "Drone attack on an orbital station."),
            HubChoice("toy", "Toy Room", "Soft cartoon toys in a child room.")
        )
    )

    private val gentle = HubOption.Toggle(
        key = "gentle", label = "Gentle mode", default = false,
        help = "Softer waves, extra gold and a sturdier castle."
    )

    val towerDefence: GameHubSpec = GameHubSpec(
        gameId = GAME_ID, title = "Tower Defence",
        tagline = "Build towers and stop the waves.",
        family = HubFamily.ARCADE_PUZZLE, resumable = false,
        modes = listOf(
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Play", blurb = "Play on your own.",
                route = GAME_ID, start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                options = listOf(map, theme, gentle)
            )
        )
    )
}

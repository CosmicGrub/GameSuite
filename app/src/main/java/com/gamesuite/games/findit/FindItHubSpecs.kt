package com.gamesuite.games.findit

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
 * The Game Hub spec of Find It Frenzy. NOT registered anywhere: the coordinator adds [spec] to
 * `GameHubSpecs.baseSpecs()` (ARCADE_PUZZLE section) at integration (see docs/design/kids-games/find-it.md, SCAFFOLD).
 * NOT networked: do not list "find-it" in `GameHubSpecs.networkedGames` until the host-authoritative Nearby mode exists.
 *
 * Every text has a `hubKey(text)` string in res/values{,-es}/find_it_strings.xml, except texts that already exist
 * elsewhere ("Nobody, all equal", "Player 1".."Player 2", the shared computer-level words).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `cardSize` (tiny | little | standard), `length`
 * (quick | standard | long), `handicap` (none | all | p1..p4), `hint` (off | slow | fast, Group Hunt only),
 * `relaxed`, `oneSwitch`. Helpers are the computer seats (`cpus`); `difficulty` is their level.
 */
object FindItHubSpecs {
    /** Suggested menu glyph (not used by any other game at the time of writing; the coordinator re-checks). */
    const val GLYPH: String = "\uD83D\uDC40"

    private val cardSize = HubOption.Choice(
        key = "cardSize", label = "Picture count", default = "little",
        help = "Fewer pictures on a card make the match easier to spot.",
        choices = listOf(
            HubChoice("tiny", "Tiny cards", "4 pictures on each card: the easiest match."),
            HubChoice("little", "Little cards", "6 pictures on each card: great for ages 4 to 6."),
            HubChoice("standard", "Big cards", "8 pictures on each card: a faster game for practised eyes.")
        )
    )

    private val length = HubOption.Choice(
        key = "length", label = "Game length", default = "standard",
        help = "About 3, 4 or 6 minutes.",
        choices = listOf(
            HubChoice("quick", "Quick round", "About 3 minutes."),
            HubChoice("standard", "Normal round", "About 4 minutes."),
            HubChoice("long", "Long round", "About 6 minutes.")
        )
    )

    private val handicap = HubOption.Choice(
        key = "handicap", label = "Hint glow for", default = "none",
        help = "A gentle glow narrows the search for one player, so different ages can play together.",
        choices = listOf(
            HubChoice("none", "Nobody, all equal"),
            HubChoice("all", "Every player"),
            HubChoice("p1", "Player 1"),
            HubChoice("p2", "Player 2"),
            HubChoice("p3", "Player 3"),
            HubChoice("p4", "Player 4")
        )
    )

    private val soloHint = HubOption.Choice(
        key = "handicap", label = "Hint glow", default = "none",
        help = "A glow narrows the search while you play on your own.",
        choices = listOf(
            HubChoice("none", "No hints"),
            HubChoice("all", "Hints on")
        )
    )

    private val relaxed = HubOption.Toggle(
        key = "relaxed", label = "Relaxed play", default = false,
        help = "No waiting after a wrong tap, no clock, and hints come sooner."
    )

    private val oneSwitch = HubOption.Toggle(
        key = "oneSwitch", label = "One-switch play", default = false,
        help = "Use a single button: a highlight scans the pictures and you press to choose."
    )

    private val huntHint = HubOption.Choice(
        key = "hint", label = "Hint speed", default = "slow",
        help = "A glow points to a picture you have not found yet.",
        choices = listOf(
            HubChoice("off", "No hints"),
            HubChoice("slow", "Slow hints"),
            HubChoice("fast", "Quick hints")
        )
    )

    private fun helperLevel() = HubOptions.difficulty().copy(label = "Helper level")

    private fun helperCount(min: Int, max: Int, default: Int) =
        HubOptions.cpus(min, max, default).copy(label = "Helpers", help = "Friendly helpers play too, so a small group always has a race.")

    val spec: GameHubSpec = GameHubSpec(
        gameId = "find-it", title = "Find It Frenzy",
        tagline = "Spot the matching picture before anyone else.",
        family = HubFamily.ARCADE_PUZZLE,
        audience = GameAudience.KIDS, minAge = 4,
        modes = listOf(
            HubMode(
                id = "race-cpu", kind = HubModeKind.VS_CPU, title = "Race the helpers",
                blurb = "You and 1 to 3 friendly helpers race to find the picture on both cards.",
                route = "find-it", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(cardSize, length, helperCount(1, 3, 1), helperLevel(), handicap, relaxed, oneSwitch)
            ),
            HubMode(
                id = "race", kind = HubModeKind.PASS_AND_PLAY, title = "Match Race",
                blurb = "Everyone shares the screen. Tap the picture that is on your card and the middle card.",
                route = "find-it", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(cardSize, length, HubOptions.humans(2, 4, 2), helperCount(0, 2, 0), helperLevel(), handicap, relaxed, oneSwitch)
            ),
            HubMode(
                id = "hunt", kind = HubModeKind.PASS_AND_PLAY, title = "Group Hunt",
                blurb = "Search together and find every picture on the list before the sun sets.",
                route = "find-it", start = HubStart.READY_SCREEN, minSeats = 1, maxSeats = 4,
                options = listOf(
                    cardSize, length, HubOptions.humans(1, 4, 2), helperCount(0, 1, 0),
                    helperLevel().copy(visibleWhen = { it.int("cpus", 0) > 0 }), huntHint, relaxed, oneSwitch
                )
            ),
            HubMode(
                id = "dash", kind = HubModeKind.SOLO, title = "Solo Dash",
                blurb = "Match as many cards as you can before the clock runs out.",
                route = "find-it", start = HubStart.IMMEDIATE,
                options = listOf(cardSize, soloHint, relaxed, oneSwitch)
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)
}

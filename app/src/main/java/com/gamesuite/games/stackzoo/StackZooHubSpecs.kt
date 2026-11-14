package com.gamesuite.games.stackzoo

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
 * The Game Hub spec of Stack Zoo. NOT registered anywhere: the coordinator adds [spec] to `GameHubSpecs.baseSpecs()`
 * (ARCADE_PUZZLE section) at integration (see docs/design/kids-games/stack-zoo.md, SCAFFOLD). NOT networked: do not
 * list "stack-zoo" in `GameHubSpecs.networkedGames` until the lockstep Nearby mode exists.
 *
 * Every text has a `hubKey(text)` string in res/values{,-es}/stack_zoo_strings.xml, except texts that already exist
 * elsewhere ("Nobody, all equal", "Take Turns", the shared computer-level words).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `goal` (little | medium | tall, Co-op Tower), `length`
 * (quick | standard | long, Take Turns), `assist` (none | all | p1..p4), `relaxed`, `oneSwitch`. Helpers are the
 * computer seats (`cpus`); `difficulty` is their level.
 */
object StackZooHubSpecs {
    /** Suggested menu glyph (giraffe, Android 8+; not used by any other game at the time of writing). */
    const val GLYPH: String = "\uD83E\uDD92"

    private val goal = HubOption.Choice(
        key = "goal", label = "Tower goal", default = "medium",
        help = "How high the flag is and how many animals the team has.",
        choices = listOf(
            HubChoice("little", "Little tower", "A low flag and 12 animals: great for the youngest."),
            HubChoice("medium", "Medium tower", "A medium flag and 16 animals: a good mix for most families."),
            HubChoice("tall", "Tall tower", "A high flag and 20 animals for steady hands.")
        )
    )

    private val length = HubOption.Choice(
        key = "length", label = "Animals each", default = "standard",
        help = "How many animals every player has to place.",
        choices = listOf(
            HubChoice("quick", "Few animals", "4 animals each: about 3 minutes."),
            HubChoice("standard", "Some animals", "5 animals each: about 5 minutes."),
            HubChoice("long", "Many animals", "7 animals each: about 8 minutes.")
        )
    )

    private val assist = HubOption.Choice(
        key = "assist", label = "Bigger snap help for", default = "none",
        help = "Gently guides one player's animal toward a steadier spot, so different ages can play together.",
        choices = listOf(
            HubChoice("none", "Nobody, all equal"),
            HubChoice("all", "All players"),
            HubChoice("p1", "First player"),
            HubChoice("p2", "Second player"),
            HubChoice("p3", "Third player"),
            HubChoice("p4", "Fourth player")
        )
    )

    private val soloAssist = HubOption.Choice(
        key = "assist", label = "Snap help", default = "none",
        help = "Gently guides your animal toward a steadier spot.",
        choices = listOf(
            HubChoice("none", "No help"),
            HubChoice("all", "Help on")
        )
    )

    private val relaxed = HubOption.Toggle(
        key = "relaxed", label = "Relaxed wobble", default = false,
        help = "A steadier log, grippier animals, and animals that fall float back for free."
    )

    private val oneSwitch = HubOption.Toggle(
        key = "oneSwitch", label = "One-switch placing", default = false,
        help = "Use a single button: the animal slides and turns by itself and you press to stop it."
    )

    private fun helperLevel() = HubOptions.difficulty().copy(label = "Zoo helper level")

    private fun helperCount(min: Int, max: Int, default: Int) =
        HubOptions.cpus(min, max, default).copy(label = "Zoo helpers", help = "Friendly helpers take turns too, so a small group always has a full table.")

    val spec: GameHubSpec = GameHubSpec(
        gameId = "stack-zoo", title = "Stack Zoo",
        tagline = "Balance animals on the wobbly log.",
        family = HubFamily.ARCADE_PUZZLE,
        audience = GameAudience.KIDS, minAge = 4,
        modes = listOf(
            HubMode(
                id = "coop", kind = HubModeKind.PASS_AND_PLAY, title = "Co-op Tower",
                blurb = "Build one tall tower together. Everyone picks an animal and places it.",
                route = "stack-zoo", start = HubStart.READY_SCREEN, minSeats = 1, maxSeats = 4,
                options = listOf(
                    goal, HubOptions.humans(1, 4, 2), helperCount(0, 2, 0),
                    helperLevel().copy(visibleWhen = { it.int("cpus", 0) > 0 }), assist, relaxed, oneSwitch
                )
            ),
            HubMode(
                id = "turns-cpu", kind = HubModeKind.VS_CPU, title = "Take turns with helpers",
                blurb = "You and 1 to 3 friendly helper animals take turns. If some fall, take them back.",
                route = "stack-zoo", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                options = listOf(length, helperCount(1, 3, 1), helperLevel(), assist, relaxed, oneSwitch)
            ),
            HubMode(
                id = "turns", kind = HubModeKind.PASS_AND_PLAY, title = "Take Turns",
                blurb = "Place animals one by one. If some fall, you take them back. First to place all wins.",
                route = "stack-zoo", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                options = listOf(length, HubOptions.humans(2, 4, 2), helperCount(0, 2, 0), helperLevel(), assist, relaxed, oneSwitch)
            ),
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Solo Balance",
                blurb = "Stack as many animals as you can on the wobbly log.",
                route = "stack-zoo", start = HubStart.IMMEDIATE,
                options = listOf(soloAssist, relaxed, oneSwitch)
            )
        )
    )

    val all: List<GameHubSpec> = listOf(spec)
}

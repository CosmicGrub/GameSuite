package com.gamesuite.games.drawguess

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
 * The Game Hub spec of Draw and Guess, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (see integration-notes.md). Same screen only, no computers, not networked
 * (do not list "draw-guess" in `GameHubSpecs.networkedGames`).
 *
 * Every text has a `hubKey(text)` string in res/values{,-es}/draw_guess_hub_strings.xml (or already exists
 * elsewhere in the catalog: "Same screen", "People playing" and its help).
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `timer` (0 | 30 | 60 | 90 seconds), `guess` (pick | type),
 * `rounds` ("1".."4"). `humans` and `teams` are standard keys: they become the roster and each player's team id.
 */
object DrawGuessHubSpecs {
    val drawGuess: GameHubSpec = run {
        val teams = HubOption.Toggle(
            key = HubKeys.TEAMS, label = "Two teams", default = false,
            help = "The drawer's team does the guessing. Needs an even table of four or more.",
            visibleWhen = { it.int(HubKeys.HUMANS, 3).let { n -> n >= 4 && n % 2 == 0 } }
        )
        val rounds = HubOption.Stepper(
            key = "rounds", label = "Rounds each", min = 1, max = 4, default = 1,
            help = "Everyone draws once in every round."
        )
        val timer = HubOption.Choice(
            key = "timer", label = "Drawing time", default = "60",
            help = "How long the drawer has. The clock stops while someone is guessing.",
            choices = listOf(
                HubChoice("30", "30 seconds"),
                HubChoice("60", "60 seconds"),
                HubChoice("90", "90 seconds"),
                HubChoice("0", "No timer", "Take your time. Faster guesses still score a little more.")
            )
        )
        val guess = HubOption.Choice(
            key = "guess", label = "How to guess", default = "pick",
            help = "Players guess by picking a picture card or by typing the word.",
            choices = listOf(
                HubChoice("pick", "Pick a card", "Six picture cards and no typing. Best for younger players."),
                HubChoice("type", "Type the word", "Type your guess. Best for readers and writers.")
            )
        )
        GameHubSpec(
            gameId = "draw-guess", title = "Draw and Guess",
            tagline = "Draw a secret word, pass the device and guess. No drawing skills needed.",
            family = HubFamily.WORD,
            modes = listOf(
                HubMode(
                    id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                    blurb = "Two to eight people take turns drawing a secret word while the others guess. Pass the device around the table.",
                    route = "draw-guess", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 8,
                    options = listOf(HubOptions.humans(2, 8, 3), teams, rounds, timer, guess)
                )
            ),
            audience = GameAudience.FAMILY,
            minAge = 6
        )
    }

    val all: List<GameHubSpec> = listOf(drawGuess)

    /** First-run tips (paste into `GameTips.tips`). The English literals equal `tip_draw_guess_1..3` in values/draw_guess_coach_strings.xml. */
    val tips: Map<String, List<String>> = mapOf(
        "draw-guess" to listOf(
            "The drawer picks one of three pictures, then holds the button to see the word. Everyone else looks away.",
            "Think you know it? Tap your name to buzz in. The clock stops while you guess, and a wrong guess sits you out.",
            "Stick figures are fine. Quick, clear drawings earn more points than perfect ones."
        )
    )

    /** Proposed identity colours (light, dark) for `GameThemes.accents`. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "draw-guess" to (0xFFB45309L to 0xFFFCD34DL)
    )
}

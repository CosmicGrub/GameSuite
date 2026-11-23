package com.gamesuite.games.actitout

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
 * The Game Hub spec of Act It Out, kept next to the game until the integrator adds it to
 * `GameHubSpecs.baseSpecs()` (see integration-notes.md). It is a SAME-DEVICE party game: do NOT list "act-it-out"
 * in `GameHubSpecs.networkedGames`.
 *
 * Every text below has a `hubKey(text)` string in res/values{,-es}/act_it_out_hub_strings.xml (or already exists
 * elsewhere); `ActItOutHubSpecsTest` proves it.
 *
 * Option keys reach the screen as `LaunchOptions.extras`: `format` ("teams" | "ffa"), `time` ("30" | "60" | "90"),
 * `rounds` ("1".."5"), `band` ("little" | "everyone" | "grownups"), `handicap` (the little kids bonus switches are
 * offered in the setup), `buzzer`, `hold` (hold to start a turn; false = tap). The seats are the standard `humans`
 * stepper (3 to 12 people, no computer players).
 */
object ActItOutHubSpecs {
    val actItOut: GameHubSpec = run {
        val format = HubOption.Choice(
            key = "format", label = "How to group the players", default = "teams",
            help = "Two fair teams take turns, or everybody acts in turn and plays alone.",
            choices = listOf(
                HubChoice("teams", "Two teams", "Players are split into two teams of nearly equal size. You can change who is on which team before the game."),
                HubChoice("ffa", "Everyone for themselves", "Each player acts in turn and plays for their own score.")
            )
        )
        val time = HubOption.Choice(
            key = "time", label = "Seconds per turn", default = "60",
            help = "How long each actor has to get their team guessing.",
            choices = listOf(HubChoice("30", "30 seconds"), HubChoice("60", "60 seconds"), HubChoice("90", "90 seconds"))
        )
        val rounds = HubOption.Stepper(
            key = "rounds", label = "Rounds to play", min = ActItOutRules.MIN_ROUNDS, max = ActItOutRules.MAX_ROUNDS,
            default = ActItOutRules.DEFAULT_ROUNDS,
            help = "In every round each team, or each player when playing alone, takes one turn."
        )
        val band = HubOption.Choice(
            key = "band", label = "Card level to start with", default = "everyone",
            help = "You can pick a different level for each team in the setup.",
            choices = listOf(
                HubChoice("little", "Little kids", "Easy cards that each have their own picture, so pre-readers can play."),
                HubChoice("everyone", "Everyone", "Easy and medium cards."),
                HubChoice("grownups", "Grown-ups", "Medium and hard cards, with no pictures to lean on.")
            )
        )
        val handicap = HubOption.Toggle(
            key = "handicap", label = "Little kids bonus", default = false,
            help = "Lets you give a team with little kids one extra point for every correct card. You pick the team in the setup."
        )
        val buzzer = HubOption.Toggle(
            key = "buzzer", label = "Buzzer in the last 5 seconds", default = true,
            help = "A tick each second and a buzz at the end, with a little vibration."
        )
        val hold = HubOption.Toggle(
            key = "hold", label = "Hold to start a turn", default = true,
            help = "The actor holds a big pad to start, so nobody sees a card by accident. Switch off to start with a tap."
        )
        GameHubSpec(
            gameId = "act-it-out", title = "Act It Out",
            tagline = "Charades for the whole family: act out the card, your team guesses.",
            family = HubFamily.ARCADE_PUZZLE, resumable = true,
            audience = GameAudience.FAMILY, minAge = 4,
            modes = listOf(
                HubMode(
                    id = "party", kind = HubModeKind.PASS_AND_PLAY, title = "Family party",
                    blurb = "One device deals the cards and keeps the time. Pass it to each actor in turn.",
                    route = "act-it-out", start = HubStart.READY_SCREEN, minSeats = ActItOutRules.MIN_PLAYERS, maxSeats = ActItOutRules.MAX_PLAYERS,
                    options = listOf(HubOptions.humans(ActItOutRules.MIN_PLAYERS, ActItOutRules.MAX_PLAYERS, 6), format, time, rounds, band, handicap, buzzer, hold)
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(actItOut)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_act_it_out_1..3` strings
     * in values/; values-es/ has the Spanish.
     */
    val tips: Map<String, List<String>> = mapOf(
        "act-it-out" to listOf(
            "The actor never speaks or points at words. Everyone else shouts out guesses!",
            "Little kids can play from the picture. Choose Little kids for their team and turn on the bonus point.",
            "Hold the big pad to start, then tap Correct or Skip. You get three skips in each turn."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; the spec test checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "act-it-out" to (0xFF8E2B6DL to 0xFFF08CCBL)
    )
}

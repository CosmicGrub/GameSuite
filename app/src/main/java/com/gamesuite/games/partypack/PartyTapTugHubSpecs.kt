package com.gamesuite.games.partypack

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub specs of the same-screen party games Tap Race and Tug of War, kept next to the games until the
 * integrator adds them to `GameHubSpecs.baseSpecs()` (the entries there are these, verbatim). They are NOT
 * networked in this version: do not list either id in `GameHubSpecs.networkedGames`. Both engines are same-screen
 * only (no host, no message type), so a Nearby or Online mode would have nothing to run.
 *
 * Every text below has a `hubKey(text)` string in res/values{,-es}/party_tap_tug_hub_strings.xml (or already exists
 * elsewhere); `PartyTapTugHubSpecsTest` proves it. The "Same screen" title is the one every party-pack game shares:
 * if a sibling game's strings file also defines `hub_same_screen`, keep ONE definition.
 *
 * Option keys reach the screens as `LaunchOptions.extras`:
 *  - Tap Race: `variant` (sprint | rhythm | brake), `rounds` ("1" | "3" | "5"), `relaxed`, `hold`, `rotate`
 *  - Tug of War: `layout` (teams | lone | ffa), `rounds`, `relaxed`, `hold`, `rotate`
 * The person and computer counts become the roster (`humans` / `cpus`); computers fill every empty zone.
 */
object PartyTapTugHubSpecs {
    private val rounds = HubOption.Choice(
        key = "rounds", label = "Rounds", default = "3",
        help = "The first to win more than half of the rounds takes the match.",
        choices = listOf(HubChoice("1", "1 round"), HubChoice("3", "3 rounds"), HubChoice("5", "5 rounds"))
    )

    val tapRace: GameHubSpec = run {
        val variant = HubOption.Choice(
            key = "variant", label = "Race type", default = "sprint",
            help = "Three ways to race on the same screen.",
            choices = listOf(
                HubChoice("sprint", "Sprint", "Tap as fast as you can. First to fill the track wins."),
                HubChoice("rhythm", "Rhythm", "Tap only on the beat. Early, late and extra taps set you back."),
                HubChoice("brake", "Mash and Brake", "Tap fast, but never while the light is red.")
            )
        )
        val relaxed = HubOption.Toggle(
            key = "relaxed", label = "Relaxed tapping", default = false,
            help = "Fewer taps to win, wider beat windows and longer red-light warnings, for players who cannot tap fast."
        )
        val hold = HubOption.Toggle(
            key = "hold", label = "Hold to tap", default = false,
            help = "Hold your pad and it taps for you at a steady pace: a one-button way to play. Not used by Rhythm."
        )
        val rotate = HubOption.Toggle(
            key = "rotate", label = "Turn the far lanes around", default = true,
            help = "Players sitting opposite each other read their own lane the right way up. Switch off for one person holding the device."
        )
        GameHubSpec(
            gameId = "tap-race", title = "Tap Race",
            tagline = "Everyone taps at once on one screen. First to the finish wins the round.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "Race the computer",
                    blurb = "Race 1 to 3 computer racers. Each one gets a lane of its own.",
                    route = "tap-race", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(variant, rounds, HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), relaxed, hold, rotate)
                ),
                HubMode(
                    id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                    blurb = "Two to four people race at the same time, each in their own lane. Computers can fill empty lanes.",
                    route = "tap-race", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(variant, rounds, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(), relaxed, hold, rotate)
                )
            )
        )
    }

    val tugOfWar: GameHubSpec = run {
        val layout = HubOption.Choice(
            key = "layout", label = "Team layout", default = "teams",
            help = "How the players pull together. With two players it is always one against one.",
            choices = listOf(
                HubChoice("teams", "Even teams", "Two even teams: 2 against 2 with four players, 1 against 2 with three."),
                HubChoice("lone", "One against the rest", "One player pulls against everyone else, up to 1 against 3."),
                HubChoice("ffa", "Free for all", "Everybody pulls for themselves toward their own corner.")
            )
        )
        val relaxed = HubOption.Toggle(
            key = "relaxed", label = "Relaxed pulling", default = false,
            help = "Mashing tires you less, heaves charge faster and rounds last longer, for players who cannot pull fast."
        )
        val hold = HubOption.Toggle(
            key = "hold", label = "Hold to pull", default = false,
            help = "Hold your pad and it pulls for you at a steady pace: a one-button way to play."
        )
        val rotate = HubOption.Toggle(
            key = "rotate", label = "Turn the far side around", default = true,
            help = "Players sitting opposite each other read their own pad the right way up. Switch off for one person holding the device."
        )
        GameHubSpec(
            gameId = "tug-of-war", title = "Tug of War",
            tagline = "Pull the marker over your line. Mashing wears you out, so pull smart.",
            family = HubFamily.ARCADE_PUZZLE,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "Pull against the computer",
                    blurb = "Pull against 1 to 3 computer players, alone or with computer team-mates.",
                    route = "tug-of-war", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(layout, rounds, HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), relaxed, hold, rotate)
                ),
                HubMode(
                    id = "same", kind = HubModeKind.PASS_AND_PLAY, title = "Same screen",
                    blurb = "Two to four people pull at the same time, each on their own pad. Computers can fill empty pads.",
                    route = "tug-of-war", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(layout, rounds, HubOptions.humans(2, 4, 2), HubOptions.cpus(0, 2, 0), HubOptions.difficulty(), relaxed, hold, rotate)
                )
            )
        )
    }

    val all: List<GameHubSpec> = listOf(tapRace, tugOfWar)

    /**
     * The first-run tips (paste into `GameTips.tips`). The English literals equal the `tip_tap_race_1..3` /
     * `tip_tug_of_war_1..3` strings in values/party_tap_tug_tips_strings.xml; values-es/ has the Spanish. GameTipsTest
     * reads only polish_strings.xml, so when you paste these move the eight-line files' strings there.
     */
    val tips: Map<String, List<String>> = mapOf(
        "tap-race" to listOf(
            "Put a finger of each hand on your lane's pad and tap as fast as you can once the countdown ends.",
            "In Rhythm, tap only on the beat: early, late and extra taps cost you ground.",
            "In Mash and Brake, stop tapping the moment the light turns amber. A tap on red sets you back."
        ),
        "tug-of-war" to listOf(
            "Tap your pad to pull the marker toward your line. Every pull costs stamina, and mashing runs you dry.",
            "Hold your finger down to rest and charge up, then let go for a heave that pulls up to four times harder.",
            "Can't hold or tap fast? Turn on Relaxed pulling or Hold to pull in the setup."
        )
    )

    /** The proposed identity colours (light, dark) for `GameThemes.accents`; `PartyTapTugHubSpecsTest` checks contrast and uniqueness. */
    val accents: Map<String, Pair<Long, Long>> = mapOf(
        "tap-race" to (0xFF0F766EL to 0xFF5EEAD4L),
        "tug-of-war" to (0xFF7C2D12L to 0xFFFDBA74L)
    )
}

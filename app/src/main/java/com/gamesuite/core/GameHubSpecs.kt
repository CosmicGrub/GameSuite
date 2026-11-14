package com.gamesuite.core

import com.gamesuite.games.billiards.BilliardsGolfHubSpecs
import com.gamesuite.games.carrom.CarromCurlingHubSpecs
import com.gamesuite.games.darts.DartsBowlingHubSpecs
import com.gamesuite.games.toyboxing.ToyBoxingBaseballHubSpecs
import com.gamesuite.games.toysoccer.ToySportsHubSpecs
import com.gamesuite.games.sixball.SixBallPigHubSpecs
import com.gamesuite.games.slotcars.SlotCarsFishingHubSpecs
import com.gamesuite.games.tankbattle.TankGalleryHubSpecs
import com.gamesuite.games.partypack.PartyTapTugHubSpecs
import com.gamesuite.games.partypack.PartyReactionWhackHubSpecs
import com.gamesuite.games.partypack.PartyTrailSumoHubSpecs
import com.gamesuite.games.partypack.PartyPaddleRpsHubSpecs
import com.gamesuite.games.blackjack.BlackjackWarHubSpecs
import com.gamesuite.games.holdem.HoldemHubSpecs
import com.gamesuite.games.ludo.ChineseLudoHubSpecs
import com.gamesuite.games.president.PresidentSevensHubSpecs
import com.gamesuite.games.speed.SpeedMatchingHubSpecs
import com.gamesuite.games.spider.SpiderHanafudaHubSpecs
import com.gamesuite.games.slideclimb.SlideClimbHubSpecs
import com.gamesuite.games.drawguess.DrawGuessHubSpecs
import com.gamesuite.games.echopads.EchoPadsHubSpecs
import com.gamesuite.games.actitout.ActItOutHubSpecs
import com.gamesuite.games.bridgebuilders.BridgeBuildersHubSpecs

/**
 * The Game Hub spec of every game (docs/design/game-hub.md). A spec is data: adding a mode or an
 * option to a game is an edit here plus whatever the game's screen needs to honour it.
 *
 * Nearby and Online modes are added to a game only once its engine is verified to play over the
 * lobby transport: list its id in [networkedGames].
 */
object GameHubSpecs {
    /** Games whose engine and screen support Nearby / Online play. Grows as each game is finished. */
    /**
     * Networked games that can also be WATCHED. Only UNO: its host registers a spectator when it asks for
     * state (a seat-less RequestState), streams it the same StateSync commits as the players but redacted to
     * public information only (no card faces), and re-sends GameStart to a spectator who joins after Start
     * (OnlineLateJoinResponder). Every other networked engine sends state only to its seated players, so a
     * spectator would sit in a game that never loads: add a game here only once it delivers redacted state
     * to spectators (and to one who joins after Start). The Online lobby hides Watch for everything else.
     */
    val spectatorGames: Set<String> = setOf("uno")

    /** The relay's room size (host included): an Online table can never have more seats than this. */
    const val ONLINE_MAX_SEATS: Int = 8

    val networkedGames: Set<String> = setOf(
        "uno", "chess", "tic-tac-toe", "connect-four", "checkers", "reversi", "mancala", "dots-and-boxes",
        "dominoes", "word-tiles", "gomoku", "hex", "nine-mens-morris", "hare-and-hounds", "chinese-checkers", "ludo",
        "shogi", "mini-shogi", "blackjack", "war", "texas-holdem", "president", "sevens", "speed", "matching",
        "hanafuda",
        "darts", "bowling", "carrom", "curling", "billiards", "mini-golf", "toy-soccer", "toy-tennis", "toy-boxing", "toy-baseball",
        "slot-cars", "fishing", "tank-battle", "shooting-gallery", "six-ball-puzzle", "pig-dice"
    )

    val all: List<GameHubSpec> by lazy { buildAll(networkedGames) }

    fun byId(gameId: String): GameHubSpec? = all.firstOrNull { it.gameId == gameId }

    fun get(gameId: String): GameHubSpec = byId(gameId) ?: error("No Game Hub spec for $gameId")

    /** Specs grouped for the menu, in section order. */
    fun byFamily(): Map<HubFamily, List<GameHubSpec>> = all.groupBy { it.family }

    internal fun buildAll(networked: Set<String>): List<GameHubSpec> =
        baseSpecs().map { spec ->
            if (spec.gameId !in networked) spec else spec.copy(modes = spec.modes + networkModes(spec))
        }

    private fun networkModes(spec: GameHubSpec): List<HubMode> {
        val route = spec.modes.first().route
        // A solo mode seats one; a shared table needs at least two, so a Nearby/Online lobby never starts alone.
        val minSeats = spec.modes.filter { it.kind != HubModeKind.SOLO && it.kind != HubModeKind.DAILY }
            .minOfOrNull { it.minSeats } ?: 2
        val maxSeats = spec.modes.maxOf { it.maxSeats }
        return listOf(
            HubMode(
                id = "nearby", kind = HubModeKind.NEARBY, title = "Nearby",
                blurb = "Play friends on phones or tablets close to you.",
                route = route, start = HubStart.LOBBY, minSeats = minSeats, maxSeats = maxSeats
            ),
            HubMode(
                id = "online", kind = HubModeKind.ONLINE, title = "Online",
                blurb = "Play friends over the internet with a room code.",
                route = route, start = HubStart.LOBBY, minSeats = minSeats,
                maxSeats = minOf(maxSeats, ONLINE_MAX_SEATS)
            )
        )
    }

    // ---- shared mode builders ------------------------------------------------------------------

    /** A two-seat turn-based board game: vs the computer, or two people on one device. */
    private fun twoSeat(
        gameId: String,
        title: String,
        tagline: String,
        extraOptions: List<HubOption> = emptyList(),
        routeFor: (HubChoices) -> String = { gameId },
        extraRoutes: List<String> = emptyList()
    ) = GameHubSpec(
        gameId = gameId, title = title, tagline = tagline, family = HubFamily.BOARD,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer", blurb = "Play against the computer.",
                route = gameId, start = HubStart.IMMEDIATE, defaultCpus = 1,
                options = extraOptions + listOf(HubOptions.difficulty(), HubOptions.firstMover("The computer")),
                routeFor = routeFor, extraRoutes = extraRoutes
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play", blurb = "Two people, one device.",
                route = gameId, start = HubStart.READY_SCREEN,
                options = extraOptions + listOf(HubOptions.firstMover("Player 2", "Player 1")),
                routeFor = routeFor, extraRoutes = extraRoutes
            )
        )
    )

    /** A solo game with a free-play mode and a once-a-day puzzle on its own route. */
    private fun puzzle(
        gameId: String,
        title: String,
        tagline: String,
        dailyTitle: String = "Today's puzzle",
        family: HubFamily = HubFamily.ARCADE_PUZZLE
    ) = GameHubSpec(
        gameId = gameId, title = title, tagline = tagline, family = family,
        modes = listOf(
            HubMode(
                id = "free", kind = HubModeKind.SOLO, title = "Free play",
                blurb = "Pick a size and play as long as you like.",
                route = gameId, start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1
            ),
            HubMode(
                id = "daily", kind = HubModeKind.DAILY, title = dailyTitle,
                blurb = "The same puzzle for everyone today.",
                route = "$gameId-daily", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1
            )
        )
    )

    private fun solo(
        gameId: String,
        title: String,
        tagline: String,
        family: HubFamily,
        resumable: Boolean = true,
        blurb: String = "Play on your own."
    ) = GameHubSpec(
        gameId = gameId, title = title, tagline = tagline, family = family, resumable = resumable,
        modes = listOf(
            HubMode(
                id = "solo", kind = HubModeKind.SOLO, title = "Play", blurb = blurb,
                route = gameId, start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1
            )
        )
    )

    /**
     * Saves made before the Hub live under UNO's old routes (Teams, house rules). They stay reachable
     * so the Hub lists them for Resume; nothing launches new games on these routes any more.
     */
    private val LEGACY_UNO_ROUTES = listOf("uno-teams", "uno-custom")

    // ---- UNO ----------------------------------------------------------------------------------

    private fun toggle(
        key: String,
        label: String,
        help: String,
        visibleWhen: ((HubChoices) -> Boolean)? = null
    ) = HubOption.Toggle(key, label, false, help, visibleWhen)

    private fun preset(
        stack: Boolean, stackFour: Boolean, sevenZero: Boolean, jumpIn: Boolean,
        forcePlay: Boolean, drawUntil: Boolean, hand: Int, target: Int
    ): Map<String, String> = mapOf(
        "stackDraw" to stack.toString(),
        "stackDrawFourOnDrawTwo" to stackFour.toString(),
        "sevenZero" to sevenZero.toString(),
        "jumpIn" to jumpIn.toString(),
        "forcePlayDrawnCard" to forcePlay.toString(),
        "drawUntilPlayable" to drawUntil.toString(),
        "handSize" to hand.toString(),
        "targetScore" to target.toString()
    )

    /** UNO's rule options (keys equal the UnoRules field names so the screen maps them directly). */
    internal fun unoRuleOptions(): List<HubOption> = listOf(
        HubOption.Choice(
            key = "preset", label = "Rule preset", default = "classic",
            help = "Classic is the official game. Presets switch on several house rules at once; you can still adjust them below.",
            choices = listOf(
                HubChoice("classic", "Classic", "The official rules.",
                    preset(false, false, false, false, false, false, 7, 500)),
                HubChoice("party", "Party", "Stacking, 7-0 and jump-in.",
                    preset(true, false, true, true, false, false, 7, 500)),
                HubChoice("chaos", "Chaos", "Every house rule, bigger hands.",
                    preset(true, true, true, true, true, true, 10, 200)),
                HubChoice("quick", "Quick round", "Small hands, one round decides it.",
                    preset(false, false, false, false, false, false, 5, 0)),
                HubChoice("custom", "Custom", "Your own mix.")
            )
        ),
        toggle("stackDraw", "Draw stacking", "Answer a +2 with a +2 (or +4) instead of drawing at once."),
        toggle("stackDrawFourOnDrawTwo", "Allow +4 on +2", "A +4 may answer a stacked +2.") { it.bool("stackDraw", false) },
        toggle("sevenZero", "7-0", "A 7 swaps hands with an opponent; a 0 passes every hand along."),
        toggle("jumpIn", "Jump-in", "Play an exact match of the top card out of turn."),
        toggle("forcePlayDrawnCard", "Must play a drawn card", "Off (official): playing a card you just drew is your choice."),
        toggle("drawUntilPlayable", "Draw until you can play", "With no playable card, keep drawing until one fits."),
        HubOption.Stepper("handSize", "Starting hand", 3, 10, 7, "Cards dealt to each player."),
        HubOption.Choice(
            key = "targetScore", label = "Play to", default = "500",
            help = "A match ends when someone reaches this score. One round: the first hand decides.",
            choices = listOf(HubChoice("0", "One round"), HubChoice("200", "200"), HubChoice("300", "300"), HubChoice("500", "500"))
        )
    )

    private fun uno(): GameHubSpec {
        val rules = unoRuleOptions()
        return GameHubSpec(
            gameId = "uno", title = "UNO", family = HubFamily.CARD,
            tagline = "Match colours and numbers. Up to 10 players, any mix of people and computers.",
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                    blurb = "You against 1 to 9 computer players.",
                    route = "uno", extraRoutes = LEGACY_UNO_ROUTES, start = HubStart.IMMEDIATE, defaultCpus = 2, minSeats = 2, maxSeats = 10,
                    options = listOf(HubOptions.cpus(1, 9, 2), HubOptions.difficulty()) + rules
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "uno", extraRoutes = LEGACY_UNO_ROUTES, start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 10,
                    options = listOf(HubOptions.humans(2, 6, 4), HubOptions.cpus(0, 8, 0), HubOptions.difficulty()) + rules
                ),
                HubMode(
                    id = "teams", kind = HubModeKind.PASS_AND_PLAY, title = "Teams",
                    blurb = "Partners sit opposite and win together. Needs an even table.",
                    route = "uno", extraRoutes = LEGACY_UNO_ROUTES, start = HubStart.READY_SCREEN, minSeats = 4, maxSeats = 10,
                    options = listOf(
                        HubOptions.humans(1, 6, 2), HubOptions.cpus(0, 8, 2), HubOptions.difficulty(),
                        HubOption.Toggle(HubKeys.TEAMS, "Team play", true, visibleWhen = { false })
                    ) + rules
                )
            )
        )
    }

    // ---- the table ----------------------------------------------------------------------------

    private fun houndsSide(label: String) = HubOption.Choice(
        key = "side", label = label, default = "hounds", choices = listOf(
            HubChoice("hounds", "The hounds", "Three hunters that never step back."),
            HubChoice("hare", "The hare", "One runner that can go anywhere."),
            HubChoice("random", "Random")
        )
    )

    private fun baseSpecs(): List<GameHubSpec> = listOf(
        // Board games
        twoSeat(
            "chess", "Chess", "The classic. Full rules with castling, en passant and promotion.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "clock", label = "Chess clock", default = "none",
                    help = "Each side gets this much time (minutes + seconds added per move). Run out and you lose on time.",
                    choices = listOf(
                        HubChoice("none", "No clock"),
                        HubChoice("3+0", "3+0 Blitz"),
                        HubChoice("5+0", "5+0"),
                        HubChoice("10+0", "10+0 Rapid"),
                        HubChoice("15+10", "15+10")
                    )
                )
            )
        ),
        twoSeat(
            "checkers", "Checkers", "Jump to capture. Mandatory captures and multi-jumps.",
            extraOptions = listOf(
                HubOption.Toggle(
                    key = "mandatoryCapture", label = "Mandatory capture", default = true,
                    help = "On: you must jump when you can. Off: jumping is optional, but a jump you start must finish."
                )
            )
        ),
        twoSeat(
            "reversi", "Reversi", "Flank your opponent's discs to flip them.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "board", label = "Board size", default = "standard",
                    help = "A bigger board means longer games; the computer thinks a little less deeply on the large one.",
                    choices = listOf(
                        HubChoice("small", "Small (6x6)", "A quick game."),
                        HubChoice("standard", "Standard (8x8)", "The classic board."),
                        HubChoice("large", "Large (10x10)", "A long game.")
                    )
                )
            )
        ),
        twoSeat(
            "mancala", "Mancala", "Sow stones around the board and capture the most.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "stones", label = "Stones per pit", default = "4",
                    help = "How many stones start in every pit. Four is the classic game.",
                    choices = listOf(HubChoice("3", "3"), HubChoice("4", "4"), HubChoice("5", "5"), HubChoice("6", "6"))
                ),
                HubOption.Choice(
                    key = "pits", label = "Pits per side", default = "6",
                    help = "Six is the classic board; five is a shorter game.",
                    choices = listOf(HubChoice("5", "5"), HubChoice("6", "6"))
                ),
                HubOption.Choice(
                    key = "capture", label = "Captures", default = "opposite",
                    help = "Opposite: end in an empty pit of yours to take the pit across. None: no captures, only the extra turn.",
                    choices = listOf(HubChoice("opposite", "Opposite pit"), HubChoice("none", "None"))
                )
            )
        ),
        twoSeat(
            "connect-four", "Connect Four", "Drop discs and line up four.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "size", label = "Board size", default = "classic",
                    help = "Classic is 7 by 6, connect 4. Wide is 8 by 7, connect 4. Big is 9 by 7, connect 5.",
                    choices = listOf(
                        HubChoice("classic", "Classic", "7 by 6, connect 4."),
                        HubChoice("wide", "Wide", "8 by 7, connect 4."),
                        HubChoice("big", "Big", "9 by 7, connect 5.")
                    )
                )
            )
        ),
        twoSeat(
            "dots-and-boxes", "Dots and Boxes", "Draw lines, close boxes, take another turn.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "grid", label = "Board size", default = "standard",
                    help = "More boxes means a longer game. Standard is 5 x 5.",
                    choices = listOf(
                        HubChoice("small", "3 x 3"), HubChoice("medium", "4 x 4"),
                        HubChoice("standard", "5 x 5"), HubChoice("large", "6 x 6")
                    )
                )
            )
        ),
        twoSeat(
            "tic-tac-toe", "Tic-Tac-Toe", "Three in a row, with Misere and Wild twists.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "variant", label = "Variant", default = "classic",
                    help = "Misere: three in a row loses. Wild: each move you choose X or O.",
                    choices = listOf(HubChoice("classic", "Classic"), HubChoice("misere", "Misere"), HubChoice("wild", "Wild"))
                ),
                HubOption.Choice(
                    key = "board", label = "Board size", default = "classic",
                    help = "Classic is 3x3. 4x4 and 5x5 need four in a row. Misere and Wild are always 3x3.",
                    choices = listOf(
                        HubChoice("classic", "3x3"),
                        HubChoice("four", "4x4"),
                        HubChoice("five", "5x5")
                    ),
                    visibleWhen = { c -> c.string("variant", "classic") == "classic" }
                )
            ),
            routeFor = { c ->
                when (c.string("variant", "classic")) {
                    "misere" -> "tic-tac-toe-misere"
                    "wild" -> "tic-tac-toe-wild"
                    else -> "tic-tac-toe"
                }
            },
            extraRoutes = listOf("tic-tac-toe-misere", "tic-tac-toe-wild")
        ),
        twoSeat(
            "dominoes", "Dominoes", "Match the ends of the chain and go out first.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "set", label = "Tile set", default = "double-six",
                    help = "Double-six: 28 tiles, 7 each. Double-nine: 55 tiles, 10 each, for a longer game.",
                    choices = listOf(
                        HubChoice("double-six", "Double-six", "28 tiles, 7 each."),
                        HubChoice("double-nine", "Double-nine", "55 tiles, 10 each.")
                    )
                )
            )
        ),
        twoSeat("word-tiles", "Word Tiles", "Build words on the board from your rack."),

        // Cards
        uno(),
        BlackjackWarHubSpecs.blackjack,
        BlackjackWarHubSpecs.war,
        HoldemHubSpecs.holdem,
        PresidentSevensHubSpecs.president,
        PresidentSevensHubSpecs.sevens,
        SpeedMatchingHubSpecs.speed,
        SpeedMatchingHubSpecs.matching,
        SpiderHanafudaHubSpecs.spiderSolitaire,
        SpiderHanafudaHubSpecs.hanafuda,
        DartsBowlingHubSpecs.darts,
        DartsBowlingHubSpecs.bowling,
        CarromCurlingHubSpecs.carrom,
        CarromCurlingHubSpecs.curling,
        BilliardsGolfHubSpecs.billiards,
        BilliardsGolfHubSpecs.miniGolf,
        ToySportsHubSpecs.toySoccer,
        ToySportsHubSpecs.toyTennis,
        ToyBoxingBaseballHubSpecs.boxing,
        ToyBoxingBaseballHubSpecs.baseball,
        SlotCarsFishingHubSpecs.slotCars,
        SlotCarsFishingHubSpecs.fishing,
        TankGalleryHubSpecs.tank,
        TankGalleryHubSpecs.gallery,
        SixBallPigHubSpecs.sixBall,
        SixBallPigHubSpecs.pig,
        PartyTapTugHubSpecs.tapRace,
        PartyTapTugHubSpecs.tugOfWar,
        PartyReactionWhackHubSpecs.reactionDuel,
        PartyReactionWhackHubSpecs.whackAMole,
        PartyTrailSumoHubSpecs.lightTrail,
        PartyTrailSumoHubSpecs.sumoPush,
        PartyPaddleRpsHubSpecs.paddleDuel,
        PartyPaddleRpsHubSpecs.rpsShowdown,
        EchoPadsHubSpecs.echoPads,
        BridgeBuildersHubSpecs.spec,
        ActItOutHubSpecs.actItOut,
        solo("solitaire", "Solitaire", "Klondike with drag and drop.", HubFamily.CARD),

        // Word games
        solo("hangman", "Hangman", "Guess the word before the drawing is finished.", HubFamily.WORD),
        puzzle("word-search", "Word Search", "Find the hidden words.", "Today's word search", HubFamily.WORD),
        solo("crossword", "Crossword", "Fill the grid from the clues.", HubFamily.WORD),
        puzzle("word-guess", "Word Guess", "Find the five-letter word in six tries.", "Today's word", HubFamily.WORD),
        DrawGuessHubSpecs.drawGuess,

        // Arcade and puzzles
        puzzle("sudoku", "Sudoku", "Fill every row, column and box with 1 to 9."),
        puzzle("kenken", "KenKen", "Arithmetic cages on a Latin square."),
        puzzle("kakuro", "Kakuro", "Crossword-style sums."),
        puzzle("nonogram", "Nonogram", "Reveal a picture from number clues."),
        puzzle("minesweeper", "Minesweeper", "Clear the field without hitting a mine.", "Today's board"),
        puzzle("lights-out", "Lights Out", "Switch every light off.", "Today's board"),
        puzzle("sliding-puzzle", "Sliding Puzzle", "Slide the tiles back into order."),
        puzzle("color-flood", "Color Flood", "Flood the board with one colour.", "Today's board"),
        puzzle("edge-match", "Edge Match", "Rotate tiles until every edge matches."),
        puzzle("mastermind", "Mastermind", "Crack the hidden colour code.", "Today's code"),
        solo("breakout", "Breakout", "Bounce the ball and clear the wall.", HubFamily.ARCADE_PUZZLE, resumable = false),
        solo("tower-defence", "Tower Defence", "Build towers and stop the waves.", HubFamily.ARCADE_PUZZLE, resumable = false),
        GameHubSpec(
            gameId = "air-hockey", title = "Air Hockey", tagline = "Fast two-player table hockey.",
            family = HubFamily.ARCADE_PUZZLE, resumable = false,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer", blurb = "Take on the computer.",
                    route = "air-hockey", start = HubStart.IMMEDIATE, options = listOf(HubOptions.difficulty())
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play", blurb = "Two people, one device.",
                    route = "air-hockey", start = HubStart.READY_SCREEN
                )
            )
        ),

        // Added in wave 3
        GameHubSpec(
            gameId = "yahtzee", title = "Yahtzee", tagline = "Roll five dice, fill thirteen boxes.",
            family = HubFamily.BOARD, resumable = true,
            modes = listOf(
                HubMode(
                    id = "solo", kind = HubModeKind.SOLO, title = "Beat your best", blurb = "Play on your own.",
                    route = "yahtzee", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1
                ),
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Play against 1 to 3 computers.",
                    route = "yahtzee", start = HubStart.IMMEDIATE, defaultCpus = 1, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.cpus(1, 3, 1), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "2 to 4 people, one device.",
                    route = "yahtzee", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 4,
                    options = listOf(HubOptions.humans(2, 4, 2), HubOptions.firstMover("Player 2", "Player 1"))
                )
            )
        ),
        GameHubSpec(
            gameId = "backgammon", title = "Backgammon",
            tagline = "Race your checkers home and bear them off.", family = HubFamily.BOARD,
            modes = listOf(
                HubMode(
                    id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer",
                    blurb = "Play against the computer.", route = "backgammon",
                    start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(HubOptions.difficulty(), HubOptions.firstMover("The computer", "Opening roll"))
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Two people, one device.", route = "backgammon",
                    start = HubStart.READY_SCREEN,
                    options = listOf(HubOptions.firstMover("Player 2", "Opening roll"))
                )
            )
        ),
        GameHubSpec(
            gameId = "game-2048", title = "2048", tagline = "Slide and merge tiles to reach 2048.",
            family = HubFamily.ARCADE_PUZZLE, resumable = true,
            modes = listOf(
                HubMode(
                    id = "free", kind = HubModeKind.SOLO, title = "Free play",
                    blurb = "Swipe to merge tiles for as long as you like.",
                    route = "game-2048", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                    options = listOf(
                        HubOption.Choice(
                            key = "size", label = "Grid size", default = "classic",
                            help = "Small wins at 512. Classic and Big win at 2048.",
                            choices = listOf(
                                HubChoice("small", "Small 3 x 3", "Quick and tight."),
                                HubChoice("classic", "Classic 4 x 4", "The original game."),
                                HubChoice("big", "Big 5 x 5", "Room to build big tiles.")
                            )
                        )
                    )
                )
            )
        ),
        GameHubSpec(
            gameId = "snake", title = "Snake",
            tagline = "Eat, grow, and never bite your own tail.",
            family = HubFamily.ARCADE_PUZZLE, resumable = false,
            modes = listOf(
                HubMode(
                    id = "play", kind = HubModeKind.SOLO, title = "Play",
                    blurb = "Swipe or use the pad to steer.",
                    route = "snake", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                    options = listOf(
                        HubOption.Choice(
                            "speed", "Snake speed",
                            listOf(HubChoice("slow", "Relaxed"), HubChoice("normal", "Steady"), HubChoice("fast", "Quick")),
                            default = "normal", help = "It also speeds up as you eat."
                        ),
                        HubOption.Choice(
                            "walls", "Board edges",
                            listOf(HubChoice("solid", "Solid walls"), HubChoice("wrap", "Wrap-around")),
                            default = "solid", help = "Wrap lets you cross to the far side."
                        ),
                        HubOption.Choice(
                            "grid", "Play field",
                            listOf(
                                HubChoice("small", "Small 12x12"), HubChoice("classic", "Classic 16x16"),
                                HubChoice("big", "Big 20x20")
                            ),
                            default = "classic", help = "A bigger field leaves more room to grow."
                        )
                    )
                )
            )
        ),
        twoSeat(
            "gomoku", "Gomoku", "Line up five stones before your opponent does.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "rules", label = "Rules", default = "free",
                    help = "Free-style: five or more in a row wins. Exactly five: an overline of six or more does not count.",
                    choices = listOf(
                        HubChoice("free", "Free-style", "Five or more in a row wins."),
                        HubChoice("exact", "Exactly five", "Six or more in a row does not count.")
                    )
                )
            )
        ),
        twoSeat(
            "hex", "Hex", "Join your two sides of the board before your opponent does.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "board", label = "Board size", default = "11",
                    help = "A bigger board means a longer game. Eleven is the classic size.",
                    choices = listOf(
                        HubChoice("9", "9 x 9", "A quick game."),
                        HubChoice("11", "11 x 11", "The classic board."),
                        HubChoice("13", "13 x 13", "A long game.")
                    )
                ),
                HubOption.Toggle(
                    key = "pie", label = "Pie rule", default = false,
                    help = "After the first stone the second player may swap sides, so a strong opening is not a free win."
                )
            )
        ),
        twoSeat(
            "nine-mens-morris", "Nine Men's Morris", "Line up three men to form a mill and capture.",
            extraOptions = listOf(
                HubOption.Choice(key = "men", label = "Men each", default = "9", choices = listOf(
                    HubChoice("9", "Nine men", "The classic game."), HubChoice("6", "Six men", "A shorter game."))),
                HubOption.Choice(key = "flying", label = "Flying", default = "on", choices = listOf(
                    HubChoice("on", "Flying allowed", "A player down to three men can jump anywhere."),
                    HubChoice("off", "No flying", "Men always slide one step along a line.")))
            )
        ),
        GameHubSpec(
            gameId = "hare-and-hounds", title = "Hare and Hounds",
            tagline = "Three hounds hunt one hare across the board.", family = HubFamily.BOARD,
            modes = listOf(
                HubMode(id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer", blurb = "Play against the computer.",
                    route = "hare-and-hounds", start = HubStart.IMMEDIATE, defaultCpus = 1,
                    options = listOf(houndsSide("You play"), HubOptions.difficulty())),
                HubMode(id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play", blurb = "Two people, one device.",
                    route = "hare-and-hounds", start = HubStart.READY_SCREEN, options = listOf(houndsSide("Player 1 plays")))
            )
        ),
        ChineseLudoHubSpecs.chineseCheckers,
        ChineseLudoHubSpecs.ludo,
        SlideClimbHubSpecs.slideClimb,
        twoSeat("shogi", "Shogi", "Japanese chess: captured pieces join your side.", extraOptions = listOf(HubOption.Choice(
            key = "pieces", label = "Piece style", default = "kanji",
            help = "Kanji is the traditional look. Symbols draws arrows showing how each piece moves, so no kanji reading is needed.",
            choices = listOf(
                HubChoice("kanji", "Kanji", "Traditional characters."),
                HubChoice("symbols", "Symbols", "Arrows and letters.")
            )
        ))),
        twoSeat("mini-shogi", "Mini Shogi", "Shogi on a small 5x5 board: quick, sharp games.", extraOptions = listOf(HubOption.Choice(
            key = "pieces", label = "Piece style", default = "kanji",
            help = "Kanji is the traditional look. Symbols draws arrows showing how each piece moves, so no kanji reading is needed.",
            choices = listOf(
                HubChoice("kanji", "Kanji", "Traditional characters."),
                HubChoice("symbols", "Symbols", "Arrows and letters.")
            )
        ))),
        puzzle("flow-lines", "Flow Lines", "Join each pair of dots and fill the board.", "Today's board"),
        GameHubSpec(
            gameId = "mahjong-solitaire", title = "Mahjong Solitaire",
            tagline = "Match free pairs of tiles to clear the board.",
            family = HubFamily.ARCADE_PUZZLE, resumable = true,
            modes = listOf(
                HubMode(
                    id = "play", kind = HubModeKind.SOLO, title = "Play", blurb = "Play on your own.",
                    route = "mahjong-solitaire", start = HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1,
                    options = listOf(
                        HubOption.Choice(
                            key = "layout", label = "Tile layout", default = "turtle",
                            help = "Turtle: 144 tiles. Pyramid: 92. Cross: 60.",
                            choices = listOf(
                                HubChoice("turtle", "Turtle", "The classic 144-tile layout."),
                                HubChoice("pyramid", "Pyramid", "A stepped pyramid of 92 tiles."),
                                HubChoice("cross", "Cross", "A plus-shaped layout of 60 tiles.")
                            )
                        )
                    )
                )
            )
        ),

        // Toolkit
        solo("party-toolkit", "Party Toolkit", "Dice, spinners, timers and score keepers.", HubFamily.TOOLKIT, resumable = false, blurb = "Open the toolkit.")
    )
}

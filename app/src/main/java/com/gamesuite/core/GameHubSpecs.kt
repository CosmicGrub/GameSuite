package com.gamesuite.core

/**
 * The Game Hub spec of every game (docs/design/game-hub.md). A spec is data: adding a mode or an
 * option to a game is an edit here plus whatever the game's screen needs to honour it.
 *
 * Nearby and Online modes are added to a game only once its engine is verified to play over the
 * lobby transport: list its id in [networkedGames].
 */
object GameHubSpecs {
    /** Games whose engine and screen support Nearby / Online play. Grows as each game is finished. */
    val networkedGames: Set<String> = setOf(
        "uno", "chess", "tic-tac-toe", "connect-four", "checkers", "reversi", "mancala", "dots-and-boxes"
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
        val minSeats = spec.modes.minOf { it.minSeats }
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
                route = route, start = HubStart.LOBBY, minSeats = minSeats, maxSeats = maxSeats
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
                    route = "uno", start = HubStart.IMMEDIATE, defaultCpus = 2, minSeats = 2, maxSeats = 10,
                    options = listOf(HubOptions.cpus(1, 9, 2), HubOptions.difficulty()) + rules
                ),
                HubMode(
                    id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                    blurb = "Friends share this device. Computers can fill empty seats.",
                    route = "uno", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 10,
                    options = listOf(HubOptions.humans(2, 6, 4), HubOptions.cpus(0, 8, 0), HubOptions.difficulty()) + rules
                ),
                HubMode(
                    id = "teams", kind = HubModeKind.PASS_AND_PLAY, title = "Teams",
                    blurb = "Partners sit opposite and win together. Needs an even table.",
                    route = "uno", start = HubStart.READY_SCREEN, minSeats = 4, maxSeats = 10,
                    options = listOf(
                        HubOptions.humans(1, 6, 2), HubOptions.cpus(0, 8, 2), HubOptions.difficulty(),
                        HubOption.Toggle(HubKeys.TEAMS, "Team play", true, visibleWhen = { false })
                    ) + rules
                )
            )
        )
    }

    // ---- the table ----------------------------------------------------------------------------

    private fun baseSpecs(): List<GameHubSpec> = listOf(
        // Board games
        twoSeat("chess", "Chess", "The classic. Full rules with castling, en passant and promotion."),
        twoSeat("checkers", "Checkers", "Jump to capture. Mandatory captures and multi-jumps."),
        twoSeat("reversi", "Reversi", "Flank your opponent's discs to flip them."),
        twoSeat("mancala", "Mancala", "Sow stones around the board and capture the most."),
        twoSeat("connect-four", "Connect Four", "Drop discs and line up four."),
        twoSeat("dots-and-boxes", "Dots and Boxes", "Draw lines, close boxes, take another turn."),
        twoSeat(
            "tic-tac-toe", "Tic-Tac-Toe", "Three in a row, with Misere and Wild twists.",
            extraOptions = listOf(
                HubOption.Choice(
                    key = "variant", label = "Variant", default = "classic",
                    help = "Misere: three in a row loses. Wild: each move you choose X or O.",
                    choices = listOf(HubChoice("classic", "Classic"), HubChoice("misere", "Misere"), HubChoice("wild", "Wild"))
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
        twoSeat("dominoes", "Dominoes", "Match the ends of the chain and go out first."),
        twoSeat("word-tiles", "Word Tiles", "Build words on the board from your rack."),

        // Cards
        uno(),
        solo("solitaire", "Solitaire", "Klondike with drag and drop.", HubFamily.CARD),

        // Word games
        solo("hangman", "Hangman", "Guess the word before the drawing is finished.", HubFamily.WORD),
        puzzle("word-search", "Word Search", "Find the hidden words.", "Today's word search", HubFamily.WORD),
        solo("crossword", "Crossword", "Fill the grid from the clues.", HubFamily.WORD),
        puzzle("word-guess", "Word Guess", "Find the five-letter word in six tries.", "Today's word", HubFamily.WORD),

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

        // Toolkit
        solo("party-toolkit", "Party Toolkit", "Dice, spinners, timers and score keepers.", HubFamily.TOOLKIT, resumable = false, blurb = "Open the toolkit.")
    )
}

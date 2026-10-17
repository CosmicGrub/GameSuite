package com.gamesuite.audio.music

/** Which playlist is active. Every context has its own ordered, per-track-toggleable list. */
enum class MusicContext { MENU, BOARD, CARD, PUZZLE_WORD, ARCADE }

private val BOARD_ROUTES = setOf(
    "chess", "checkers", "reversi", "mancala", "connect-four", "dots-and-boxes", "dominoes", "word-tiles"
)
private val CARD_ROUTES = setOf("solitaire")
private val PUZZLE_ROUTES = setOf(
    "hangman", "crossword", "sudoku", "kenken", "kakuro", "nonogram", "minesweeper",
    "lights-out", "sliding-puzzle", "color-flood", "edge-match", "mastermind"
)
private val ARCADE_ROUTES = setOf("breakout", "tower-defence", "air-hockey")

/**
 * Pure nav-route to [MusicContext] mapping. Tolerates a query string, leading/trailing slashes,
 * case, and the "-daily" suffix. Unknown or blank routes map to MENU.
 */
fun contextForRoute(route: String?): MusicContext {
    if (route.isNullOrBlank()) return MusicContext.MENU
    val first = route.trim().lowercase()
        .substringBefore('?')
        .trim('/')
        .substringBefore('/')
    if (first.isEmpty() || first == "hub") return MusicContext.MENU
    val base = first.removeSuffix("-daily")
    return when {
        base in BOARD_ROUTES || base.startsWith("tic-tac-toe") -> MusicContext.BOARD
        base.startsWith("uno") || base in CARD_ROUTES -> MusicContext.CARD
        base in ARCADE_ROUTES -> MusicContext.ARCADE
        base in PUZZLE_ROUTES || base.startsWith("word-") -> MusicContext.PUZZLE_WORD
        else -> MusicContext.MENU // menu, games, stats, settings, music, party-toolkit, unknown
    }
}

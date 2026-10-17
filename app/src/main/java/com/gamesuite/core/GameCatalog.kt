package com.gamesuite.core

/*
 * Pure helpers behind the Games tab, the Home tab and the game cards (docs/design/game-hub.md).
 * Nothing here touches Compose or Android, so the search / filter / route-matching rules are unit
 * tested in GameCatalogTest.
 */

/** Lower-cases, turns dashes into spaces and collapses whitespace, so "tic-tac" finds "Tic-Tac-Toe". */
private fun catalogNormalize(text: String): String =
    text.lowercase().replace('-', ' ').trim().replace(Regex("\\s+"), " ")

/**
 * The games in [specs] that match [query] and [family], in the original order.
 *
 * A blank [query] matches everything; otherwise a game matches when the (trimmed, case-insensitive)
 * query is contained in its title, its tagline or its game id with dashes read as spaces. A null
 * [family] means every family.
 */
fun filterGames(specs: List<GameHubSpec>, query: String, family: HubFamily?): List<GameHubSpec> {
    val q = catalogNormalize(query)
    return specs.filter { spec ->
        (family == null || spec.family == family) &&
            (q.isEmpty() ||
                catalogNormalize(spec.title).contains(q) ||
                catalogNormalize(spec.tagline).contains(q) ||
                catalogNormalize(spec.gameId).contains(q))
    }
}

/** The id of the game whose Hub owns nav [route] (a saved game's `navRoute`), or null when none does. */
fun gameIdForRoute(specs: List<GameHubSpec>, route: String): String? =
    specs.firstOrNull { route in it.routes() }?.gameId

/** The menu sections in display order. */
val catalogFamilyOrder: List<HubFamily> = listOf(
    HubFamily.BOARD, HubFamily.CARD, HubFamily.WORD, HubFamily.ARCADE_PUZZLE, HubFamily.TOOLKIT
)

/** [specs] grouped by family in [catalogFamilyOrder], skipping families with no games. */
fun groupByFamily(specs: List<GameHubSpec>): List<Pair<HubFamily, List<GameHubSpec>>> =
    catalogFamilyOrder.mapNotNull { family ->
        val inFamily = specs.filter { it.family == family }
        if (inFamily.isEmpty()) null else family to inFamily
    }

/** The daily mode of [spec], or null when it has none. */
fun dailyModeOf(spec: GameHubSpec): HubMode? = spec.modes.firstOrNull { it.kind == HubModeKind.DAILY }

/** A decorative emoji for a game's card. Unknown ids get a generic die. */
fun gameGlyph(gameId: String): String = when (gameId) {
    "chess" -> "♟"
    "checkers" -> "⛀"
    "reversi" -> "⚫"
    "mancala" -> "🥣"
    "connect-four" -> "🔴"
    "dots-and-boxes" -> "▦"
    "tic-tac-toe" -> "❌"
    "dominoes" -> "🁣"
    "word-tiles" -> "🔤"
    "uno" -> "🃏"
    "solitaire" -> "🂡"
    "hangman" -> "🪢"
    "word-search" -> "🔍"
    "crossword" -> "📰"
    "word-guess" -> "🟩"
    "sudoku" -> "🔢"
    "kenken" -> "➗"
    "kakuro" -> "➕"
    "nonogram" -> "🖼"
    "minesweeper" -> "💣"
    "lights-out" -> "💡"
    "sliding-puzzle" -> "🧩"
    "color-flood" -> "🎨"
    "edge-match" -> "🔷"
    "mastermind" -> "🎯"
    "breakout" -> "🧱"
    "tower-defence" -> "🏰"
    "air-hockey" -> "🏒"
    "party-toolkit" -> "🎲"
    else -> "🎲"
}

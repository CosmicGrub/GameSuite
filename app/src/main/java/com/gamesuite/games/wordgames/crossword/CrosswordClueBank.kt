package com.gamesuite.games.wordgames.crossword

/**
 * Crossword answers need real clues, and the bundled dictionary has no clue
 * data — so unlike word search (which can pull arbitrary dictionary words),
 * crossword uses a small hand-authored, themed clue bank instead of trying
 * to auto-generate clues for random words. This is one theme (games/tech,
 * fitting this app); more banks can be added and picked at random per match.
 */
object CrosswordClueBank {
    data class Entry(val word: String, val clue: String)

    val gamesAndTechTheme = listOf(
        Entry("ANDROID", "Google's mobile OS"),
        Entry("KOTLIN", "Language this app is written in"),
        Entry("PIXEL", "Smallest unit of a screen image"),
        Entry("BOARD", "Chess or checkers surface"),
        Entry("DECK", "Collection of playing cards"),
        Entry("DICE", "Rolled for random results"),
        Entry("TOKEN", "Game piece moved on a board"),
        Entry("SCORE", "Tally of points earned"),
        Entry("LEVEL", "Stage of a game"),
        Entry("BONUS", "Extra reward"),
        Entry("TURN", "One player's chance to act"),
        Entry("RULES", "What every game needs"),
        Entry("TILE", "Square piece, as in Scrabble"),
        Entry("MATCH", "A single game session"),
        Entry("TEAM", "Group playing together"),
        Entry("SOLO", "Playing alone"),
        Entry("BOT", "Computer-controlled opponent"),
        Entry("WIN", "Opposite of lose"),
        Entry("DRAW", "Neither side wins"),
        Entry("CARD", "UNO or poker piece"),
        Entry("WILD", "UNO card that changes color"),
        Entry("SKIP", "UNO card that passes the next player"),
        Entry("FOLD", "What a Z-series Samsung phone does"),
        Entry("TABLET", "Larger touchscreen device, like a Tab S9"),
        Entry("APP", "Short for application"),
        Entry("GRID", "Rows and columns layout"),
        Entry("MAZE", "Puzzle you navigate through"),
        Entry("CLUE", "Hint toward an answer"),
        Entry("WORD", "Unit of language, and this game's theme"),
        Entry("PLAY", "To take part in a game")
    )
}

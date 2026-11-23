package com.gamesuite.core

/** One game's audience tag and youngest sensible age. */
data class AudienceEntry(val audience: GameAudience, val minAge: Int)

/**
 * Audience tags for the games that existed before profiles, kept in ONE table instead of 75 spec edits.
 * New games set `audience` / `minAge` on their own GameHubSpec (those win over this table).
 * Anything not listed here is ADULTS. A KID profile's age band shows a game when minAge <= the band's top
 * age (4-6 -> 6, 7-9 -> 9, 10-12 -> 12). Gambling-themed games are blocked for kids separately
 * ([KID_BLOCKED_GAME_IDS]) and are not listed here on purpose.
 */
object GameAudienceTable {
    private fun k(minAge: Int) = AudienceEntry(GameAudience.KIDS, minAge)
    private fun f(minAge: Int) = AudienceEntry(GameAudience.FAMILY, minAge)

    private val table: Map<String, AudienceEntry> = buildMap {
        // Tap / reaction party games: no reading, instant rules, short rounds.
        put("tap-race", k(4)); put("tug-of-war", k(4)); put("whack-a-mole", k(4)); put("matching", k(4))
        put("reaction-duel", k(5)); put("sumo-push", k(5)); put("paddle-duel", k(5)); put("rps-showdown", k(4))
        put("light-trail", k(6))
        // Simple two-player boards and chase games: rules fit in one sentence.
        put("tic-tac-toe", k(5)); put("connect-four", k(6)); put("mancala", k(6)); put("dots-and-boxes", k(6))
        put("checkers", k(7)); put("hare-and-hounds", k(6)); put("ludo", k(5))
        // Single-player puzzles with a gentle learning curve and no reading load.
        put("sliding-puzzle", k(6)); put("color-flood", k(6)); put("flow-lines", k(6)); put("snake", k(6))
        put("game-2048", k(8)); put("breakout", k(6))
        // Toy sports and fairground minigames: slapstick, no violence beyond cartoon targets.
        put("darts", k(6)); put("bowling", k(5)); put("mini-golf", k(5)); put("toy-soccer", k(5))
        put("fishing", k(4)); put("shooting-gallery", k(7)); put("air-hockey", k(6)); put("toy-tennis", k(6))
        put("toy-baseball", k(6))
        // FAMILY: strategy / card / logic games a child can learn but that adults enjoy as much.
        put("chess", f(8)); put("reversi", f(8)); put("gomoku", f(8)); put("hex", f(8)); put("nine-mens-morris", f(8))
        put("chinese-checkers", f(7)); put("dominoes", f(7)); put("uno", f(7)); put("war", f(6))
        put("sevens", f(8)); put("speed", f(8)); put("solitaire", f(8)); put("spider-solitaire", f(10))
        put("president", f(10)); put("yahtzee", f(8)); put("mahjong-solitaire", f(8)); put("party-toolkit", f(6))
        put("curling", f(8)); put("carrom", f(8)); put("billiards", f(10)); put("six-ball-puzzle", f(10))
        put("toy-boxing", f(8)); put("tank-battle", f(10)); put("tower-defence", f(10))
        put("shogi", f(10)); put("mini-shogi", f(10))
        // Logic puzzles: reading numbers is enough, still FAMILY because difficulty ramps up quickly.
        put("sudoku", f(10)); put("minesweeper", f(10)); put("lights-out", f(8)); put("mastermind", f(8))
        put("nonogram", f(8)); put("edge-match", f(8)); put("kenken", f(10))
        // Deliberately NOT listed (stay ADULTS): hangman and word-search/word-guess/word-tiles/crossword
        // (raw dictionary words, not curated for children), backgammon (doubling-cube gambling theme),
        // kakuro (adult logic puzzle).
    }

    fun entryFor(gameId: String): AudienceEntry? = table[gameId]

    val ids: Set<String> get() = table.keys
}

package com.gamesuite.core

/** The fixed list of skills a game can be said to practise. [id] is stable (used in resource names: `rw_skill_<id>`). */
enum class Skill(val id: String) {
    MEMORY("memory"),
    COUNTING("counting"),
    PLANNING("planning"),
    DEDUCTION("deduction"),
    TURN_TAKING("turn_taking"),
    FINE_MOTOR("fine_motor"),
    TEAMWORK("teamwork"),
    LANGUAGE("language"),
    CREATIVITY("creativity");

    companion object {
        fun fromId(id: String): Skill? = values().firstOrNull { it.id == id }
    }
}

/**
 * "What this teaches": each game a KID profile can see, mapped to one or two [Skill]s (the first is the main one).
 * The friendly sentence for each skill lives in `rewards_strings.xml` (`rw_skill_<id>_line`, EN + ES); the parent
 * dashboard shows the sentences on the game's row and counts the skills in the weekly report.
 *
 * Covers GameAudienceTable (kids + family tags) and the kid games that set their own audience on their spec.
 * A new kid-visible game needs a row here; GameTeachesTest fails until it has one.
 */
object GameTeaches {
    private val table: Map<String, List<Skill>> = buildMap {
        fun t(id: String, a: Skill, b: Skill? = null) { put(id, if (b == null) listOf(a) else listOf(a, b)) }
        // Tap / reaction party games
        t("tap-race", Skill.FINE_MOTOR, Skill.TURN_TAKING); t("tug-of-war", Skill.FINE_MOTOR, Skill.TEAMWORK)
        t("whack-a-mole", Skill.FINE_MOTOR); t("matching", Skill.MEMORY)
        t("reaction-duel", Skill.FINE_MOTOR); t("sumo-push", Skill.FINE_MOTOR, Skill.PLANNING)
        t("paddle-duel", Skill.FINE_MOTOR); t("rps-showdown", Skill.TURN_TAKING, Skill.DEDUCTION)
        t("light-trail", Skill.PLANNING, Skill.FINE_MOTOR)
        // Simple two-player boards
        t("tic-tac-toe", Skill.PLANNING, Skill.TURN_TAKING); t("connect-four", Skill.PLANNING, Skill.TURN_TAKING)
        t("mancala", Skill.COUNTING, Skill.PLANNING); t("dots-and-boxes", Skill.PLANNING, Skill.COUNTING)
        t("checkers", Skill.PLANNING, Skill.TURN_TAKING); t("hare-and-hounds", Skill.PLANNING, Skill.DEDUCTION)
        t("ludo", Skill.COUNTING, Skill.TURN_TAKING)
        // Single-player puzzles
        t("sliding-puzzle", Skill.PLANNING); t("color-flood", Skill.PLANNING); t("flow-lines", Skill.PLANNING, Skill.FINE_MOTOR)
        t("snake", Skill.FINE_MOTOR, Skill.PLANNING); t("game-2048", Skill.COUNTING, Skill.PLANNING)
        t("breakout", Skill.FINE_MOTOR)
        // Toy sports and fairground games
        t("darts", Skill.FINE_MOTOR, Skill.COUNTING); t("bowling", Skill.FINE_MOTOR, Skill.COUNTING)
        t("mini-golf", Skill.FINE_MOTOR, Skill.PLANNING); t("toy-soccer", Skill.FINE_MOTOR, Skill.TURN_TAKING)
        t("fishing", Skill.FINE_MOTOR); t("shooting-gallery", Skill.FINE_MOTOR, Skill.COUNTING)
        t("air-hockey", Skill.FINE_MOTOR); t("toy-tennis", Skill.FINE_MOTOR, Skill.TURN_TAKING)
        t("toy-baseball", Skill.FINE_MOTOR, Skill.TURN_TAKING)
        // Family strategy, cards and logic
        t("chess", Skill.PLANNING, Skill.TURN_TAKING); t("reversi", Skill.PLANNING, Skill.COUNTING)
        t("gomoku", Skill.PLANNING); t("hex", Skill.PLANNING); t("nine-mens-morris", Skill.PLANNING, Skill.TURN_TAKING)
        t("chinese-checkers", Skill.PLANNING, Skill.TURN_TAKING); t("dominoes", Skill.COUNTING, Skill.PLANNING)
        t("uno", Skill.TURN_TAKING, Skill.PLANNING); t("war", Skill.COUNTING, Skill.TURN_TAKING)
        t("sevens", Skill.COUNTING, Skill.PLANNING); t("speed", Skill.COUNTING, Skill.FINE_MOTOR)
        t("solitaire", Skill.PLANNING, Skill.COUNTING); t("spider-solitaire", Skill.PLANNING, Skill.COUNTING)
        t("president", Skill.PLANNING, Skill.TURN_TAKING); t("yahtzee", Skill.COUNTING, Skill.PLANNING)
        t("mahjong-solitaire", Skill.MEMORY, Skill.PLANNING); t("party-toolkit", Skill.TURN_TAKING, Skill.COUNTING)
        t("curling", Skill.FINE_MOTOR, Skill.PLANNING); t("carrom", Skill.FINE_MOTOR, Skill.PLANNING)
        t("billiards", Skill.FINE_MOTOR, Skill.PLANNING); t("six-ball-puzzle", Skill.PLANNING, Skill.DEDUCTION)
        t("toy-boxing", Skill.FINE_MOTOR, Skill.TURN_TAKING); t("tank-battle", Skill.PLANNING, Skill.FINE_MOTOR)
        t("tower-defence", Skill.PLANNING, Skill.COUNTING); t("shogi", Skill.PLANNING); t("mini-shogi", Skill.PLANNING)
        t("sudoku", Skill.DEDUCTION, Skill.COUNTING); t("minesweeper", Skill.DEDUCTION, Skill.COUNTING)
        t("lights-out", Skill.DEDUCTION, Skill.PLANNING); t("mastermind", Skill.DEDUCTION, Skill.MEMORY)
        t("nonogram", Skill.DEDUCTION, Skill.COUNTING); t("edge-match", Skill.PLANNING, Skill.DEDUCTION)
        t("kenken", Skill.COUNTING, Skill.DEDUCTION)
        // The kid games that tag themselves on their own spec
        t("chutes-race", Skill.COUNTING, Skill.TURN_TAKING); t("echo-pads", Skill.MEMORY, Skill.FINE_MOTOR)
        t("bridge-builders", Skill.PLANNING, Skill.TEAMWORK); t("draw-guess", Skill.CREATIVITY, Skill.LANGUAGE)
        t("act-it-out", Skill.CREATIVITY, Skill.TEAMWORK)
        t("sweet-trail", Skill.COUNTING, Skill.TURN_TAKING); t("trail-together", Skill.TEAMWORK, Skill.PLANNING)
        t("find-it", Skill.DEDUCTION, Skill.LANGUAGE); t("pie-detective", Skill.DEDUCTION, Skill.LANGUAGE)
        t("stack-zoo", Skill.FINE_MOTOR, Skill.PLANNING); t("flick-rink", Skill.FINE_MOTOR, Skill.TURN_TAKING)
        t("bump-run", Skill.FINE_MOTOR, Skill.PLANNING); t("egg-garden", Skill.MEMORY, Skill.COUNTING)
        t("life-road", Skill.PLANNING, Skill.COUNTING)
    }

    /** The skills [gameId] practises (one or two, main skill first); empty for a game that is not in the table. */
    fun skillsFor(gameId: String): List<Skill> = table[gameId].orEmpty()

    fun covers(gameId: String): Boolean = gameId in table

    val ids: Set<String> get() = table.keys
}

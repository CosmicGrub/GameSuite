package com.gamesuite.core

import androidx.annotation.StringRes
import com.gamesuite.R

/** The small animated diagram shown above a game's steps (ui/club/CoachCard.kt draws each one). */
enum class CoachMotif {
    /** A piece hops across a chequered board (Chess, Checkers, Shogi, Reversi...). */
    BOARD_MOVE,
    /** Marks drop into a grid until a line of three/four/five lights up. */
    LINE_UP,
    /** Stones sown one by one along a row of pits. */
    SOW,
    /** A fan of cards with one lifting out. */
    CARDS,
    /** Lettered tiles sliding into a word. */
    LETTERS,
    /** A numbered grid filling in. */
    NUMBERS,
    /** Dice tumbling. */
    DICE,
    /** Tiles that match and vanish, or slide together. */
    TILES,
    /** A paddle bouncing a ball. */
    BALL,
    /** Dots joined by a drawn path. */
    PATH
}

/** One game's coaching card: [steps] (3 to 6) and one trivia [fact], as string resource ids. */
class CoachEntry(
    val gameId: String,
    @StringRes val steps: List<Int>,
    @StringRes val fact: Int,
    val motif: CoachMotif
)

/**
 * "How to play" and "Did you know" for every game (docs/roadmap/CLUBHOUSE-PARITY.md, Club layer).
 * Text lives in res/values{,-es}/coach_strings.xml under `coach_<id with _>_s1..sN` and `_fact`;
 * GameCoachTest keeps every Hub game, in both locales, covered. [upcomingIds] are games not yet in
 * GameHubSpecs, covered ahead of time so wiring a new game's card is free.
 */
object GameCoach {
    val upcomingIds: List<String> = listOf(
        "gomoku", "hex", "nine-mens-morris", "hare-and-hounds", "chinese-checkers", "ludo", "shogi", "mini-shogi"
    )

    /**
     * Games whose own screen already shows a first-run coach card with its own "seen" flag. The Hub
     * does not auto-open the Club coach for these (the (?) button still opens it on demand), so a
     * player never gets two cards for one game. Chinese Checkers and Ludo are not listed: their
     * screens use the Club card and ClubProfile.coachSeen, the same flag the Hub marks.
     */
    val ownCoachInGame: Set<String> = setOf(
        "gomoku", "hex", "nine-mens-morris", "hare-and-hounds", "shogi", "mini-shogi",
        "paddle-duel", "rps-showdown"
    )

    /** Whether the Hub should open the coach by itself on a first visit to [gameId]. */
    fun autoOpensInHub(gameId: String): Boolean = gameId in entries && gameId !in ownCoachInGame

    fun forGame(gameId: String): CoachEntry? = entries[gameId]

    val ids: Set<String> get() = entries.keys

    /** The resource name a game's strings use: "coach_" + id with dashes as underscores. */
    fun stepName(gameId: String, n: Int): String = "coach_${gameId.replace('-', '_')}_s$n"
    fun factName(gameId: String): String = "coach_${gameId.replace('-', '_')}_fact"

    private val motifs: Map<String, CoachMotif> = mapOf(
        "chess" to CoachMotif.BOARD_MOVE, "checkers" to CoachMotif.BOARD_MOVE, "reversi" to CoachMotif.BOARD_MOVE,
        "backgammon" to CoachMotif.BOARD_MOVE, "shogi" to CoachMotif.BOARD_MOVE, "mini-shogi" to CoachMotif.BOARD_MOVE,
        "chinese-checkers" to CoachMotif.BOARD_MOVE, "hare-and-hounds" to CoachMotif.BOARD_MOVE,
        "nine-mens-morris" to CoachMotif.BOARD_MOVE, "ludo" to CoachMotif.DICE,
        "tic-tac-toe" to CoachMotif.LINE_UP, "connect-four" to CoachMotif.LINE_UP, "gomoku" to CoachMotif.LINE_UP,
        "hex" to CoachMotif.LINE_UP, "mastermind" to CoachMotif.LINE_UP, "dots-and-boxes" to CoachMotif.PATH,
        "mancala" to CoachMotif.SOW,
        "uno" to CoachMotif.CARDS, "solitaire" to CoachMotif.CARDS,
        "blackjack" to CoachMotif.CARDS, "war" to CoachMotif.CARDS, "texas-holdem" to CoachMotif.CARDS,
        "president" to CoachMotif.CARDS, "sevens" to CoachMotif.CARDS, "speed" to CoachMotif.CARDS,
        "matching" to CoachMotif.TILES,
        "darts" to CoachMotif.BALL, "bowling" to CoachMotif.BALL, "carrom" to CoachMotif.BALL, "curling" to CoachMotif.PATH, "billiards" to CoachMotif.BALL, "mini-golf" to CoachMotif.BALL, "toy-soccer" to CoachMotif.BALL, "toy-tennis" to CoachMotif.BALL, "toy-boxing" to CoachMotif.BALL, "toy-baseball" to CoachMotif.BALL,
        "slot-cars" to CoachMotif.PATH, "fishing" to CoachMotif.BALL, "tank-battle" to CoachMotif.PATH, "shooting-gallery" to CoachMotif.BALL, "six-ball-puzzle" to CoachMotif.BALL, "pig-dice" to CoachMotif.DICE,
        "tap-race" to CoachMotif.PATH, "tug-of-war" to CoachMotif.BALL, "reaction-duel" to CoachMotif.BALL, "whack-a-mole" to CoachMotif.BALL, "light-trail" to CoachMotif.PATH, "sumo-push" to CoachMotif.BALL, "paddle-duel" to CoachMotif.BALL, "rps-showdown" to CoachMotif.CARDS, "spider-solitaire" to CoachMotif.CARDS, "hanafuda" to CoachMotif.CARDS,
        "word-tiles" to CoachMotif.LETTERS, "hangman" to CoachMotif.LETTERS, "word-search" to CoachMotif.LETTERS,
        "crossword" to CoachMotif.LETTERS, "word-guess" to CoachMotif.LETTERS,
        "sudoku" to CoachMotif.NUMBERS, "kenken" to CoachMotif.NUMBERS, "kakuro" to CoachMotif.NUMBERS,
        "nonogram" to CoachMotif.NUMBERS, "minesweeper" to CoachMotif.NUMBERS, "game-2048" to CoachMotif.NUMBERS,
        "yahtzee" to CoachMotif.DICE, "party-toolkit" to CoachMotif.DICE,
        "mahjong-solitaire" to CoachMotif.TILES, "dominoes" to CoachMotif.TILES, "lights-out" to CoachMotif.TILES,
        "sliding-puzzle" to CoachMotif.TILES, "color-flood" to CoachMotif.TILES, "edge-match" to CoachMotif.TILES,
        "breakout" to CoachMotif.BALL, "air-hockey" to CoachMotif.BALL, "tower-defence" to CoachMotif.PATH,
        "snake" to CoachMotif.PATH, "flow-lines" to CoachMotif.PATH,
        "chutes-race" to CoachMotif.DICE, "draw-guess" to CoachMotif.PATH, "echo-pads" to CoachMotif.TILES,
        "act-it-out" to CoachMotif.CARDS, "bridge-builders" to CoachMotif.PATH
    )

    private fun entry(id: String, steps: List<Int>, fact: Int) =
        id to CoachEntry(id, steps, fact, motifs[id] ?: CoachMotif.BOARD_MOVE)

    private val entries: Map<String, CoachEntry> = mapOf(
        entry("chess", listOf(R.string.coach_chess_s1, R.string.coach_chess_s2, R.string.coach_chess_s3, R.string.coach_chess_s4), R.string.coach_chess_fact),
        entry("checkers", listOf(R.string.coach_checkers_s1, R.string.coach_checkers_s2, R.string.coach_checkers_s3, R.string.coach_checkers_s4), R.string.coach_checkers_fact),
        entry("reversi", listOf(R.string.coach_reversi_s1, R.string.coach_reversi_s2, R.string.coach_reversi_s3, R.string.coach_reversi_s4), R.string.coach_reversi_fact),
        entry("mancala", listOf(R.string.coach_mancala_s1, R.string.coach_mancala_s2, R.string.coach_mancala_s3, R.string.coach_mancala_s4), R.string.coach_mancala_fact),
        entry("connect-four", listOf(R.string.coach_connect_four_s1, R.string.coach_connect_four_s2, R.string.coach_connect_four_s3, R.string.coach_connect_four_s4), R.string.coach_connect_four_fact),
        entry("dots-and-boxes", listOf(R.string.coach_dots_and_boxes_s1, R.string.coach_dots_and_boxes_s2, R.string.coach_dots_and_boxes_s3, R.string.coach_dots_and_boxes_s4), R.string.coach_dots_and_boxes_fact),
        entry("tic-tac-toe", listOf(R.string.coach_tic_tac_toe_s1, R.string.coach_tic_tac_toe_s2, R.string.coach_tic_tac_toe_s3, R.string.coach_tic_tac_toe_s4), R.string.coach_tic_tac_toe_fact),
        entry("dominoes", listOf(R.string.coach_dominoes_s1, R.string.coach_dominoes_s2, R.string.coach_dominoes_s3, R.string.coach_dominoes_s4), R.string.coach_dominoes_fact),
        entry("word-tiles", listOf(R.string.coach_word_tiles_s1, R.string.coach_word_tiles_s2, R.string.coach_word_tiles_s3, R.string.coach_word_tiles_s4), R.string.coach_word_tiles_fact),
        entry("uno", listOf(R.string.coach_uno_s1, R.string.coach_uno_s2, R.string.coach_uno_s3, R.string.coach_uno_s4), R.string.coach_uno_fact),
        entry("tap-race", listOf(R.string.coach_tap_race_s1, R.string.coach_tap_race_s2, R.string.coach_tap_race_s3, R.string.coach_tap_race_s4, R.string.coach_tap_race_s5), R.string.coach_tap_race_fact),
        entry("tug-of-war", listOf(R.string.coach_tug_of_war_s1, R.string.coach_tug_of_war_s2, R.string.coach_tug_of_war_s3, R.string.coach_tug_of_war_s4, R.string.coach_tug_of_war_s5), R.string.coach_tug_of_war_fact),
        entry("reaction-duel", listOf(R.string.coach_reaction_duel_s1, R.string.coach_reaction_duel_s2, R.string.coach_reaction_duel_s3, R.string.coach_reaction_duel_s4, R.string.coach_reaction_duel_s5), R.string.coach_reaction_duel_fact),
        entry("whack-a-mole", listOf(R.string.coach_whack_a_mole_s1, R.string.coach_whack_a_mole_s2, R.string.coach_whack_a_mole_s3, R.string.coach_whack_a_mole_s4, R.string.coach_whack_a_mole_s5), R.string.coach_whack_a_mole_fact),
        entry("light-trail", listOf(R.string.coach_light_trail_s1, R.string.coach_light_trail_s2, R.string.coach_light_trail_s3, R.string.coach_light_trail_s4, R.string.coach_light_trail_s5), R.string.coach_light_trail_fact),
        entry("sumo-push", listOf(R.string.coach_sumo_push_s1, R.string.coach_sumo_push_s2, R.string.coach_sumo_push_s3, R.string.coach_sumo_push_s4, R.string.coach_sumo_push_s5), R.string.coach_sumo_push_fact),
        entry("paddle-duel", listOf(R.string.coach_paddle_duel_s1, R.string.coach_paddle_duel_s2, R.string.coach_paddle_duel_s3, R.string.coach_paddle_duel_s4, R.string.coach_paddle_duel_s5), R.string.coach_paddle_duel_fact),
        entry("rps-showdown", listOf(R.string.coach_rps_showdown_s1, R.string.coach_rps_showdown_s2, R.string.coach_rps_showdown_s3, R.string.coach_rps_showdown_s4, R.string.coach_rps_showdown_s5), R.string.coach_rps_showdown_fact),
        entry("slot-cars", listOf(R.string.coach_slot_cars_s1, R.string.coach_slot_cars_s2, R.string.coach_slot_cars_s3, R.string.coach_slot_cars_s4, R.string.coach_slot_cars_s5), R.string.coach_slot_cars_fact),
        entry("fishing", listOf(R.string.coach_fishing_s1, R.string.coach_fishing_s2, R.string.coach_fishing_s3, R.string.coach_fishing_s4, R.string.coach_fishing_s5), R.string.coach_fishing_fact),
        entry("tank-battle", listOf(R.string.coach_tank_battle_s1, R.string.coach_tank_battle_s2, R.string.coach_tank_battle_s3, R.string.coach_tank_battle_s4, R.string.coach_tank_battle_s5), R.string.coach_tank_battle_fact),
        entry("shooting-gallery", listOf(R.string.coach_shooting_gallery_s1, R.string.coach_shooting_gallery_s2, R.string.coach_shooting_gallery_s3, R.string.coach_shooting_gallery_s4, R.string.coach_shooting_gallery_s5), R.string.coach_shooting_gallery_fact),
        entry("six-ball-puzzle", listOf(R.string.coach_six_ball_puzzle_s1, R.string.coach_six_ball_puzzle_s2, R.string.coach_six_ball_puzzle_s3, R.string.coach_six_ball_puzzle_s4, R.string.coach_six_ball_puzzle_s5), R.string.coach_six_ball_puzzle_fact),
        entry("pig-dice", listOf(R.string.coach_pig_dice_s1, R.string.coach_pig_dice_s2, R.string.coach_pig_dice_s3, R.string.coach_pig_dice_s4, R.string.coach_pig_dice_s5), R.string.coach_pig_dice_fact),
        entry("darts", listOf(R.string.coach_darts_s1, R.string.coach_darts_s2, R.string.coach_darts_s3, R.string.coach_darts_s4, R.string.coach_darts_s5), R.string.coach_darts_fact),
        entry("bowling", listOf(R.string.coach_bowling_s1, R.string.coach_bowling_s2, R.string.coach_bowling_s3, R.string.coach_bowling_s4, R.string.coach_bowling_s5), R.string.coach_bowling_fact),
        entry("carrom", listOf(R.string.coach_carrom_s1, R.string.coach_carrom_s2, R.string.coach_carrom_s3, R.string.coach_carrom_s4, R.string.coach_carrom_s5), R.string.coach_carrom_fact),
        entry("curling", listOf(R.string.coach_curling_s1, R.string.coach_curling_s2, R.string.coach_curling_s3, R.string.coach_curling_s4, R.string.coach_curling_s5), R.string.coach_curling_fact),
        entry("billiards", listOf(R.string.coach_billiards_s1, R.string.coach_billiards_s2, R.string.coach_billiards_s3, R.string.coach_billiards_s4, R.string.coach_billiards_s5), R.string.coach_billiards_fact),
        entry("mini-golf", listOf(R.string.coach_mini_golf_s1, R.string.coach_mini_golf_s2, R.string.coach_mini_golf_s3, R.string.coach_mini_golf_s4), R.string.coach_mini_golf_fact),
        entry("toy-soccer", listOf(R.string.coach_toy_soccer_s1, R.string.coach_toy_soccer_s2, R.string.coach_toy_soccer_s3, R.string.coach_toy_soccer_s4, R.string.coach_toy_soccer_s5), R.string.coach_toy_soccer_fact),
        entry("toy-tennis", listOf(R.string.coach_toy_tennis_s1, R.string.coach_toy_tennis_s2, R.string.coach_toy_tennis_s3, R.string.coach_toy_tennis_s4, R.string.coach_toy_tennis_s5), R.string.coach_toy_tennis_fact),
        entry("toy-boxing", listOf(R.string.coach_toy_boxing_s1, R.string.coach_toy_boxing_s2, R.string.coach_toy_boxing_s3, R.string.coach_toy_boxing_s4, R.string.coach_toy_boxing_s5), R.string.coach_toy_boxing_fact),
        entry("toy-baseball", listOf(R.string.coach_toy_baseball_s1, R.string.coach_toy_baseball_s2, R.string.coach_toy_baseball_s3, R.string.coach_toy_baseball_s4, R.string.coach_toy_baseball_s5), R.string.coach_toy_baseball_fact),
        entry("blackjack", listOf(R.string.coach_blackjack_s1, R.string.coach_blackjack_s2, R.string.coach_blackjack_s3, R.string.coach_blackjack_s4, R.string.coach_blackjack_s5), R.string.coach_blackjack_fact),
        entry("war", listOf(R.string.coach_war_s1, R.string.coach_war_s2, R.string.coach_war_s3, R.string.coach_war_s4), R.string.coach_war_fact),
        entry("texas-holdem", listOf(R.string.coach_texas_holdem_s1, R.string.coach_texas_holdem_s2, R.string.coach_texas_holdem_s3, R.string.coach_texas_holdem_s4, R.string.coach_texas_holdem_s5), R.string.coach_texas_holdem_fact),
        entry("president", listOf(R.string.coach_president_s1, R.string.coach_president_s2, R.string.coach_president_s3, R.string.coach_president_s4, R.string.coach_president_s5), R.string.coach_president_fact),
        entry("sevens", listOf(R.string.coach_sevens_s1, R.string.coach_sevens_s2, R.string.coach_sevens_s3, R.string.coach_sevens_s4, R.string.coach_sevens_s5), R.string.coach_sevens_fact),
        entry("speed", listOf(R.string.coach_speed_s1, R.string.coach_speed_s2, R.string.coach_speed_s3, R.string.coach_speed_s4), R.string.coach_speed_fact),
        entry("matching", listOf(R.string.coach_matching_s1, R.string.coach_matching_s2, R.string.coach_matching_s3, R.string.coach_matching_s4), R.string.coach_matching_fact),
        entry("spider-solitaire", listOf(R.string.coach_spider_solitaire_s1, R.string.coach_spider_solitaire_s2, R.string.coach_spider_solitaire_s3, R.string.coach_spider_solitaire_s4, R.string.coach_spider_solitaire_s5), R.string.coach_spider_solitaire_fact),
        entry("hanafuda", listOf(R.string.coach_hanafuda_s1, R.string.coach_hanafuda_s2, R.string.coach_hanafuda_s3, R.string.coach_hanafuda_s4, R.string.coach_hanafuda_s5, R.string.coach_hanafuda_s6), R.string.coach_hanafuda_fact),
        entry("solitaire", listOf(R.string.coach_solitaire_s1, R.string.coach_solitaire_s2, R.string.coach_solitaire_s3, R.string.coach_solitaire_s4), R.string.coach_solitaire_fact),
        entry("hangman", listOf(R.string.coach_hangman_s1, R.string.coach_hangman_s2, R.string.coach_hangman_s3, R.string.coach_hangman_s4), R.string.coach_hangman_fact),
        entry("word-search", listOf(R.string.coach_word_search_s1, R.string.coach_word_search_s2, R.string.coach_word_search_s3, R.string.coach_word_search_s4), R.string.coach_word_search_fact),
        entry("crossword", listOf(R.string.coach_crossword_s1, R.string.coach_crossword_s2, R.string.coach_crossword_s3, R.string.coach_crossword_s4), R.string.coach_crossword_fact),
        entry("word-guess", listOf(R.string.coach_word_guess_s1, R.string.coach_word_guess_s2, R.string.coach_word_guess_s3, R.string.coach_word_guess_s4), R.string.coach_word_guess_fact),
        entry("sudoku", listOf(R.string.coach_sudoku_s1, R.string.coach_sudoku_s2, R.string.coach_sudoku_s3, R.string.coach_sudoku_s4), R.string.coach_sudoku_fact),
        entry("kenken", listOf(R.string.coach_kenken_s1, R.string.coach_kenken_s2, R.string.coach_kenken_s3, R.string.coach_kenken_s4), R.string.coach_kenken_fact),
        entry("kakuro", listOf(R.string.coach_kakuro_s1, R.string.coach_kakuro_s2, R.string.coach_kakuro_s3, R.string.coach_kakuro_s4), R.string.coach_kakuro_fact),
        entry("nonogram", listOf(R.string.coach_nonogram_s1, R.string.coach_nonogram_s2, R.string.coach_nonogram_s3, R.string.coach_nonogram_s4), R.string.coach_nonogram_fact),
        entry("minesweeper", listOf(R.string.coach_minesweeper_s1, R.string.coach_minesweeper_s2, R.string.coach_minesweeper_s3, R.string.coach_minesweeper_s4), R.string.coach_minesweeper_fact),
        entry("lights-out", listOf(R.string.coach_lights_out_s1, R.string.coach_lights_out_s2, R.string.coach_lights_out_s3, R.string.coach_lights_out_s4), R.string.coach_lights_out_fact),
        entry("sliding-puzzle", listOf(R.string.coach_sliding_puzzle_s1, R.string.coach_sliding_puzzle_s2, R.string.coach_sliding_puzzle_s3, R.string.coach_sliding_puzzle_s4), R.string.coach_sliding_puzzle_fact),
        entry("color-flood", listOf(R.string.coach_color_flood_s1, R.string.coach_color_flood_s2, R.string.coach_color_flood_s3, R.string.coach_color_flood_s4), R.string.coach_color_flood_fact),
        entry("edge-match", listOf(R.string.coach_edge_match_s1, R.string.coach_edge_match_s2, R.string.coach_edge_match_s3, R.string.coach_edge_match_s4), R.string.coach_edge_match_fact),
        entry("mastermind", listOf(R.string.coach_mastermind_s1, R.string.coach_mastermind_s2, R.string.coach_mastermind_s3, R.string.coach_mastermind_s4), R.string.coach_mastermind_fact),
        entry("breakout", listOf(R.string.coach_breakout_s1, R.string.coach_breakout_s2, R.string.coach_breakout_s3, R.string.coach_breakout_s4), R.string.coach_breakout_fact),
        entry("tower-defence", listOf(R.string.coach_tower_defence_s1, R.string.coach_tower_defence_s2, R.string.coach_tower_defence_s3, R.string.coach_tower_defence_s4), R.string.coach_tower_defence_fact),
        entry("air-hockey", listOf(R.string.coach_air_hockey_s1, R.string.coach_air_hockey_s2, R.string.coach_air_hockey_s3, R.string.coach_air_hockey_s4), R.string.coach_air_hockey_fact),
        entry("yahtzee", listOf(R.string.coach_yahtzee_s1, R.string.coach_yahtzee_s2, R.string.coach_yahtzee_s3, R.string.coach_yahtzee_s4), R.string.coach_yahtzee_fact),
        entry("backgammon", listOf(R.string.coach_backgammon_s1, R.string.coach_backgammon_s2, R.string.coach_backgammon_s3, R.string.coach_backgammon_s4), R.string.coach_backgammon_fact),
        entry("game-2048", listOf(R.string.coach_game_2048_s1, R.string.coach_game_2048_s2, R.string.coach_game_2048_s3, R.string.coach_game_2048_s4), R.string.coach_game_2048_fact),
        entry("snake", listOf(R.string.coach_snake_s1, R.string.coach_snake_s2, R.string.coach_snake_s3, R.string.coach_snake_s4), R.string.coach_snake_fact),
        entry("flow-lines", listOf(R.string.coach_flow_lines_s1, R.string.coach_flow_lines_s2, R.string.coach_flow_lines_s3, R.string.coach_flow_lines_s4), R.string.coach_flow_lines_fact),
        entry("mahjong-solitaire", listOf(R.string.coach_mahjong_solitaire_s1, R.string.coach_mahjong_solitaire_s2, R.string.coach_mahjong_solitaire_s3, R.string.coach_mahjong_solitaire_s4), R.string.coach_mahjong_solitaire_fact),
        entry("party-toolkit", listOf(R.string.coach_party_toolkit_s1, R.string.coach_party_toolkit_s2, R.string.coach_party_toolkit_s3, R.string.coach_party_toolkit_s4), R.string.coach_party_toolkit_fact),
        entry("gomoku", listOf(R.string.coach_gomoku_s1, R.string.coach_gomoku_s2, R.string.coach_gomoku_s3, R.string.coach_gomoku_s4), R.string.coach_gomoku_fact),
        entry("hex", listOf(R.string.coach_hex_s1, R.string.coach_hex_s2, R.string.coach_hex_s3, R.string.coach_hex_s4), R.string.coach_hex_fact),
        entry("nine-mens-morris", listOf(R.string.coach_nine_mens_morris_s1, R.string.coach_nine_mens_morris_s2, R.string.coach_nine_mens_morris_s3, R.string.coach_nine_mens_morris_s4), R.string.coach_nine_mens_morris_fact),
        entry("hare-and-hounds", listOf(R.string.coach_hare_and_hounds_s1, R.string.coach_hare_and_hounds_s2, R.string.coach_hare_and_hounds_s3, R.string.coach_hare_and_hounds_s4), R.string.coach_hare_and_hounds_fact),
        entry("chinese-checkers", listOf(R.string.coach_chinese_checkers_s1, R.string.coach_chinese_checkers_s2, R.string.coach_chinese_checkers_s3, R.string.coach_chinese_checkers_s4), R.string.coach_chinese_checkers_fact),
        entry("ludo", listOf(R.string.coach_ludo_s1, R.string.coach_ludo_s2, R.string.coach_ludo_s3, R.string.coach_ludo_s4), R.string.coach_ludo_fact),
        entry("shogi", listOf(R.string.coach_shogi_s1, R.string.coach_shogi_s2, R.string.coach_shogi_s3, R.string.coach_shogi_s4), R.string.coach_shogi_fact),
        entry("mini-shogi", listOf(R.string.coach_mini_shogi_s1, R.string.coach_mini_shogi_s2, R.string.coach_mini_shogi_s3, R.string.coach_mini_shogi_s4), R.string.coach_mini_shogi_fact),
        entry("chutes-race", listOf(R.string.coach_chutes_race_s1, R.string.coach_chutes_race_s2, R.string.coach_chutes_race_s3, R.string.coach_chutes_race_s4, R.string.coach_chutes_race_s5), R.string.coach_chutes_race_fact),
        entry("draw-guess", listOf(R.string.coach_draw_guess_s1, R.string.coach_draw_guess_s2, R.string.coach_draw_guess_s3, R.string.coach_draw_guess_s4, R.string.coach_draw_guess_s5), R.string.coach_draw_guess_fact),
        entry("echo-pads", listOf(R.string.coach_echo_pads_s1, R.string.coach_echo_pads_s2, R.string.coach_echo_pads_s3, R.string.coach_echo_pads_s4), R.string.coach_echo_pads_fact),
        entry("act-it-out", listOf(R.string.coach_act_it_out_s1, R.string.coach_act_it_out_s2, R.string.coach_act_it_out_s3, R.string.coach_act_it_out_s4, R.string.coach_act_it_out_s5), R.string.coach_act_it_out_fact),
        entry("bridge-builders", listOf(R.string.coach_bridge_builders_s1, R.string.coach_bridge_builders_s2, R.string.coach_bridge_builders_s3, R.string.coach_bridge_builders_s4, R.string.coach_bridge_builders_s5), R.string.coach_bridge_builders_fact),
    )
}

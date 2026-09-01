package com.gamesuite.games.hangman

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

data class HangmanState(
    val word: String,
    val guessedLetters: Set<Char>,
    val wrongGuesses: Int,
    val maxWrongGuesses: Int,
    val matchOver: Boolean = false,
    val won: Boolean = false
) {
    val revealedWord: String get() = word.map { if (it in guessedLetters) it else '_' }.joinToString(" ")
    val remainingGuesses: Int get() = maxWrongGuesses - wrongGuesses
}

/**
 * Single-device Hangman: one player picks/types a word (or a random one is
 * chosen from a small built-in list), the other guesses letters. Proves the
 * shell handles a very different UI/input shape (letter buttons, not a
 * board) with the same GameModule contract as Tic-Tac-Toe and UNO.
 *
 * Research pass (README item 9f): a solo word-guessing puzzle has no
 * opponent to make "smarter" or "dumber" — the honest equivalent of a CPU
 * difficulty ladder here is the word itself, so [difficulty] picks which
 * curated pool the word is drawn from (EASY: short, everyday vocabulary;
 * MEDIUM: longer but still common; HARD: long and/or specialized). Guess
 * count is deliberately left fixed at the classic 6 across all three —
 * varying it too would double up with the word-pool change as the
 * difficulty lever and muddy which one actually made a given round harder.
 */
class HangmanGame : GameModule {
    override val gameId = "hangman"
    override val displayName = "Hangman"
    override val category = GameCategory.WORD
    override val minPlayers = 1
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<HangmanState?>(null)
    val wins = mutableStateOf(0)
    val losses = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-word. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    private val wordBank: Map<CpuDifficulty, List<String>> = mapOf(
        CpuDifficulty.EASY to listOf(
            "APPLE", "HOUSE", "TIGER", "BEACH", "CHAIR", "SMILE", "TABLE", "MUSIC",
            "WATER", "HAPPY", "ROBOT", "PLANT", "TRAIN", "CANDY", "STORM"
        ),
        CpuDifficulty.MEDIUM to listOf(
            "ANDROID", "KOTLIN", "COMPOSE", "TABLET", "PUZZLE", "ARCADE", "HOCKEY",
            "JOURNEY", "FESTIVAL", "SANDWICH", "MOUNTAIN", "ELEPHANT", "CHEMISTRY",
            "VOLCANO", "GUITAR"
        ),
        CpuDifficulty.HARD to listOf(
            "FOLDABLE", "MULTIPLAYER", "DOMINOES", "MANCALA", "CROSSWORD",
            "XYLOPHONE", "QUARANTINE", "RHYTHM", "SYNCHRONIZE", "ASTRONAUT",
            "LABYRINTH", "PNEUMONIA", "HANDKERCHIEF", "BUREAUCRACY"
        )
    )

    override fun init(context: GameContext) {
        this.context = context
        wins.value = 0
        losses.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        state.value = HangmanState(
            word = (wordBank[difficulty] ?: wordBank.getValue(CpuDifficulty.MEDIUM)).random(),
            guessedLetters = emptySet(),
            wrongGuesses = 0,
            maxWrongGuesses = 6
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun guessLetter(letter: Char) {
        val s = state.value ?: return
        if (s.matchOver || letter in s.guessedLetters) return

        val newGuessed = s.guessedLetters + letter
        val correct = letter in s.word
        val newWrong = if (correct) s.wrongGuesses else s.wrongGuesses + 1

        val won = s.word.all { it in newGuessed }
        val lost = newWrong >= s.maxWrongGuesses

        state.value = s.copy(guessedLetters = newGuessed, wrongGuesses = newWrong)

        if (won || lost) {
            if (won) wins.value += 1 else losses.value += 1
            state.value = state.value?.copy(matchOver = true, won = won)
        }
    }

    /** Called from the round-over panel's "New Word" button — keeps the running score. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the round-over panel's "Back to Menu" button — ends the whole session. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(playerId = player.playerId, score = wins.value, isWinner = wins.value > losses.value)
            ) else emptyList()
        )
        endMatch(result)
    }
}

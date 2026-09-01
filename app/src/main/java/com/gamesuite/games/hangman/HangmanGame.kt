package com.gamesuite.games.hangman

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*

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

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    private val wordBank = listOf(
        "ANDROID", "KOTLIN", "COMPOSE", "FOLDABLE", "TABLET", "MULTIPLAYER",
        "DOMINOES", "MANCALA", "CROSSWORD", "PUZZLE", "ARCADE", "HOCKEY"
    )

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        state.value = HangmanState(
            word = wordBank.random(),
            guessedLetters = emptySet(),
            wrongGuesses = 0,
            maxWrongGuesses = 6
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value?.copy(matchOver = true)
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
            val player = context.players.getOrNull(context.localPlayerIndex)
            val result = GameResult(
                scores = if (player != null) listOf(
                    PlayerScore(playerId = player.playerId, score = if (won) 1 else 0, isWinner = won)
                ) else emptyList()
            )
            state.value = state.value?.copy(matchOver = true, won = won)
            endMatch(result)
        }
    }
}

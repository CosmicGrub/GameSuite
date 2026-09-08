package com.gamesuite.games.hangman

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.wordgames.WordDictionary
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
 *
 * Variety pass: [pickWord] now excludes [recentWords] (the last
 * [WORD_HISTORY_SIZE] words played this session, in-memory only) from the
 * random draw, so mashing "New Word" doesn't keep re-serving the same word
 * out of these small pools — it falls back to allowing a repeat only once
 * excluding history would leave nothing to pick from.
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

    /**
     * Last few words played (most recent last), across "New Word" taps this
     * session — excluded from [pickWord]'s draw so the same word doesn't keep
     * coming back out of these small pools. In-memory only, cleared on
     * [init]; see [WORD_HISTORY_SIZE].
     */
    private val recentWords = ArrayDeque<String>()

    /**
     * Small hand-picked "flavor" words per tier — kept (not replaced) so the pool still reads as
     * curated rather than a raw dictionary dump, and as a working fallback if [loadDictionary]
     * hasn't run yet (e.g. a preview/test context).
     */
    private val curatedWords: Map<CpuDifficulty, List<String>> = mapOf(
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

    /**
     * Word lengths sampled from [WordDictionary] for each tier, picked to sit in the same rough
     * range as that tier's own [curatedWords] so the dictionary additions read as the same
     * difficulty as the words they're mixed in with, not a random jump in obscurity.
     */
    private val dictionaryLengthsByDifficulty: Map<CpuDifficulty, IntRange> = mapOf(
        CpuDifficulty.EASY to 4..5,
        CpuDifficulty.MEDIUM to 6..8,
        CpuDifficulty.HARD to 9..11
    )

    override fun init(context: GameContext) {
        this.context = context
        wins.value = 0
        losses.value = 0
        matchOver.value = false
        recentWords.clear()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /** Call once, from the UI, before startMatch() — loads the shared dictionary asset that [wordPoolFor] samples from. */
    fun loadDictionary(androidContext: Context) {
        WordDictionary.ensureLoaded(androidContext)
    }

    /**
     * Builds this round's candidate pool for [tier]: [curatedWords] plus a broader sample from
     * the shared offline dictionary (see WordDictionary) at lengths typical for the tier. The raw
     * dictionary has no frequency/curation signal — obscure and everyday words look identical to
     * it — so this doesn't pipe it straight through; it just widens each ~15-word tier to roughly
     * 40-60 words so "New Word" stops cycling the same short list, while [curatedWords] still
     * anchors each tier with words known to read well in this format. If [loadDictionary] hasn't
     * run yet, [WordDictionary.wordsOfLength] returns empty lists rather than throwing, so this
     * degrades gracefully to [curatedWords] alone instead of crashing.
     */
    private fun wordPoolFor(tier: CpuDifficulty): List<String> {
        val curated = curatedWords.getValue(tier)
        val lengths = dictionaryLengthsByDifficulty.getValue(tier)
        val sampled = lengths.flatMap { length ->
            WordDictionary.wordsOfLength(length)
                .filter { it.all(Char::isLetter) } // dictionary asset can include hyphenated/apostrophe entries; hangman assumes plain letters
                .shuffled()
                .take(DICTIONARY_WORDS_PER_LENGTH)
                .map { it.uppercase() }
        }
        return (curated + sampled).distinct()
    }

    override fun startMatch() {
        state.value = HangmanState(
            word = pickWord(wordPoolFor(difficulty)),
            guessedLetters = emptySet(),
            wrongGuesses = 0,
            maxWrongGuesses = 6
        )
    }

    /**
     * Draws a word for the round, avoiding [recentWords] where possible so
     * back-to-back "New Word" taps don't keep re-serving the same word from
     * these small (15-ish word) pools — falls back to allowing a repeat only
     * once excluding history would leave nothing left to draw from (e.g.
     * early in a session, before [WORD_HISTORY_SIZE] distinct words have
     * been seen yet, exclusion never empties the pool).
     */
    private fun pickWord(pool: List<String>): String {
        val available = pool.filterNot { it in recentWords }
        val chosen = available.ifEmpty { pool }.random()

        recentWords.addLast(chosen)
        while (recentWords.size > WORD_HISTORY_SIZE) recentWords.removeFirst()
        return chosen
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

    companion object {
        /** How many past words [pickWord] avoids repeating. */
        private const val WORD_HISTORY_SIZE = 5

        /** How many dictionary words [wordPoolFor] samples per length, per tier — see its KDoc for the 40-60-per-tier target this feeds into. */
        private const val DICTIONARY_WORDS_PER_LENGTH = 15
    }
}

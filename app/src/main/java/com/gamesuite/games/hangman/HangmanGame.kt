package com.gamesuite.games.hangman

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.wordgames.WordDictionary
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a Hangman session back exactly as it was: the SAME hidden word, the
 * letters already guessed, the wrong-guess count, how many hints this word has used, the session
 * tally (words won / lost), the difficulty and the recent-word history (so "New Word" variety
 * continues across a resume). Not stored because they are transient: the screen's animation
 * state and the in-flight letter arcs.
 */
@Serializable
private data class HangmanSnapshot(
    val word: String,
    /** Guessed letters as one string (sorted, no duplicates). */
    val guessed: String,
    val wrongGuesses: Int,
    val maxWrongGuesses: Int,
    /** True once this word is won or lost (its result panel is showing). */
    val wordOver: Boolean,
    val won: Boolean,
    val hintsUsed: Int,
    val wins: Int,
    val losses: Int,
    val difficulty: String,
    val recentWords: List<String>,
    val players: List<HangmanSnapshotPlayer>
)

@Serializable
private data class HangmanSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val hangmanSnapshotJson = Json { ignoreUnknownKeys = true }

data class HangmanState(
    val word: String,
    val guessedLetters: Set<Char>,
    val wrongGuesses: Int,
    val maxWrongGuesses: Int,
    val matchOver: Boolean = false,
    val won: Boolean = false,
    /** Hints used on THIS word; a word with hints is "assisted". Reset for every new word. */
    val hintsUsed: Int = 0
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
 *
 * Resume + Hint: implements [ResumableGame] (the snapshot carries the hidden word, so a resume
 * continues the SAME word) and offers [hint], which reveals one correct letter and marks the word
 * assisted ([HangmanState.hintsUsed]). There is no Undo: a guess is the whole move.
 */
class HangmanGame : GameModule, ResumableGame {
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

    /** Words lost this session (the counterpart of [wins]; same role as WordGuessGame.puzzlesFailed). */
    val losses = mutableStateOf(0)

    /**
     * Words finished this session, solved or lost. A word still in progress is NOT finished.
     * The screen's abort policy keys off this: with finished words, leaving goes through
     * [leaveSession] so they still count; with none, leaving is a pure abort.
     */
    val finishedWords: Int get() = wins.value + losses.value

    /** True while a hint could still reveal a letter (word in progress, a hidden letter left). */
    val canHint: Boolean
        get() {
            val s = state.value ?: return false
            return !matchOver.value && !s.matchOver && s.word.any { it !in s.guessedLetters }
        }

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
            maxWrongGuesses = MAX_WRONG_GUESSES
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

    fun guessLetter(letter: Char) = applyGuess(letter, hinted = false)

    private fun applyGuess(letter: Char, hinted: Boolean) {
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.matchOver || letter in s.guessedLetters) return

        val newGuessed = s.guessedLetters + letter
        val correct = letter in s.word
        val newWrong = if (correct) s.wrongGuesses else s.wrongGuesses + 1

        val won = s.word.all { it in newGuessed }
        val lost = newWrong >= s.maxWrongGuesses

        state.value = s.copy(
            guessedLetters = newGuessed,
            wrongGuesses = newWrong,
            hintsUsed = if (hinted) s.hintsUsed + 1 else s.hintsUsed
        )

        if (won || lost) {
            if (won) wins.value += 1 else losses.value += 1
            state.value = state.value?.copy(matchOver = true, won = won)
        }
    }

    /**
     * Reveals one correct letter (the first hidden one, left to right) and returns it, or null
     * when hinting makes no sense (no word, word already over, session over). It is a real
     * guess that can never be wrong, but it counts: [HangmanState.hintsUsed] goes up so the
     * result panel can say the word was assisted. Hangman keeps no best-time/best-score record, so
     * an assisted word still counts as solved for the session tally.
     */
    fun hint(): Char? {
        if (matchOver.value) return null
        val s = state.value ?: return null
        if (s.matchOver) return null
        val letter = s.word.firstOrNull { it !in s.guessedLetters } ?: return null
        applyGuess(letter, hinted = true)
        return letter
    }

    /** Test seam: starts a word with a known answer instead of a random draw. */
    internal fun startMatchWithWord(word: String) {
        state.value = HangmanState(
            word = word.uppercase(),
            guessedLetters = emptySet(),
            wrongGuesses = 0,
            maxWrongGuesses = MAX_WRONG_GUESSES
        )
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun isLocalMode(): Boolean =
        context.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT ||
            context.activeMode == PlayMode.SINGLE_DEVICE_PASS_AND_PLAY

    /** Null when there is nothing to save: no word yet, the session already ended, or an online/nearby mode. */
    override fun snapshot(): String? {
        if (!::context.isInitialized) return null
        val s = state.value ?: return null
        if (matchOver.value || !isLocalMode()) return null
        return try {
            hangmanSnapshotJson.encodeToString(
                HangmanSnapshot.serializer(),
                HangmanSnapshot(
                    word = s.word,
                    guessed = s.guessedLetters.sorted().joinToString(""),
                    wrongGuesses = s.wrongGuesses,
                    maxWrongGuesses = s.maxWrongGuesses,
                    wordOver = s.matchOver,
                    won = s.won,
                    hintsUsed = s.hintsUsed,
                    wins = wins.value,
                    losses = losses.value,
                    difficulty = difficulty.name,
                    recentWords = recentWords.toList(),
                    players = context.players.map { HangmanSnapshotPlayer(it.playerId, it.isBot) }
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Decodes a snapshot and checks it is internally consistent and belongs to THIS roster; null if unusable. */
    private fun decodeSnapshot(text: String): HangmanSnapshot? {
        val snap = try {
            hangmanSnapshotJson.decodeFromString(HangmanSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val roster = context.players
        val guessed = snap.guessed.toSet()
        val won = snap.word.isNotEmpty() && snap.word.all { it in guessed }
        val lost = snap.wrongGuesses >= snap.maxWrongGuesses
        val valid = snap.word.isNotEmpty() &&
            snap.word.length <= MAX_SNAPSHOT_WORD_LENGTH &&
            snap.word.all { it in 'A'..'Z' } &&
            snap.maxWrongGuesses == MAX_WRONG_GUESSES &&
            guessed.size == snap.guessed.length &&
            guessed.all { it in 'A'..'Z' } &&
            snap.wrongGuesses == guessed.count { it !in snap.word } &&
            snap.wordOver == (won || lost) &&
            snap.won == won &&
            snap.hintsUsed >= 0 && snap.wins >= 0 && snap.losses >= 0 &&
            snap.players.size == roster.size &&
            snap.players.indices.all {
                snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
            }
        return if (valid) snap else null
    }

    override fun restore(snapshot: String): Boolean {
        if (!::context.isInitialized) return false
        val snap = decodeSnapshot(snapshot) ?: return false
        // Everything above is validated; from here on nothing can fail, so the engine is
        // assigned all-or-nothing.
        matchOver.value = false
        state.value = HangmanState(
            word = snap.word,
            guessedLetters = snap.guessed.toSet(),
            wrongGuesses = snap.wrongGuesses,
            maxWrongGuesses = snap.maxWrongGuesses,
            matchOver = snap.wordOver,
            won = snap.won,
            hintsUsed = snap.hintsUsed
        )
        wins.value = snap.wins
        losses.value = snap.losses
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        recentWords.clear()
        recentWords.addAll(snap.recentWords.takeLast(WORD_HISTORY_SIZE))
        return true
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
        /** Classic six wrong guesses, the same at every difficulty (the word pool is the difficulty lever). */
        const val MAX_WRONG_GUESSES = 6

        /** How many past words [pickWord] avoids repeating. */
        private const val WORD_HISTORY_SIZE = 5

        /** Longest word a restored snapshot may carry (the pools top out far below this). */
        private const val MAX_SNAPSHOT_WORD_LENGTH = 40

        /** How many dictionary words [wordPoolFor] samples per length, per tier — see its KDoc for the 40-60-per-tier target this feeds into. */
        private const val DICTIONARY_WORDS_PER_LENGTH = 15
    }
}

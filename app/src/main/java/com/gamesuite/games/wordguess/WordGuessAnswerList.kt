package com.gamesuite.games.wordguess

import android.content.Context
import com.gamesuite.games.wordgames.WordSafety
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The curated pool of common 5-letter words [WordGuessGame] picks its SECRET from —
 * deliberately separate from the much larger, general-purpose `WordDictionary` (~359k words,
 * shared by Word Search/Crossword/Word Tiles): that dictionary is sourced for BREADTH, not for
 * "would a player ever reasonably guess this as today's word" fairness — it contains plenty of
 * obscure/archaic/technical 5-letter words that would make a genuinely unfair secret. This is
 * the same two-tier design real Wordle itself uses (a broad "valid guess" dictionary plus a
 * separate, curated "possible answer" list) — [WordGuessGame]'s own guess VALIDATION still goes
 * through the shared `WordDictionary` directly (see `WordGuessGame.isValidGuess`'s default);
 * only the secret comes from here.
 *
 * Backed by `assets/word_guess_answers.txt` — 2,314 common 5-letter English words, one per
 * line, lowercase, sourced from the publicly-known NYT Wordle answer list (a widely-reused,
 * well-vetted curated list — the actual words real Wordle has used as daily answers, not an
 * arbitrary independent selection).
 */
object WordGuessAnswerList {
    private var answers: List<String> = emptyList()
    private var answerSet: Set<String> = emptySet()
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val words = mutableListOf<String>()
        context.assets.open("word_guess_answers.txt").use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines -> words.addAll(parse(lines)) }
        }
        install(words)
    }

    /** Trims, lowercases, drops blanks and anything [WordSafety] blocks. Pure, for tests and the loader. */
    fun parse(lines: Sequence<String>): List<String> =
        lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && WordSafety.isSafe(it) }.toList()

    internal fun install(words: List<String>) {
        answers = words
        answerSet = words.toSet()
        loaded = true
    }

    /** All curated, safe answers (empty until loaded). */
    fun all(): List<String> = answers

    fun isAnswer(word: String): Boolean = word.lowercase() in answerSet

    fun randomAnswer(random: kotlin.random.Random): String = answers.random(random)
}

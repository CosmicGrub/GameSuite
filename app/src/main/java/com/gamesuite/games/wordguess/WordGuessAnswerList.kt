package com.gamesuite.games.wordguess

import android.content.Context
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
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val words = mutableListOf<String>()
        context.assets.open("word_guess_answers.txt").use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                lines.forEach { line ->
                    val w = line.trim()
                    if (w.isNotEmpty()) words.add(w)
                }
            }
        }
        answers = words
        loaded = true
    }

    fun randomAnswer(random: kotlin.random.Random): String = answers.random(random)
}

package com.gamesuite.games.wordgames

import com.gamesuite.games.drawguess.DrawGuessWordBank
import com.gamesuite.games.wordguess.WordGuessAnswerList

/**
 * The curated pool KID profiles use in Hangman and Word Search instead of the raw dictionary:
 * only the nouns of the kid-safe Draw and Guess bank plus the curated Word Guess answers
 * (a well-vetted list of common words), all passed through [WordSafety]. Uppercase-free:
 * lowercase letters only, single words.
 */
object KidWordPool {
    /** Single-word, letters-only English Draw and Guess words (already vetted by that bank's own tests). */
    fun drawGuessWords(): List<String> =
        DrawGuessWordBank.all.map { it.en.lowercase() }
            .filter { w -> w.all { it in 'a'..'z' } && WordSafety.isSafe(w) }
            .distinct()

    /** Everything the kid pool may contain, for [answers] as the Word Guess answer list. */
    fun build(answers: List<String> = WordGuessAnswerList.all()): List<String> =
        (drawGuessWords() + answers.filter { w -> w.all { it in 'a'..'z' } && WordSafety.isSafe(w) }).distinct()

    fun ofLength(length: Int, answers: List<String> = WordGuessAnswerList.all()): List<String> =
        build(answers).filter { it.length == length }
}

package com.gamesuite.games.wordgames

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shared offline dictionary for every word game (word search, crossword,
 * tile/Scrabble-style). Backed by `assets/words.txt` — ~359k lowercase
 * English words, one per line, sourced from the public-domain dwyl/english-words
 * list. Loaded once and cached; all three games share this single instance
 * instead of each parsing their own copy.
 */
object WordDictionary {
    private var allWords: Set<String> = emptySet()
    private var byLength: Map<Int, List<String>> = emptyMap()
    // @Volatile + @Synchronized: Word Search loads this off the main thread (Dispatchers.Default)
    // while other word games may call ensureLoaded from the main thread.
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        // mutableSetOf() is insertion-ordered (LinkedHashSet) on purpose: wordsOfLength() must come
        // back in file order, because the seeded Daily Word Search indexes into it.
        val words = mutableSetOf<String>()
        context.assets.open("words.txt").use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                lines.forEach { line ->
                    val w = line.trim()
                    if (w.isNotEmpty()) words.add(w)
                }
            }
        }
        allWords = words
        byLength = words.groupBy { it.length }
        loaded = true
    }

    fun isValidWord(word: String): Boolean = allWords.contains(word.lowercase())

    /** Random words of a given length, e.g. for word search grid seeding. */
    fun randomWordsOfLength(length: Int, count: Int, excluding: Set<String> = emptySet()): List<String> {
        val pool = (byLength[length] ?: emptyList()).filterNot { it in excluding }
        return pool.shuffled().take(count)
    }

    /** Random words within a length range, e.g. for tile-game bot vocabulary sampling. */
    fun randomWords(minLength: Int, maxLength: Int, count: Int): List<String> {
        val pool = allWords.filter { it.length in minLength..maxLength }
        return pool.shuffled().take(count)
    }

    fun wordsOfLength(length: Int): List<String> = byLength[length] ?: emptyList()
}

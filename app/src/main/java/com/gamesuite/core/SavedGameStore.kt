package com.gamesuite.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One game's saved, resumable state. [snapshot] is the engine's own opaque string (see
 * [ResumableGame]); everything else is shell metadata for the Resume prompt and for expiry.
 */
@Serializable
data class SavedGame(
    /** The nav route the game was launched on ("sudoku-daily", "tic-tac-toe-wild"): variants are separate saves. */
    val route: String,
    /** Shown in the Resume prompt, usually the module's display name. */
    val label: String,
    val savedAtMillis: Long,
    /** Local calendar day of the save, so a daily puzzle from yesterday is not offered today. */
    val savedAtEpochDay: Long,
    val snapshot: String
)

/**
 * File-backed store for [SavedGame]s: one small JSON file per route under [dir]. A game is saved
 * when the app is backgrounded or the player leaves mid-match, and deleted when its match
 * finishes or the player starts a new game over it, so at most one save per route exists.
 *
 * Deliberately forgiving: an unreadable, corrupt or expired file is deleted and reported as "no
 * save" rather than thrown, because a broken save must never be able to stop a game launching.
 * Writes go to a temp file first and are moved into place, so a crash mid-write cannot leave a
 * half-written save over a good one.
 */
class SavedGameStore(
    private val dir: File,
    private val epochDay: () -> Long = { LocalDate.now().toEpochDay() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(game: SavedGame) {
        if (!dir.exists() && !dir.mkdirs()) return
        val target = fileFor(game.route)
        val temp = File(dir, target.name + ".tmp")
        try {
            temp.writeText(json.encodeToString(SavedGame.serializer(), game), Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            temp.delete()
        }
    }

    /** The saved game for [route], or null when there is none, it is unreadable, or it has expired. */
    fun load(route: String): SavedGame? = read(fileFor(route), expectedRoute = route)

    /** Reads one save file; a corrupt, mismatched or expired one is deleted and reported as null. */
    private fun read(file: File, expectedRoute: String?): SavedGame? {
        if (!file.isFile) return null
        val game = try {
            json.decodeFromString(SavedGame.serializer(), file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            file.delete()
            return null
        }
        if ((expectedRoute != null && game.route != expectedRoute) || isExpired(game)) {
            file.delete()
            return null
        }
        return game
    }

    fun delete(route: String) {
        fileFor(route).delete()
    }

    fun has(route: String): Boolean = load(route) != null

    /** Every live save, newest first. Expired and corrupt files are cleaned up along the way. */
    fun all(): List<SavedGame> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return emptyList()
        return files
            .mapNotNull { f -> read(f, expectedRoute = null) }
            .sortedByDescending { it.savedAtMillis }
    }

    private fun isExpired(game: SavedGame): Boolean {
        if (isDailyRoute(game.route) && game.savedAtEpochDay != epochDay()) return true
        return nowMillis() - game.savedAtMillis > maxAgeMillis
    }

    private fun fileFor(route: String): File = File(dir, sanitize(route) + SUFFIX)

    private fun sanitize(route: String): String = route.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val SUFFIX = ".save.json"
        const val DEFAULT_MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** Daily puzzles/boards are only worth resuming on the day they were saved. */
        fun isDailyRoute(route: String): Boolean = route.endsWith("-daily")
    }
}

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago": the Resume prompt's age line. */
fun savedAgoText(savedAtMillis: Long, nowMillis: Long): String {
    val minutes = ((nowMillis - savedAtMillis).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 24L * 60L -> "${minutes / 60L} h ago"
        minutes < 48L * 60L -> "yesterday"
        else -> "${minutes / (24L * 60L)} days ago"
    }
}

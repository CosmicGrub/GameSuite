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
    val snapshot: String,
    /** The nav route to open to resume it. [route] is the storage key (route + launch configuration); this is the plain destination. */
    val navRoute: String = route,
    /**
     * How the game was launched (mode, roster, per-launch options), so the Game Hub can resume it
     * on its own without the menu having to re-install the same launch first. Null on saves from
     * before this existed: those still load, but only the legacy menu path can resume them.
     */
    val launch: SavedLaunch? = null,
    /** One line for the Hub's Resume card, e.g. "You vs CPU" or "4 players". */
    val summary: String = ""
)

@Serializable
data class SavedSeat(val id: String, val name: String, val isBot: Boolean = false, val teamId: Int = -1)

@Serializable
data class SavedLaunch(
    val mode: String,
    val seats: List<SavedSeat>,
    val localPlayerIndex: Int = 0,
    val cpuDifficulty: String? = null,
    val firstMoverIndex: Int = 0,
    val extras: Map<String, String> = emptyMap()
)

/**
 * Rebuilds the launch of a save made before the Game Hub (which recorded none) from its storage key,
 * `route@MODE@id,id!,...` ("!" marks a bot). Names are the defaults (the originals were not stored),
 * and the old Teams route gets alternating team ids. Returns null when the key is not in that shape.
 */
fun legacyLaunchFromKey(key: String, navRoute: String): SavedLaunch? {
    val parts = key.split("@")
    if (parts.size < 3) return null
    val mode = parts[1]
    if (mode.isBlank()) return null
    val ids = parts[2].split(",").filter { it.isNotBlank() }
    if (ids.isEmpty()) return null
    val humans = ids.count { !it.endsWith("!") }
    val teams = navRoute == "uno-teams"
    var humanNo = 0
    var cpuNo = 0
    val cpus = ids.count { it.endsWith("!") }
    val seats = ids.mapIndexed { i, raw ->
        val bot = raw.endsWith("!")
        val id = raw.removeSuffix("!").substringBefore("#")
        val name = if (bot) {
            cpuNo++; if (cpus == 1) "CPU" else "CPU $cpuNo"
        } else {
            humanNo++; if (humans == 1) "You" else "Player $humanNo"
        }
        SavedSeat(id = id, name = name, isBot = bot, teamId = if (teams) i % 2 else -1)
    }
    return SavedLaunch(mode = mode, seats = seats, localPlayerIndex = 0)
}

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

    /** Writes the save; true only when it really reached disk. */
    fun save(game: SavedGame): Boolean {
        if (!dir.exists() && !dir.mkdirs()) return false
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
            return true
        } catch (_: Exception) {
            temp.delete()
            return false
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
        if (isDailyRoute(game.navRoute) && game.savedAtEpochDay != epochDay()) return true
        return nowMillis() - game.savedAtMillis > maxAgeMillis
    }

    private fun fileFor(route: String): File = File(dir, sanitize(route) + SUFFIX)

    /**
     * Injective file-name encoding: letters, digits and '-' stay, everything else (including '_'
     * itself) becomes '_' plus four hex digits, so two different keys can never share a file.
     */
    private fun sanitize(route: String): String = buildString {
        for (c in route) {
            if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-') append(c)
            else append('_').append(c.code.toString(16).padStart(4, '0'))
        }
    }

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

package com.gamesuite.games.botbastion

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One leaderboard line. Initials are the only personal text and are local to the device. */
@Serializable
data class BbEntry(
    val initials: String,
    val score: Int,
    val wave: Int,
    val timeSeconds: Int,
    val coreLeft: Int,
    val epochDay: Long,
    val players: Int
)

/** Best results of one (map, tier). */
@Serializable
data class BbBest(val bestWave: Int = 0, val bestScore: Int = 0, val stars: Int = 0)

/**
 * Everything Bot Bastion remembers: per `bb/$mapId:$tier` bests and top-10 boards. Immutable; every change returns
 * a new profile. [encode] / [decode] are what the DataStore wrapper stores.
 */
@Serializable
data class BbProfile(
    val bests: Map<String, BbBest> = emptyMap(),
    val boards: Map<String, List<BbEntry>> = emptyMap()
) {
    companion object {
        const val TOP = 10
        const val PREFIX = "bb/"
        private val json = Json { ignoreUnknownKeys = true }

        fun key(mapId: String, tier: Tier): String = "$PREFIX$mapId:${tier.id}"

        /** Three letters A to Z; anything else is dropped and short names are padded with dashes. */
        fun sanitizeInitials(raw: String): String {
            val letters = raw.uppercase().filter { it in 'A'..'Z' }.take(3)
            return letters.padEnd(3, '-')
        }

        /** Endless ranks by waves then score; every other tier by score then the faster time. */
        fun comparator(tier: Tier): Comparator<BbEntry> =
            if (tier.endless) compareByDescending<BbEntry> { it.wave }.thenByDescending { it.score }.thenBy { it.timeSeconds }
            else compareByDescending<BbEntry> { it.score }.thenBy { it.timeSeconds }

        fun decode(text: String?): BbProfile {
            if (text.isNullOrBlank()) return BbProfile()
            return try {
                val p = json.decodeFromString<BbProfile>(text)
                p.copy(
                    bests = p.bests.filter { (k, v) -> k.startsWith(PREFIX) && v.bestWave >= 0 && v.bestScore >= 0 && v.stars in 0..3 },
                    boards = p.boards.filterKeys { it.startsWith(PREFIX) }.mapValues { (_, l) ->
                        l.filter { it.score >= 0 && it.wave >= 0 && it.players in 1..BbRules.MAX_PLAYERS }
                            .map { it.copy(initials = sanitizeInitials(it.initials)) }.take(TOP)
                    }
                )
            } catch (_: Exception) {
                BbProfile()
            }
        }
    }

    fun encode(): String = Json.encodeToString(this)

    fun best(mapId: String, tier: Tier): BbBest = bests[key(mapId, tier)] ?: BbBest()
    fun board(mapId: String, tier: Tier): List<BbEntry> = boards[key(mapId, tier)] ?: emptyList()

    /** Stars on a map: the best over every tier. */
    fun mapStars(mapId: String): Int = Tier.values().maxOf { best(mapId, it).stars }
    fun totalStars(maps: List<MapDef>): Int = maps.sumOf { mapStars(it.id) }
    fun isUnlocked(m: MapDef, maps: List<MapDef>): Boolean = totalStars(maps) >= m.unlockStars

    /** Would [entry] make the top 10 of its board? */
    fun qualifies(mapId: String, tier: Tier, entry: BbEntry): Boolean {
        val l = board(mapId, tier)
        return l.size < TOP || comparator(tier).compare(entry, l.last()) < 0
    }

    /** Folds a finished run into the bests (and the board when [entry] is given). */
    fun record(mapId: String, tier: Tier, outcome: BbOutcome, entry: BbEntry?): BbProfile {
        val k = key(mapId, tier)
        val old = best(mapId, tier)
        val nb = BbBest(maxOf(old.bestWave, outcome.wave), maxOf(old.bestScore, outcome.score), maxOf(old.stars, outcome.stars))
        val nbests = bests + (k to nb)
        if (entry == null) return copy(bests = nbests)
        val list = (board(mapId, tier) + entry.copy(initials = sanitizeInitials(entry.initials))).sortedWith(comparator(tier)).take(TOP)
        return copy(bests = nbests, boards = boards + (k to list))
    }
}

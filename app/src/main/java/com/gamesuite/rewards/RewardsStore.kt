package com.gamesuite.rewards

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamesuite.core.ProfileScope
import com.gamesuite.core.ProfileScopedDataStore
import com.gamesuite.core.epochDayOf
import com.gamesuite.core.gameDataStore
import com.gamesuite.stats.quarantineValue
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Result of decoding a stored sticker book: [corruptRaw] is set only when a non-blank value could not be read. */
data class BookDecoded(val book: StickerBook, val corruptRaw: String? = null)

/** Pure encode / decode of the stored book (copies the StatsCodec approach: unreadable data is quarantined, never overwritten). */
object RewardsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(book: StickerBook): String = json.encodeToString(StickerBook.serializer(), book)

    fun decode(raw: String?): BookDecoded {
        if (raw.isNullOrBlank()) return BookDecoded(StickerBook())
        return runCatching { json.decodeFromString(StickerBook.serializer(), raw) }
            .fold(
                onSuccess = { BookDecoded(sanitize(it)) },
                onFailure = { BookDecoded(StickerBook(), corruptRaw = raw) }
            )
    }

    /** Defends the invariants against a hand-edited or newer blob: only known stickers, bounded lists. */
    fun sanitize(b: StickerBook): StickerBook {
        val earned = b.earned.filterKeys { StickerCatalog.byId(it) != null }
        return b.copy(
            earned = earned,
            pending = b.pending.filter { it in earned }.distinct(),
            granted = b.granted.takeLast(RewardEngine.MAX_GRANTED),
            plays = b.plays.takeLast(RewardEngine.MAX_PLAYS),
            recentEvents = b.recentEvents.takeLast(RewardEngine.MAX_RECENT_EVENTS),
            totalGames = b.totalGames.coerceIn(0, 1_000_000)
        )
    }
}

private val Context.stickerStore: DataStore<Preferences> by gameDataStore("sticker_book")

/**
 * DataStore persistence of one [StickerBook] per profile. The store is created through [gameDataStore], so it is
 * profile-scoped: writes always go to the ACTIVE profile's own file, and [bookOf] can read any profile's (the parent
 * dashboard's weekly report). Adults and arena matches never reach [record].
 */
class RewardsStore(private val context: Context) {
    private object Keys {
        val BOOK = stringPreferencesKey("book_json")
        val CORRUPT = stringPreferencesKey("book_json_corrupt")
    }

    private fun Flow<Preferences>.safe(): Flow<Preferences> = catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /** The active profile's book (re-emits when the profile switches). */
    val book: Flow<StickerBook> = context.stickerStore.data.safe().map { RewardsCodec.decode(it[Keys.BOOK]).book }

    /** Read-only view of [profileId]'s book (null id = the active one). */
    fun bookOf(profileId: String): Flow<StickerBook> {
        val store = context.stickerStore
        val data = if (store is ProfileScopedDataStore) store.dataFor(profileId) else store.data
        return data.safe().map { RewardsCodec.decode(it[Keys.BOOK]).book }
    }

    private fun MutablePreferences.preserveCorrupt(d: BookDecoded) {
        quarantineValue(this[Keys.CORRUPT], d.corruptRaw)?.let { this[Keys.CORRUPT] = it }
    }

    /**
     * Records one finished match for the ACTIVE profile. [isKidProfile] false or [arena] true records nothing.
     * Returns the stickers earned (empty when none or ignored). The edit is atomic, so a crash can never half-award.
     */
    suspend fun record(
        event: RewardEvent,
        isKidProfile: Boolean,
        arena: Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Sticker> {
        if (!isKidProfile || arena) return emptyList()
        var out: List<Sticker> = emptyList()
        context.stickerStore.edit { prefs ->
            val decoded = RewardsCodec.decode(prefs[Keys.BOOK])
            prefs.preserveCorrupt(decoded)
            val r = RewardEngine.record(
                decoded.book, event, epochDayOf(event.atEpochMillis, zone), isKidProfile = true, arena = false
            )
            if (!r.ignored) prefs[Keys.BOOK] = RewardsCodec.encode(r.book)
            out = r.awarded.map { it.second }
        }
        return out
    }

    /** The child tapped a reward: moves [stickerId] into the visible book. */
    suspend fun collect(stickerId: String) {
        context.stickerStore.edit { prefs ->
            val decoded = RewardsCodec.decode(prefs[Keys.BOOK])
            prefs.preserveCorrupt(decoded)
            val next = RewardEngine.collect(decoded.book, stickerId)
            if (next !== decoded.book) prefs[Keys.BOOK] = RewardsCodec.encode(next)
        }
    }

    /** Whether the active profile id is a sensible target (defensive: used by the hook). */
    fun activeProfileId(): String = ProfileScope.current()
}

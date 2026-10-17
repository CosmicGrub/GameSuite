package com.gamesuite.audio.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One audio file found in the user's folder. [id] is stable across rescans (the document URI). */
data class MusicTrack(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L
)

/** A track's slot in one context's playlist. */
data class PlaylistEntry(val trackId: String, val enabled: Boolean = true)

/** Everything persisted about the user's library. */
data class MusicLibraryData(
    val folderUri: String? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: Map<MusicContext, List<PlaylistEntry>> = emptyMap()
) {
    /** Playlist for [context], always reconciled against the current track list. */
    fun playlistFor(context: MusicContext): List<PlaylistEntry> =
        Playlists.reconcile(playlists[context].orEmpty(), tracks.map { it.id })

    fun withPlaylist(context: MusicContext, list: List<PlaylistEntry>): MusicLibraryData =
        copy(playlists = playlists + (context to list))

    fun trackById(id: String): MusicTrack? = tracks.firstOrNull { it.id == id }
}

/** Pure, tolerant JSON (de)serialization for [MusicLibraryData]. [decode] never throws. */
object MusicLibraryCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(data: MusicLibraryData): String {
        val root = buildJsonObject {
            put("version", 1)
            if (data.folderUri != null) put("folderUri", data.folderUri)
            put("tracks", buildJsonArray {
                data.tracks.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("uri", t.uri)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("durationMs", t.durationMs)
                    })
                }
            })
            put("playlists", buildJsonObject {
                data.playlists.forEach { (ctx, entries) ->
                    put(ctx.name, buildJsonArray {
                        entries.forEach { e ->
                            add(buildJsonObject {
                                put("id", e.trackId)
                                put("on", e.enabled)
                            })
                        }
                    })
                }
            })
        }
        return root.toString()
    }

    fun decode(text: String?): MusicLibraryData {
        if (text.isNullOrBlank()) return MusicLibraryData()
        return try {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return MusicLibraryData()
            val folder = root["folderUri"].str()?.takeIf { it.isNotBlank() }
            val tracks = elements(root["tracks"]).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o["id"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val uri = o["uri"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MusicTrack(
                    id = id,
                    uri = uri,
                    title = o["title"].str()?.takeIf { it.isNotBlank() } ?: id,
                    artist = o["artist"].str().orEmpty(),
                    durationMs = (o["durationMs"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L) ?: 0L
                )
            }.distinctBy { it.id }
            val playlists = HashMap<MusicContext, List<PlaylistEntry>>()
            (root["playlists"] as? JsonObject)?.forEach { (key, value) ->
                val ctx = MusicContext.values().firstOrNull { it.name == key } ?: return@forEach
                playlists[ctx] = elements(value).mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val id = o["id"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PlaylistEntry(id, (o["on"] as? JsonPrimitive)?.booleanOrNull ?: true)
                }.distinctBy { it.trackId }
            }
            MusicLibraryData(folder, tracks, playlists)
        } catch (e: Exception) {
            MusicLibraryData()
        }
    }

    private fun JsonElement?.str(): String? =
        if (this is JsonPrimitive && this !is JsonNull) contentOrNull else null

    private fun elements(el: JsonElement?): List<JsonElement> = (el as? JsonArray)?.toList() ?: emptyList()
}

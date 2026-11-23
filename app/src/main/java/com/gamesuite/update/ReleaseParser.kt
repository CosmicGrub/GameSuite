package com.gamesuite.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Tolerant parser for the GitHub "list releases" payload: unknown fields ignored, malformed entries skipped. */
object ReleaseParser {
    private val json = Json { isLenient = true }

    /** @throws UpdateException(BadResponse) when the root is not a JSON array. */
    fun parseList(text: String): List<ReleaseInfo> {
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.BadResponse, "not JSON")
        }
        val arr = root as? JsonArray ?: throw UpdateException(UpdateFailure.BadResponse, "not an array")
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseRelease) }
    }

    fun parseRelease(o: JsonObject): ReleaseInfo? {
        val tag = o.str("tag_name")?.takeIf { it.isNotBlank() } ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAsset) }
        return ReleaseInfo(
            tag = tag,
            name = o.str("name").orEmpty().ifBlank { tag },
            body = o.str("body").orEmpty(),
            prerelease = o.bool("prerelease"),
            draft = o.bool("draft"),
            publishedAt = o.str("published_at") ?: o.str("created_at"),
            htmlUrl = o.str("html_url"),
            assets = assets
        )
    }

    private fun parseAsset(o: JsonObject): ReleaseAsset? {
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val url = o.str("browser_download_url")?.takeIf { it.isNotBlank() } ?: return null
        return ReleaseAsset(name, (o["size"] as? JsonPrimitive)?.longOrNull ?: -1L, url, o.str("content_type").orEmpty())
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
}

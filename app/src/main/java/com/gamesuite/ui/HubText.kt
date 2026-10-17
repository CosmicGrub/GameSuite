package com.gamesuite.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.gamesuite.R
import com.gamesuite.core.hubKey

/**
 * Localizes the Game Hub catalog. The pure model (GameHubSpecs, HubSummary) keeps English text so
 * it stays JVM-testable; the UI maps each English string to the Android string resource named by
 * [hubKey] (`hub_` + a slug of the text) and shows the locale's translation. A text without a
 * resource shows its English original, so a missing translation can never crash or blank a label.
 * HubLocalizationTest keeps every catalog string covered in both locales.
 */
object HubText {
    fun resolve(context: Context, text: String): String {
        if (text.isBlank()) return text
        val resources = context.resources
        val id = resources.getIdentifier(hubKey(text), "string", context.packageName)
        if (id == 0) return text
        return try {
            resources.getString(id)
        } catch (e: Resources.NotFoundException) {
            text
        }
    }

    /**
     * "just now" / "5 min ago" / "3 h ago" / "yesterday" / "4 days ago" in the current locale, using
     * the same thresholds as core's savedAgoText (which stays English for the data layer).
     */
    fun savedAgo(context: Context, savedAtMillis: Long, nowMillis: Long): String {
        val minutes = ((nowMillis - savedAtMillis).coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1L -> context.getString(R.string.hub_ui_saved_just_now)
            minutes < 60L -> context.getString(R.string.hub_ui_saved_min_ago, minutes.toInt())
            minutes < 24L * 60L -> context.getString(R.string.hub_ui_saved_h_ago, (minutes / 60L).toInt())
            minutes < 48L * 60L -> context.getString(R.string.hub_ui_saved_yesterday)
            else -> {
                val days = (minutes / (24L * 60L)).toInt()
                context.resources.getQuantityString(R.plurals.hub_ui_saved_days_ago, days, days)
            }
        }
    }
}

/** [HubText.resolve] for a composable: re-resolves when the locale or configuration changes. */
@Composable
fun hubText(text: String): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, text) { HubText.resolve(context, text) }
}

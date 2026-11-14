package com.gamesuite.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameTeaches
import com.gamesuite.profiles.KidControls
import com.gamesuite.profiles.PlayTimeLimiter
import com.gamesuite.rewards.RewardsStore
import com.gamesuite.rewards.StickerBook
import com.gamesuite.rewards.WeeklyReport
import com.gamesuite.rewards.WeeklyReportBuilder
import com.gamesuite.rewards.lineRes
import com.gamesuite.rewards.nameRes
import com.gamesuite.ui.hubText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The parent dashboard's 7-day view for one child: minutes per day (read from the limiter's own history, not
 * re-counted), games played, favourite games, skills practised and stickers earned. The numbers come from
 * [WeeklyReportBuilder] over this child's sticker book (their plays) and [PlayTimeLimiter.weekMillis].
 */
@Composable
fun WeeklyReportCard(limiter: PlayTimeLimiter, kidId: String, kc: KidControls) {
    val context = LocalContext.current
    val store = remember { RewardsStore(context.applicationContext) }
    val flow = remember(kidId) { store.bookOf(kidId) }
    val book by flow.collectAsStateWithLifecycle(initialValue = StickerBook())
    val today = LocalDate.now()
    val report = WeeklyReportBuilder.build(book, limiter.weekMillis(kc), today.toEpochDay())
    ReportBody(report)
}

@Composable
private fun ReportBody(report: WeeklyReport) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rw_report_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rw_report_minutes, report.totalMinutes))
            val maxMin = (report.days.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(15)
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                report.days.forEachIndexed { i, d ->
                    val day: DayOfWeek = LocalDate.ofEpochDay(d.epochDay).dayOfWeek
                    val dayName = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    val cd = stringResource(R.string.rw_report_day_cd, dayName, d.minutes)
                    Column(Modifier.weight(1f).semantics { contentDescription = cd }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f))
                        Box(
                            Modifier.fillMaxWidth().height((56f * d.minutes / maxMin).coerceAtLeast(2f).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (i == report.days.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        )
                        Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (report.gamesPlayed == 0) {
                Text(stringResource(R.string.rw_report_none), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.rw_report_games, report.gamesPlayed))
                Text(stringResource(R.string.rw_report_favs), style = MaterialTheme.typography.labelLarge)
                report.favourites.forEach { f ->
                    val title = GameHubSpecs.byId(f.gameId)?.let { hubText(it.title) } ?: f.gameId
                    Text(stringResource(R.string.rw_report_fav_row, title, f.plays), style = MaterialTheme.typography.bodyMedium)
                }
                if (report.skills.isNotEmpty()) {
                    Text(stringResource(R.string.rw_report_skills), style = MaterialTheme.typography.labelLarge)
                    report.skills.forEach { s ->
                        Text(
                            stringResource(s.skill.nameRes()) + " (" + s.plays + "): " + stringResource(s.skill.lineRes()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Text(stringResource(R.string.rw_report_stickers, report.stickersEarned))
        }
    }
}

/** The "Practises: Memory, Taking turns" line for a game's row in the "Games shown" list; null for a game with no entry. */
@Composable
fun teachesLine(gameId: String): String? {
    val skills = GameTeaches.skillsFor(gameId)
    if (skills.isEmpty()) return null
    val names = skills.joinToString(", ") { stringResource(it.nameRes()) }
    return stringResource(R.string.rw_teaches_label, names)
}

/** The friendly sentences for a game's skills, space separated. */
@Composable
fun teachesSentences(gameId: String): String =
    GameTeaches.skillsFor(gameId).joinToString(" ") { stringResource(it.lineRes()) }

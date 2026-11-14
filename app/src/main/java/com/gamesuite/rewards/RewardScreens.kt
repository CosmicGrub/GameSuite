package com.gamesuite.rewards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gamesuite.R
import java.time.LocalDate

/** The text for the "one more game" card (pure choice in [RewardEngine.nextGoal]). */
@Composable
fun goalText(goal: NextGoal): String = when (goal) {
    NextGoal.BookComplete -> stringResource(R.string.rw_goal_complete)
    NextGoal.PlayToday -> stringResource(R.string.rw_goal_today)
    is NextGoal.MoreGames -> pluralStringResource(R.plurals.rw_goal_games, goal.remaining, goal.remaining)
    is NextGoal.StreakDays -> pluralStringResource(R.plurals.rw_goal_streak, goal.days, goal.days)
}

/**
 * The sticker book: one tab per set, collected stickers in full colour, locked slots as dashed outlines with a lock,
 * the count, and a "one more game" progress card. Only COLLECTED stickers show (a pending one is waiting for its tap).
 * Call inside the Kids theme. Text uses MaterialTheme typography only, so it follows the text-size setting.
 */
@Composable
fun StickerBookScreen(book: StickerBook, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val sets = StickerSet.values()
    val set = sets[tab.coerceIn(0, sets.lastIndex)]
    val collected = book.collected
    val goal = remember(book) { RewardEngine.nextGoal(book, LocalDate.now().toEpochDay()) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.rw_book_back))
                }
                Text(
                    stringResource(R.string.rw_book_title),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                stringResource(R.string.rw_book_count, collected.size, StickerCatalog.size),
                style = MaterialTheme.typography.titleMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sets.forEachIndexed { i, s ->
                    FilterChip(
                        selected = i == tab,
                        onClick = { tab = i },
                        label = { Text(stringResource(s.nameRes())) },
                        modifier = Modifier.heightIn(min = 56.dp)
                    )
                }
            }
            val setCd = stringResource(
                R.string.rw_book_set_cd, stringResource(set.nameRes()),
                StickerCatalog.inSet(set).count { it.id in collected }, StickerCatalog.SET_SIZE
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.weight(1f).semantics { contentDescription = setCd },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(StickerCatalog.inSet(set), key = { it.id }) { st ->
                    StickerSlot(st, unlocked = st.id in collected)
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    goalText(goal), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp).heightIn(min = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun StickerSlot(sticker: Sticker, unlocked: Boolean) {
    val name = stringResource(sticker.nameRes())
    val cd = if (unlocked) stringResource(R.string.rw_book_sticker_cd, name) else stringResource(R.string.rw_book_locked_cd)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = cd },
        contentAlignment = Alignment.Center
    ) {
        if (unlocked) {
            StickerArt(sticker, Modifier.fillMaxSize())
        } else {
            LockedSlotOutline(MaterialTheme.colorScheme.outline, Modifier.fillMaxSize())
            Text("🔒", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/**
 * The reward moment: the oldest pending sticker pops in with a spring (no animation under Reduced Motion), TalkBack
 * announces it (live region), and ONE tap on the button (or anywhere outside) collects it. Shows nothing without a pending sticker.
 */
@Composable
fun RewardMomentHost(book: StickerBook, reducedMotion: Boolean, onCollect: (String) -> Unit) {
    val sticker = RewardEngine.nextPending(book) ?: return
    RewardMomentDialog(sticker, reducedMotion) { onCollect(sticker.id) }
}

@Composable
fun RewardMomentDialog(sticker: Sticker, reducedMotion: Boolean, onCollect: () -> Unit) {
    val name = stringResource(sticker.nameRes())
    val title = stringResource(R.string.rw_reward_title)
    val spoken = stringResource(R.string.rw_reward_cd, name, stringResource(R.string.rw_collect))
    val pop = remember(sticker.id) { Animatable(if (reducedMotion) 1f else 0.2f) }
    LaunchedEffect(sticker.id, reducedMotion) {
        if (reducedMotion) pop.snapTo(1f)
        else pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    Dialog(onDismissRequest = onCollect) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFFFFF4DC)) {
            Column(
                Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = Color(0xFF2B1B4A), textAlign = TextAlign.Center
                )
                StickerArt(sticker, Modifier.size(160.dp).scale(pop.value))
                Text(name, style = MaterialTheme.typography.titleLarge, color = Color(0xFF2B1B4A), textAlign = TextAlign.Center)
                Button(onClick = onCollect, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth()) {
                    Text(stringResource(R.string.rw_collect))
                }
            }
        }
    }
}

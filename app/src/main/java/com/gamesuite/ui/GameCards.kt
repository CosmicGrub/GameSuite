package com.gamesuite.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.R
import com.gamesuite.core.FavoritesStore
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.gameGlyph
import com.gamesuite.core.launchHubMode

/*
 * The game card used by the Games tab, and the compact tile used by the Home tab's rows.
 * See docs/design/game-hub.md.
 */

/** The starred games, observable by the screen that owns it. Backed by [FavoritesStore]. */
class FavoritesState(private val store: FavoritesStore) {
    var ids: Set<String> by mutableStateOf(store.all())
        private set

    fun toggle(gameId: String) {
        ids = store.toggle(gameId)
    }
}

@Composable
fun rememberFavorites(): FavoritesState {
    val context: Context = LocalContext.current
    return remember(context) { FavoritesState(FavoritesStore(context)) }
}

/** The existing section-title string for a family (the Games tab headers and filter chips). */
@StringRes
fun familyLabelRes(family: HubFamily): Int = when (family) {
    HubFamily.BOARD -> R.string.section_board_games
    HubFamily.CARD -> R.string.section_cards
    HubFamily.WORD -> R.string.section_word_games
    HubFamily.ARCADE_PUZZLE -> R.string.section_arcade_puzzles
    HubFamily.TOOLKIT -> R.string.section_party_toolkit
}

/** A thin family colour, picked from the theme so it follows light, dark and dynamic colour. */
@Composable
fun familyAccent(family: HubFamily): Color {
    val scheme = MaterialTheme.colorScheme
    return when (family) {
        HubFamily.BOARD -> scheme.primary
        HubFamily.CARD -> scheme.tertiary
        HubFamily.WORD -> scheme.secondary
        HubFamily.ARCADE_PUZZLE -> lerp(scheme.primary, scheme.tertiary, 0.5f)
        HubFamily.TOOLKIT -> scheme.outline
    }
}

/**
 * A game's own accent colour (docs/design/game-hub.md, "per-game identity"): the card's left edge,
 * the Hub header band and the Play button tint. Picks the light or dark value from the current
 * scheme, then nudges it if this scheme's surfaces (dynamic colour, high contrast) would leave it
 * under 3:1. A game with no entry in [GameThemes.accents] falls back to its family's colour.
 */
@Composable
fun gameAccent(gameId: String, family: HubFamily): Color {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    val surface = scheme.surface
    val card = scheme.surfaceContainerHighest
    return remember(gameId, family, isDark, surface, card) {
        val pair = GameThemes.accentFor(gameId, family)
        val backgrounds = listOf(surface, card).map { it.toArgb().toLong() and 0xFFFFFFFFL }
        Color(GameThemes.ensureContrast(pair.forDark(isDark), backgrounds))
    }
}

/** The label colour for something filled with [accent] (white or near-black, whichever reads better). */
fun onGameAccent(accent: Color): Color =
    Color(GameThemes.onAccent(accent.toArgb().toLong() and 0xFFFFFFFFL))

/**
 * What tapping a game's card does: a game with nothing to choose ([GameHubSpec.skipsHub]) launches
 * straight away, every other game opens its Hub. [navigate] receives a nav route.
 */
fun openGameFromCard(spec: GameHubSpec, sessionManager: GameSessionManager, navigate: (String) -> Unit) {
    if (spec.skipsHub) {
        val mode = spec.modes.first()
        val result = launchHubMode(sessionManager, spec, mode, mode.defaults())
        if (result is HubLaunch.Local) navigate(result.route) else navigate("hub/${spec.gameId}")
    } else {
        navigate("hub/${spec.gameId}")
    }
}

@Composable
private fun CardBadge(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

/**
 * One game on the Games tab: glyph, title, tagline and badges, with a star to favourite it. The
 * whole card is the tap target and reads as one button ("Title. Tagline. Daily, Online").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameCard(
    spec: GameHubSpec,
    hasSave: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = hubText(spec.title)
    val tagline = hubText(spec.tagline)
    val dailyBadge = stringResource(R.string.badge_daily)
    val onlineBadge = stringResource(R.string.badge_online)
    val resumeBadge = stringResource(R.string.badge_resume)
    val badges = buildList {
        if (spec.hasDaily) add(dailyBadge)
        if (spec.isNetworked) add(onlineBadge)
        if (hasSave) add(resumeBadge)
    }
    val description = buildString {
        append(title).append(". ").append(tagline)
        if (badges.isNotEmpty()) append(". ").append(badges.joinToString(", "))
    }
    val accent = gameAccent(spec.gameId, spec.family)
    val favName = if (isFavorite) {
        stringResource(R.string.fav_remove, title)
    } else {
        stringResource(R.string.fav_add, title)
    }
    val favState = stringResource(if (isFavorite) R.string.fav_state_on else R.string.fav_state_off)

    Box(modifier = modifier) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = 72.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(accent))
                Row(
                    // The card's own contentDescription is the label; the visuals add nothing for a screen reader.
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
                        .clearAndSetSemantics { },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A slot with a minimum width, so titles line up whatever the glyph's own width.
                    Text(
                        gameGlyph(spec.gameId),
                        fontSize = 28.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.widthIn(min = 36.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            // Two lines so a long title stays readable at 200% font on a narrow card.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            tagline,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (badges.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (spec.hasDaily) {
                                    CardBadge(
                                        dailyBadge,
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                if (spec.isNetworked) {
                                    CardBadge(
                                        onlineBadge,
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                if (hasSave) {
                                    CardBadge(
                                        resumeBadge,
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                    // Room for the star button that is drawn on top of the card.
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        IconToggleButton(
            checked = isFavorite,
            onCheckedChange = { onToggleFavorite() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics {
                    contentDescription = favName
                    stateDescription = favState
                }
        ) {
            Text(
                if (isFavorite) "★" else "☆",
                fontSize = 24.sp,
                color = if (isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}

/**
 * A compact card for the Home tab's rows (Today, Continue, Favourites, Recently played). Reads as
 * one button from its visible text. [onLongClick] is optional (Recently played uses it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameTile(
    glyph: String,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Announced for the long-press action ("Remove from recently played"); null announces none. */
    longClickLabel: String? = null
) {
    ElevatedCard(
        modifier = modifier
            .widthIn(min = 168.dp, max = 260.dp)
            .heightIn(min = 72.dp)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick
            )
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(accent))
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A fixed-width slot so every card's text starts at the same x whatever the glyph's own width.
                Text(glyph, fontSize = 24.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(40.dp).clearAndSetSemantics { })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

package com.gamesuite.ui.club

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.ClubProfile

/**
 * The "Club" card for the Home tab: the player's animated avatar, a greeting with their name, their
 * favourite game and two actions. Stateless: [favoriteTitle] is the already-localized title of
 * profile.favoriteGameId (null when none), [onPlayFavorite] is null when there is no favourite.
 * Both buttons are at least 48dp tall and the row wraps to a column at large font scales.
 */
@Composable
fun ClubHomeCard(
    profile: ClubProfile,
    defaultName: String,
    favoriteTitle: String?,
    onEditProfile: () -> Unit,
    onPlayFavorite: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val name = profile.displayNameOr(defaultName)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AvatarBadge(profile.avatar, size = 72.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.club_home_greeting, name),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        if (favoriteTitle != null) stringResource(R.string.club_home_favorite, favoriteTitle)
                        else stringResource(R.string.club_home_no_favorite),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditProfile, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.club_home_edit))
                }
                if (onPlayFavorite != null && favoriteTitle != null) {
                    Button(onClick = onPlayFavorite, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.club_home_play_favorite, favoriteTitle), maxLines = 2)
                    }
                }
            }
        }
    }
}

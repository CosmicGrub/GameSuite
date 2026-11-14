package com.gamesuite.ui.club

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamesuite.core.Avatar

/**
 * One joined player in a host lobby: a still avatar (a stable face derived from the name, so a
 * guest with no profile of their own still gets one) beside the name. The avatar is decorative: the
 * name is the TalkBack content, and the row is at least 48dp tall so it stays comfortable at large fonts.
 */
@Composable
fun LobbySeatRow(name: String, modifier: Modifier = Modifier) {
    val avatar = remember(name) { Avatar.forSeed(name) }
    Row(
        modifier = modifier.heightIn(min = 48.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AvatarBadge(avatar, size = 36.dp, animated = false, contentDescription = "")
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
    }
}

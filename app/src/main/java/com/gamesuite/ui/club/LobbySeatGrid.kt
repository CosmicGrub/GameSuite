package com.gamesuite.ui.club

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.Avatar
import com.gamesuite.transport.SeatKind
import com.gamesuite.transport.SeatView

/**
 * What the host can do from the seat grid. A null callback hides that control (a game with no computer seats passes
 * no add/remove-computer; a guest's read-only grid passes the whole object as null).
 */
class SeatGridActions(
    val onAddComputer: (() -> Unit)? = null,
    val onRemoveComputer: (() -> Unit)? = null,
    val onRemovePlayer: ((playerId: String) -> Unit)? = null,
)

/**
 * The lobby table: one card per seat (host, humans, computers, empty), two columns in portrait and four on a wide
 * window. Every card is at least 64dp tall; a card with an action (add or remove a computer, remove a player) is a
 * single 48dp+ target that TalkBack reads as "Seat 3 of 4: Sam, ready" with the action offered as its click label.
 * Status is always words ("Ready", "Computer", "Empty"), never colour alone. [localPlayerId] marks the viewer's own seat.
 */
@Composable
fun LobbySeatGrid(
    seats: List<SeatView>,
    modifier: Modifier = Modifier,
    localPlayerId: String? = null,
    actions: SeatGridActions? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            seats.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { col, seat ->
                        val index = rowIndex * columns + col
                        SeatCard(
                            seat = seat,
                            number = index + 1,
                            total = seats.size,
                            isYou = localPlayerId != null && seat.playerId == localPlayerId && seat.kind != SeatKind.EMPTY,
                            actions = actions,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Keep the last row's cards the same width as the rest.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SeatCard(
    seat: SeatView,
    number: Int,
    total: Int,
    isYou: Boolean,
    actions: SeatGridActions?,
    modifier: Modifier,
) {
    val status = when (seat.kind) {
        SeatKind.HOST -> stringResource(R.string.ls_seat_host)
        SeatKind.HUMAN -> stringResource(if (seat.ready) R.string.ls_seat_ready else R.string.ls_seat_not_ready)
        SeatKind.CPU -> stringResource(R.string.ls_seat_computer)
        SeatKind.EMPTY -> stringResource(R.string.ls_seat_empty)
    }
    val addComputer = actions?.onAddComputer.takeIf { seat.kind == SeatKind.EMPTY }
    val removeComputer = actions?.onRemoveComputer.takeIf { seat.kind == SeatKind.CPU }
    val removePlayer = actions?.onRemovePlayer.takeIf { seat.kind == SeatKind.HUMAN && !isYou && seat.playerId != null }
    val onClick: (() -> Unit)? = when {
        addComputer != null -> addComputer
        removeComputer != null -> removeComputer
        removePlayer != null -> ({ removePlayer(seat.playerId ?: "") })
        else -> null
    }
    val clickLabel = when {
        addComputer != null -> stringResource(R.string.ls_action_add_computer)
        removeComputer != null -> stringResource(R.string.ls_action_remove_computer)
        removePlayer != null -> stringResource(R.string.ls_action_remove_player)
        else -> null
    }
    val description = when {
        seat.kind == SeatKind.EMPTY && addComputer != null ->
            stringResource(R.string.ls_seat_cd_empty_can_add, number, total)
        seat.kind == SeatKind.EMPTY -> stringResource(R.string.ls_seat_cd_empty, number, total)
        isYou -> stringResource(R.string.ls_seat_cd_you, number, total, seat.name, status)
        else -> stringResource(R.string.ls_seat_cd_filled, number, total, seat.name, status)
    }
    val empty = seat.kind == SeatKind.EMPTY
    val shape = MaterialTheme.shapes.medium
    val base = modifier.heightIn(min = 64.dp)
    val clickable = if (onClick != null) base.clickable(onClickLabel = clickLabel, onClick = onClick) else base

    Surface(
        modifier = clickable.semantics(mergeDescendants = true) { contentDescription = description },
        shape = shape,
        color = if (empty) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        border = if (empty) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!empty) {
                val avatar = remember(seat.name, seat.kind) {
                    Avatar.forSeed(if (seat.kind == SeatKind.CPU) "cpu-" + seat.name else seat.name)
                }
                // The card's own description already says who this is; the picture is decoration.
                AvatarBadge(avatar, size = 36.dp, animated = false, contentDescription = "")
            }
            Column(modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {}) {
                if (!empty) {
                    Text(
                        if (isYou) seat.name + " · " + stringResource(R.string.ls_seat_you) else seat.name,
                        style = MaterialTheme.typography.bodyLarge,
                        // A long default name ("Host (device model)") must not eat the card: two lines, then an ellipsis.
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (empty && addComputer != null) "+ " + stringResource(R.string.ls_action_add_computer) else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

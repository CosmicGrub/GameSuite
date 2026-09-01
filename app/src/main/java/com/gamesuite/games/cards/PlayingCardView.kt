package com.gamesuite.games.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared card face rendering — every card game draws its cards through this
 * so they share one visual language (shadow, rounded corners, face-down
 * back). Width/height are parameters so callers (rack, discard pile, board)
 * can size cards to their layout.
 */
@Composable
fun PlayingCardView(
    card: CardVisual,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 64.dp,
    height: androidx.compose.ui.unit.Dp = 92.dp
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (card.faceDown) Color(0xFF2B2B2B) else card.backgroundColor)
            .border(1.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (card.faceDown) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(Color(0xFF424242), RoundedCornerShape(6.dp))
                    .size(width - 16.dp, height - 16.dp)
            )
        } else {
            // Derived from the width this card was actually given, not a fixed
            // constant — so a caller applying LocalCardScale (see CardScale.kt)
            // to width/height gets proportionally scaled, still-legible text for
            // free, at either end of the resize range, without this composable
            // needing to know about the scale setting itself.
            val labelFontSize = (width.value * 0.26f).coerceIn(9f, 34f).sp
            if (card.cornerIndex != null) {
                Text(
                    card.cornerIndex,
                    color = card.textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = labelFontSize * 0.6f,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                )
            }
            Text(
                card.label,
                color = card.textColor,
                fontWeight = FontWeight.Bold,
                fontSize = labelFontSize
            )
        }
    }
}

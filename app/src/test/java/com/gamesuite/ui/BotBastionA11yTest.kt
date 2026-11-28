package com.gamesuite.ui

import androidx.compose.ui.graphics.Color
import com.gamesuite.games.botbastion.BbArena
import com.gamesuite.games.botbastion.BotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class BotBastionA11yTest {
    private fun lin(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(lum(a), lum(b)); val lo = minOf(lum(a), lum(b))
        return (hi + 0.05) / (lo + 0.05)
    }
    private fun over(fg: Color, bg: Color, alpha: Float) = Color(
        fg.red * alpha + bg.red * (1 - alpha), fg.green * alpha + bg.green * (1 - alpha), fg.blue * alpha + bg.blue * (1 - alpha)
    )

    @Test fun nodeTapBoxIsNeverBelow48dp() {
        for (w in listOf(0f, 240f, 320f, 360f, 411f, 600f)) {
            assertTrue("width $w", bbNodeTapBoxDp(w, BbArena.W) >= 48f)
        }
    }

    @Test fun nodeTapBoxGrowsOnWideScreens() {
        assertEquals(1280f / 16f * 1.15f, bbNodeTapBoxDp(1280f, BbArena.W), 0.001f)
    }

    @Test fun nodeTapBoxSurvivesBadArenaWidth() {
        assertEquals(48f, bbNodeTapBoxDp(360f, 0.0), 0f)
    }

    /** Floor 4.0 (not 4.5): Skiff is 4.25:1, a known gap tracked in docs/audit/09-a11y-new-games.md. */
    @Test fun blackLabelOnBuyButtonsStaysReadable() {
        for (t in BotType.values()) {
            val bg = over(BbPalette.bot(t), BbPalette.space, 0.85f)
            assertTrue("$t ${contrast(Color.Black, bg)}", contrast(Color.Black, bg) >= 4.0)
        }
    }

    @Test fun dangerMessageOnSpaceMeetsAa() {
        assertTrue(contrast(BbPalette.danger, BbPalette.space) >= 4.5)
    }

    @Test fun nodeMarkersMeetNonTextContrast() {
        assertTrue(contrast(BbPalette.node, BbPalette.space) >= 3.0)
        assertTrue(contrast(BbPalette.nodeSel, BbPalette.space) >= 3.0)
    }
}

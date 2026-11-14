package com.gamesuite.games.piedetective

/**
 * Colours and shapes of the screen that tests need without Compose (ARGB longs). The answer stamps follow the design
 * doc 1.6: Yes is a green CIRCLE with a TICK, No is an orange rounded SQUARE with a CROSS, so the answer never depends
 * on colour alone.
 */
object PieTokens {
    data class Scheme(
        val background: Long,
        val card: Long,
        val text: Long,
        val muted: Long,
        val accent: Long,
        val onAccent: Long,
        val yes: Long,
        val no: Long,
        val onYes: Long,
        val onNo: Long,
        val warn: Long
    )

    val light = Scheme(
        background = 0xFFFFF6E5, card = 0xFFFFEBC8, text = 0xFF3B2A1E, muted = 0xFF6B5444,
        accent = 0xFF8A2F5B, onAccent = 0xFFFFFFFF, yes = 0xFF1B7F4F, no = 0xFFB45309,
        onYes = 0xFFFFFFFF, onNo = 0xFFFFFFFF, warn = 0xFFB3261E
    )

    val dark = Scheme(
        background = 0xFF1F1812, card = 0xFF33281E, text = 0xFFFBEFD9, muted = 0xFFD2BFA8,
        accent = 0xFFF2A6C8, onAccent = 0xFF2A1220, yes = 0xFF4CD08C, no = 0xFFFFA45B,
        onYes = 0xFF10281C, onNo = 0xFF3A1A00, warn = 0xFFFF8A80
    )

    enum class StampShape { CIRCLE, ROUNDED_SQUARE }
    enum class StampIcon { TICK, CROSS }
    data class Stamp(val shape: StampShape, val icon: StampIcon, val light: Long, val dark: Long)

    val yesStamp = Stamp(StampShape.CIRCLE, StampIcon.TICK, light.yes, dark.yes)
    val noStamp = Stamp(StampShape.ROUNDED_SQUARE, StampIcon.CROSS, light.no, dark.no)

    fun stampFor(yes: Boolean): Stamp = if (yes) yesStamp else noStamp
}

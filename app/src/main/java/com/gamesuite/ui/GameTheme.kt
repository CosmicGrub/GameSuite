package com.gamesuite.ui

import com.gamesuite.core.HubFamily

/*
 * Per-game identity colours (docs/design/game-hub.md). Pure Kotlin: colours are plain ARGB Longs so
 * GameThemeTest can check the WCAG contrast of every pair on the JVM. The composable that turns a
 * pair into a Compose Color for the current theme is gameAccent() in GameCards.kt.
 */

/** One game's accent for the light and the dark theme, as 0xAARRGGBB. */
data class GameAccent(val light: Long, val dark: Long) {
    fun forDark(isDark: Boolean): Long = if (isDark) dark else light
}

/** WCAG 2.x relative luminance and contrast ratio, on opaque 0xAARRGGBB values (alpha is ignored). */
object Wcag {
    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /** The contrast ratio between two colours, 1.0 (identical) to 21.0 (black on white). */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}

object GameThemes {
    /** Text colour on a dark-theme accent surface (a button tinted with the accent). */
    const val ON_ACCENT_DARK: Long = 0xFF101014
    const val ON_ACCENT_LIGHT: Long = 0xFFFFFFFF

    /**
     * The reference card surfaces the accents are designed and tested against: the Classic scheme's
     * surface and Material's filled-card container (surfaceContainerHighest), in each theme. At
     * runtime [ensureContrast] nudges an accent for any other scheme (dynamic colour, high contrast).
     */
    val lightSurfaces: List<Long> = listOf(0xFFFFFBFE, 0xFFE6E0E9)
    val darkSurfaces: List<Long> = listOf(0xFF1C1B1F, 0xFF36343B)

    /** Required contrast between an accent and the surface it sits on (WCAG 1.4.11, non-text). */
    const val MIN_ACCENT_CONTRAST: Double = 3.0

    /** Required contrast of the label on an accent-tinted button (WCAG 1.4.3, normal text). */
    const val MIN_TEXT_CONTRAST: Double = 4.5

    /** One accent per game id. Light values keep white text at 4.8:1 or better, dark values keep near-black text at 5:1. */
    val accents: Map<String, GameAccent> = mapOf(
        "chess" to GameAccent(0xFF6823C7, 0xFF9D67E9),
        "checkers" to GameAccent(0xFFC74423, 0xFFE25532),
        "reversi" to GameAccent(0xFF167E64, 0xFF20DFAF),
        "mancala" to GameAccent(0xFFA95F1E, 0xFFDF7920),
        "connect-four" to GameAccent(0xFF2368C7, 0xFF3F84E4),
        "dots-and-boxes" to GameAccent(0xFF197B8F, 0xFF20BFDF),
        "tic-tac-toe" to GameAccent(0xFFC72368, 0xFFE54889),
        "dominoes" to GameAccent(0xFF867018, 0xFFDFB920),
        "word-tiles" to GameAccent(0xFF9C671C, 0xFFDF8F20),
        "uno" to GameAccent(0xFFC72331, 0xFFE64C59),
        "solitaire" to GameAccent(0xFF17823B, 0xFF20DF60),
        "hangman" to GameAccent(0xFFC322C3, 0xFFE029E0),
        "word-search" to GameAccent(0xFF167E7A, 0xFF20DFD9),
        "crossword" to GameAccent(0xFF2344C7, 0xFF637EE9),
        "word-guess" to GameAccent(0xFF1B8217, 0xFF26DF20),
        "sudoku" to GameAccent(0xFF1F75B2, 0xFF208FDF),
        "kenken" to GameAccent(0xFF3F23C7, 0xFF8875EB),
        "kakuro" to GameAccent(0xFF9623C7, 0xFFBB55E7),
        "nonogram" to GameAccent(0xFFC72396, 0xFFE43FB2),
        "minesweeper" to GameAccent(0xFF587915, 0xFF9FDF20),
        "lights-out" to GameAccent(0xFF797115, 0xFFDFCF20),
        "sliding-puzzle" to GameAccent(0xFFB65220, 0xFFDF6020),
        "color-flood" to GameAccent(0xFF1B7898, 0xFF20AFDF),
        "edge-match" to GameAccent(0xFF2323C7, 0xFF7979EC),
        "mastermind" to GameAccent(0xFFC7234C, 0xFFE64C73),
        "breakout" to GameAccent(0xFFC73923, 0xFFE4553F),
        "tower-defence" to GameAccent(0xFF397E16, 0xFF60DF20),
        "air-hockey" to GameAccent(0xFF1D78A5, 0xFF209FDF),
        "party-toolkit" to GameAccent(0xFFAC23C7, 0xFFCB48E5),
        "yahtzee" to GameAccent(0xFFB3261E, 0xFFEF6A5E),
        "backgammon" to GameAccent(0xFF7A4A12, 0xFFD9A15A),
        "game-2048" to GameAccent(0xFFB35A00, 0xFFF0A040),
        "snake" to GameAccent(0xFF2E7D32, 0xFF66BB6A),
        "flow-lines" to GameAccent(0xFF00697A, 0xFF4DD0E1),
        "mahjong-solitaire" to GameAccent(0xFF2A6F3B, 0xFF7BC98A),
        "gomoku" to GameAccent(0xFF8A5A1E, 0xFFE0A35A),
        "hex" to GameAccent(0xFF2B6CB0, 0xFF63A4E8),
        "nine-mens-morris" to GameAccent(0xFF8D5A1B, 0xFFD99A4E),
        "hare-and-hounds" to GameAccent(0xFF2E7D5B, 0xFF4FD0A0),
        "chinese-checkers" to GameAccent(0xFF7B4FBF, 0xFFB79CF2),
        "ludo" to GameAccent(0xFFC0392B, 0xFFFF8A75),
        "shogi" to GameAccent(0xFF7D5F12, 0xFFDDB23A),
        "mini-shogi" to GameAccent(0xFFA14A1A, 0xFFE88A50),
        "blackjack" to GameAccent(0xFF1B6B4A, 0xFF6FD0A3),
        "war" to GameAccent(0xFF6B4E2E, 0xFFD2A679),
        "texas-holdem" to GameAccent(0xFF8B1E3F, 0xFFF07C9E),
        "president" to GameAccent(0xFF86254A, 0xFFE8809F),
        "sevens" to GameAccent(0xFF4A55A2, 0xFF9FA8F0),
        "speed" to GameAccent(0xFFC2410C, 0xFFFF9A5C),
        "matching" to GameAccent(0xFF0E7490, 0xFF4FC3DC),
        "spider-solitaire" to GameAccent(0xFF3E5C76, 0xFF8FB3D1),
        "hanafuda" to GameAccent(0xFF9E2A56, 0xFFE88AB0),
        "darts" to GameAccent(0xFF840A84, 0xFFF371F3),
        "bowling" to GameAccent(0xFF202B6E, 0xFF8895DC),
        "carrom" to GameAccent(0xFF8C6A00, 0xFFF2C14E),
        "curling" to GameAccent(0xFF3E7488, 0xFF9ED0E0),
        "billiards" to GameAccent(0xFF0B6A5A, 0xFF4FD6B4),
        "mini-golf" to GameAccent(0xFF587514, 0xFFB8D95B),
        "toy-soccer" to GameAccent(0xFF537D38, 0xFF6F9956),
        "toy-tennis" to GameAccent(0xFF7D6F38, 0xFF998C56),
        "toy-boxing" to GameAccent(0xFF9A3412, 0xFFFB8A5C),
        "toy-baseball" to GameAccent(0xFF1E3A8A, 0xFF93AEF2),
        "slot-cars" to GameAccent(0xFFB5381E, 0xFFFF8F70),
        "fishing" to GameAccent(0xFF0B7A8F, 0xFF5CCBE6),
        "tank-battle" to GameAccent(0xFF5F6B1F, 0xFFC8D86A),
        "shooting-gallery" to GameAccent(0xFFB23A5C, 0xFFFF92A8),
        "six-ball-puzzle" to GameAccent(0xFF96438E, 0xFFB766AF),
        "pig-dice" to GameAccent(0xFFA55B76, 0xFFB2768C),
        "tap-race" to GameAccent(0xFF0F766E, 0xFF5EEAD4),
        "tug-of-war" to GameAccent(0xFF7C2D12, 0xFFFDBA74),
        "reaction-duel" to GameAccent(0xFF12507A, 0xFF86C8F5),
        "whack-a-mole" to GameAccent(0xFF9C5638, 0xFFF0B096),
        "light-trail" to GameAccent(0xFF00669B, 0xFF5BD2FF),
        "sumo-push" to GameAccent(0xFFA6402B, 0xFFF5927A),
        "paddle-duel" to GameAccent(0xFF711F9E, 0xFFC779F2),
        "rps-showdown" to GameAccent(0xFF9E1F78, 0xFFFF66D1),
        "chutes-race" to GameAccent(0xFF541380, 0xFFAF85CC),
        "draw-guess" to GameAccent(0xFFB45309, 0xFFFCD34D),
        "echo-pads" to GameAccent(0xFF5D4DB8, 0xFFB3A8F5),
        "act-it-out" to GameAccent(0xFF8E2B6D, 0xFFF08CCB),
        "bridge-builders" to GameAccent(0xFF8A5A00, 0xFFF2C46B)
    )

    /** What a game with no entry in [accents] (one added later) uses: a colour per family. */
    val familyFallback: Map<HubFamily, GameAccent> = mapOf(
        HubFamily.BOARD to GameAccent(0xFF2368C7, 0xFF3F84E4),
        HubFamily.CARD to GameAccent(0xFFC72331, 0xFFE64C59),
        HubFamily.WORD to GameAccent(0xFF167E64, 0xFF20DFAF),
        HubFamily.ARCADE_PUZZLE to GameAccent(0xFFA95F1E, 0xFFDF7920),
        HubFamily.TOOLKIT to GameAccent(0xFF5C6370, 0xFF9AA3B2)
    )

    fun accentFor(gameId: String, family: HubFamily): GameAccent =
        accents[gameId] ?: familyFallback.getValue(family)

    /** White or near-black, whichever reads better on [background]. */
    fun onAccent(background: Long): Long =
        if (Wcag.contrast(ON_ACCENT_LIGHT, background) >= Wcag.contrast(ON_ACCENT_DARK, background)) {
            ON_ACCENT_LIGHT
        } else {
            ON_ACCENT_DARK
        }

    /**
     * [color] unchanged when it already has [min] contrast against every background, otherwise
     * blended step by step toward white (dark surfaces) or black (light surfaces) until it does.
     */
    fun ensureContrast(color: Long, backgrounds: List<Long>, min: Double = MIN_ACCENT_CONTRAST): Long {
        if (backgrounds.isEmpty()) return color
        fun ok(c: Long) = backgrounds.all { Wcag.contrast(c, it) >= min }
        if (ok(color)) return color
        val darkSurfaces = backgrounds.map { Wcag.luminance(it) }.average() < 0.5
        val target = if (darkSurfaces) 0xFFFFFFFF else 0xFF000000
        for (step in 1..20) {
            val candidate = blend(color, target, step / 20.0)
            if (ok(candidate)) return candidate
        }
        return target
    }

    private fun blend(from: Long, to: Long, fraction: Double): Long {
        fun mix(shift: Int): Long {
            val a = ((from shr shift) and 0xFF).toDouble()
            val b = ((to shr shift) and 0xFF).toDouble()
            return Math.round(a + (b - a) * fraction).coerceIn(0L, 255L)
        }
        return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

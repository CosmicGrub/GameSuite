package com.gamesuite.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * A named color palette, independent of light/dark — see theme/AppTheme.kt.
 * Only CLASSIC and HIGH_CONTRAST ship with real palettes for now (settings
 * foundation phase); MIDNIGHT_ARCADE/FELT_TABLE are reserved enum slots for
 * the "fun" themes planned in docs/SETTINGS_THEMING_ACCESSIBILITY.md §2 so
 * adding them later is just a new ColorScheme, not a data-model change.
 */
enum class NamedTheme { CLASSIC, HIGH_CONTRAST, MIDNIGHT_ARCADE, FELT_TABLE }

enum class CpuDifficulty { EASY, MEDIUM, HARD }

/**
 * App-wide settings — everything that applies across all games. Per-game
 * settings (UNO house rules, per-game CPU difficulty overrides, etc.) are
 * a separate, later concern — see docs/SETTINGS_THEMING_ACCESSIBILITY.md §3.
 *
 * NOTE ON SCOPE: every field here is persisted and settable from the
 * Settings screen, but not all of them are wired into actual game behavior
 * yet. themeMode/dynamicColor/namedTheme drive AppTheme (fully wired).
 * soundEnabled gates CardSounds (fully wired). hapticsEnabled, textScale,
 * reducedMotion, colorblindMode, and defaultCpuDifficulty are stored and
 * toggleable now but not yet consumed by individual game screens — that's
 * documented, phased follow-up work (see the roadmap in README.md), not an
 * oversight.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val namedTheme: NamedTheme = NamedTheme.CLASSIC,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /** Multiplier applied on top of whatever the OS font scale already is. */
    val textScale: Float = 1.0f,
    val reducedMotion: Boolean = false,
    val colorblindMode: Boolean = false,
    val defaultCpuDifficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
    /**
     * 0f..1f slider position, not an absolute size — see
     * games/cards/CardScale.kt's rememberCardScaleMultiplier() for how this
     * maps to an actual device-aware card-size multiplier at render time.
     * Kept portable across devices for exactly that reason: the same 0.4f
     * means "cards look like GameSuite's original tuned size" whether it's
     * read back on the phone that set it or a different device signed into
     * the same settings later, since the min/max it's interpolated between
     * is computed fresh per-device, not baked in here.
     */
    val cardSizePreference: Float = 0.4f
)

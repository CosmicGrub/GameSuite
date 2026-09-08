package com.gamesuite.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * A named color palette, independent of light/dark — see theme/AppTheme.kt.
 * CLASSIC, HIGH_CONTRAST, and MIDNIGHT_ARCADE (which ports the ESP32
 * hardware arcade cabinet's own palette — see theme/Color.kt's
 * MidnightArcadeScheme) ship with real palettes; FELT_TABLE remains a
 * reserved enum slot for the other "fun" theme planned in
 * docs/SETTINGS_THEMING_ACCESSIBILITY.md §2, so adding it later is just a
 * new ColorScheme, not a data-model change.
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
 * soundEnabled gates CardSounds (fully wired). musicEnabled is stored and
 * toggleable now, wired into com.gamesuite.audio.AmbientMusicEngine's public
 * API, but not yet consumed by individual game screens — that wiring is a
 * later pass (see AmbientMusicEngine.kt's KDoc). hapticsEnabled, textScale,
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
    /** Generative ambient background music (see com.gamesuite.audio.AmbientMusicEngine).
     *  Still hard-gated by [soundEnabled] at each call site — turning off all
     *  sound must silence music too, regardless of this being on. */
    val musicEnabled: Boolean = true,
    /** Multiplier applied on top of whatever the OS font scale already is. */
    val textScale: Float = 1.0f,
    val reducedMotion: Boolean = false,
    val colorblindMode: Boolean = false,
    /**
     * "3D Perspective Mode" (Settings -> Display; named "3D Mode"/"3D card
     * mode" before the animation/physics pitch's cross-cutting finding that
     * it should cover board games too) — an opt-in upgrade from flat 2D
     * rendering to real perspective transforms (graphicsLayer rotationX/Y/Z
     * + cameraDistance): cards and pieces get visible depth/thickness, flips
     * rotate through actual 3D space instead of a flat scale-fade, and every
     * board/table surface gets a slight resting tilt. Off by default -- it's
     * a taste choice, not a correctness fix, and some players will prefer
     * the original flat look. See games/cards/Card3D.kt (card-specific
     * transforms) and ui/TablePerspective.kt (the board-agnostic table/board
     * tilt) for the shared helpers every game screen reads this through.
     * Always fully suppressed when reducedMotion is on, same as every other
     * non-essential animation.
     */
    val card3DEnabled: Boolean = false,
    /**
     * "Enhanced Move Animations" (Settings -> Display) — an opt-in upgrade
     * from an instant snap to a weighted, physics-feel motion for pieces/
     * cards/tiles actually moving: lift-and-place arcs, capture fade-outs,
     * fly-to-target tosses, tile slides. Distinct from [card3DEnabled]:
     * this is 2D/2.5D motion quality (does something get there with weight
     * and momentum, or does it just appear), not perspective/depth
     * rendering (does it look like it's sitting in 3D space) — the two
     * compose independently, e.g. a lift-and-place arc can run with or
     * without a perspective tilt underneath it. Defaults to true since,
     * unlike card3DEnabled, this isn't a stylistic fork most players would
     * want to opt into — it's a strict improvement over a hard snap, with
     * an off switch for players who'd rather everything resolve instantly
     * (or on very low-end devices). Always fully suppressed when
     * reducedMotion is on, same as every other non-essential animation.
     */
    val enhancedAnimationsEnabled: Boolean = true,
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
    val cardSizePreference: Float = 0.4f,
    /**
     * A `ws://` or `wss://` URL for [com.gamesuite.transport.OnlineTransport]'s relay
     * server (roadmap item 12) — see server/README.md. Empty means "not configured";
     * the Online lobby screens block Host/Join until this is set, same spirit as
     * Nearby's radio-enabled gate. Deliberately just a string, not validated here —
     * a malformed URL simply fails to connect, surfaced via OnlineTransport's own
     * connectionError, no need to duplicate that validation in two places.
     */
    val onlineServerUrl: String = "",
    /** True once the player has dismissed the main menu's first-run welcome banner — see
     *  MainMenuScreen's OnboardingBanner. Not shown again after that, on this device. */
    val hasSeenOnboarding: Boolean = false
)

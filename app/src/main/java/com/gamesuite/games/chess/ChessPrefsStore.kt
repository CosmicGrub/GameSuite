package com.gamesuite.games.chess

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Split out of ChessGame.kt for docs/ENGINE_DECISION.md Action Item 4 ("ChessGame.kt
// persistence untangling"): this is the one place in this package that is genuinely
// Android-only (Context, DataStore, coroutines Flow) -- it used to be co-located directly in
// ChessGame.kt (that file's own top-of-file comment at the time reasoned "this bundle's file
// list is exactly {ChessGame.kt, ChessScreen.kt}", so a third file felt unnecessary), but that
// meant Android-only imports sat in the same file as ChessGame's otherwise fully portable
// rules engine -- exactly the kind of thing that would have silently broken a future
// commonMain port the moment ChessGame.kt was copied wholesale, the way TicTacToeGame.kt and
// AirHockeyGame.kt already were for docs/ENGINE_DECISION.md Action Items 1 and 3. Splitting
// this out costs nothing at any existing call site: ChessScreen.kt already imports
// ChessPieceStyle/ChessMotionTier/ChessPrefsStore by fully-qualified name (see its own
// `import com.gamesuite.games.chess.ChessPrefsStore` etc.), and Kotlin resolves same-package
// symbols regardless of which file in the package declares them -- this move needed no edit
// anywhere outside this package.
//
// Mirrors the small per-game-DataStore shape `games/solitaire/SolitairePrefsStore.kt` already
// established for Solitaire's draw-1/draw-3 preference -- this file is Chess's own version of
// that same pattern, just arriving one refactor later than Solitaire's did.

/** Selectable piece-silhouette art sets for ChessScreen's board rendering -- see
 *  ChessScreen.kt's `buildPieceSilhouette`. Persisted via [ChessPrefsStore]. */
enum class ChessPieceStyle { CLASSIC, MINIMALIST }

/**
 * Chess's own genuine 3-tier motion-intensity split (on top of, not instead of, the app-wide
 * [com.gamesuite.settings.LocalEnhancedAnimations] / [com.gamesuite.settings.LocalReducedMotion]
 * gates ChessScreen already reads): STANDARD is everything this screen already shipped --
 * weighted lift/slide, capture fade-out, promotion flip. MAXIMUM additionally arms the
 * checkmate-specific hit-stop + camera-push and the board's specular-sweep sheen. Persisted via
 * [ChessPrefsStore], with a visible toggle in ChessScreen.kt.
 */
enum class ChessMotionTier { STANDARD, MAXIMUM }

private val Context.chessPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "chess_prefs")

/** Never read/written directly from a @Composable -- ChessScreen collects both flows as
 *  state and calls the setters from its own toggle controls, exactly like
 *  SolitaireScreen does with SolitairePrefsStore. */
class ChessPrefsStore(private val context: Context) {
    private object Keys {
        val PIECE_STYLE = stringPreferencesKey("piece_style")
        val MOTION_TIER = stringPreferencesKey("motion_tier")
    }

    val pieceStyle: Flow<ChessPieceStyle> = context.chessPrefsDataStore.data.map { prefs ->
        prefs[Keys.PIECE_STYLE]?.let { runCatching { ChessPieceStyle.valueOf(it) }.getOrNull() } ?: ChessPieceStyle.CLASSIC
    }

    suspend fun setPieceStyle(style: ChessPieceStyle) {
        context.chessPrefsDataStore.edit { it[Keys.PIECE_STYLE] = style.name }
    }

    /** Standard (the default) until the player opts into Maximum. */
    val motionTier: Flow<ChessMotionTier> = context.chessPrefsDataStore.data.map { prefs ->
        prefs[Keys.MOTION_TIER]?.let { runCatching { ChessMotionTier.valueOf(it) }.getOrNull() } ?: ChessMotionTier.STANDARD
    }

    suspend fun setMotionTier(tier: ChessMotionTier) {
        context.chessPrefsDataStore.edit { it[Keys.MOTION_TIER] = tier.name }
    }
}

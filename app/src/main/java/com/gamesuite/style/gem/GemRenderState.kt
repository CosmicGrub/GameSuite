package com.gamesuite.style.gem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextMeasurer
import com.gamesuite.games.gemcascade.GemBoard
import com.gamesuite.games.gemcascade.Pos
import com.gamesuite.style.AtlasManifest
import com.gamesuite.style.BoardCamera
import com.gamesuite.style.DrawCommandBudget
import com.gamesuite.style.ResolvedStyle
import com.gamesuite.ui.GemPaints

/**
 * What the screen's playback coroutine mutates and the draw scope reads. Only the draw scope reads it per frame, so a
 * frame recomposes nothing. (Moved here from GemCascadeScreen so every art tier draws from the same state.)
 */
class GemDrawState {
    var board: GemBoard = GemBoard(8, 8)
    val offsets = HashMap<Pos, Offset>()
    val fading = HashSet<Pos>()
    val glow = HashSet<Pos>()
    val pop = HashSet<Pos>()
    val rings = HashSet<Pos>()
    var hint: Set<Pos> = emptySet()
    var selected: Pos? = null
    var scan: Pos? = null
    var chainText: String? = null
    var scoreText: String? = null
    var textAt: Offset = Offset(3f, 3f)
    /** Cascade multiplier glow level (0 = none, else the chain length). */
    var chainLevel: Int = 0
    /** Twist: the scan highlight covers a 2 x 2 block. */
    var scanBlock: Boolean = false
    val bursts = ArrayList<ActiveBurst>()
}

class GemPalette(val dark: Boolean, val fg: Color, val accent: Color, val bgBoard: Color, val tileA: Color, val tileB: Color)

/** A decoded sprite atlas plus its manifest (Compose side of style/SpriteAtlas.kt). */
class GemAtlas(val manifest: AtlasManifest, val image: ImageBitmap)

/** Everything one frame needs, assembled in the Canvas lambda. */
internal class GemFrame(
    val state: GemDrawState,
    /** Current step progress 0..1. */
    val t: Float,
    /** Floating text progress 0..1 (1 = finished). */
    val floatT: Float,
    /** Clock for bursts, ms. */
    val nowMs: Long,
    val cellPx: Float,
    val rows: Int,
    val cols: Int,
    val reduced: Boolean,
    val text: TextMeasurer,
    val palette: GemPalette,
    val paints: GemPaints,
    val style: ResolvedStyle,
    val camera: BoardCamera,
    val atlas: GemAtlas?,
    val budget: DrawCommandBudget,
    /** Called once when a sprite could not be drawn (missing atlas or sprite): the screen demotes PIECE for the session. */
    val onSpriteMissing: () -> Unit
)

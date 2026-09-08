package com.gamesuite.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Shared AGSL (`RuntimeShader`) infrastructure for the "premium 2026 vision"
 * pitch's cross-cutting finding: Checkers' piece sheen, Chess's board/piece
 * sheen, UNO's Wild-card foil, Solitaire's face-card sheen, Word Tiles'
 * tile gloss, and Air Hockey's puck specular all want the same SHAPE of
 * effect (a soft specular band drifting across a surface) with a different
 * tint color. Built once here, in exactly one place, instead of seven
 * per-game bespoke shaders — the same reasoning [com.gamesuite.ui.TablePerspective]
 * already established for the whole-surface tilt.
 *
 * `RuntimeShader` is API 33 (Tiramisu)+. Every entry point below is gated
 * with an inline `Build.VERSION.SDK_INT` check (so Android Lint's own
 * NewApi dataflow analysis recognizes the guard) AND wraps shader
 * construction/use in `runCatching` — a genuine AGSL syntax mistake fails
 * only at runtime, never at Kotlin compile time, so a defensive fallback to
 * plain, unshaded rendering is required, not optional, here. Below API 33,
 * or on any shader failure for any reason, callers silently get their
 * modifier chain back unchanged — this must never be able to crash a
 * screen, only skip its own flourish.
 */
object PremiumShaders {
    /** Cheap, cheatable check for call sites that want to skip building
     *  shader-dependent state entirely (e.g. not even starting an
     *  [androidx.compose.animation.core.rememberInfiniteTransition]) when
     *  shaders can never run on this device. */
    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // A soft specular band at a fixed diagonal angle, animated by a single
    // `time` uniform (0..1, looping). `composable` is AGSL's standard name
    // for "whatever this shader is wrapping" when used as a RenderEffect
    // child shader — this reads the real rendered content of the layer
    // it's applied to and adds a tinted highlight on top of it, rather than
    // replacing it.
    internal const val SPECULAR_SWEEP_AGSL = """
        uniform shader composable;
        uniform float2 resolution;
        uniform float time;
        uniform float4 tint;

        half4 main(float2 coord) {
            half4 base = composable.eval(coord);
            float2 uv = resolution.x > 0.0 && resolution.y > 0.0 ? coord / resolution : coord;
            float diag = uv.x + uv.y;
            float sweepPos = fract(time) * 2.6 - 0.8;
            float dist = abs(diag - sweepPos);
            float band = smoothstep(0.22, 0.0, dist);
            half4 sheen = half4(tint.rgb, 1.0) * band * tint.a;
            return half4(base.rgb + sheen.rgb, base.a);
        }
    """
}

/**
 * Applies [PremiumShaders]' shared specular-sweep effect to this modifier's
 * layer, tinted with [tint], looping every [periodMs]. A pure no-op (returns
 * this unchanged) below API 33, when [enabled] is false, or if shader
 * construction/use fails for any reason at runtime.
 */
@Composable
fun Modifier.specularSweep(enabled: Boolean, tint: Color, periodMs: Int = 3200): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.specularSweepApi33(tint, periodMs)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.specularSweepApi33(tint: Color, periodMs: Int): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "specularSweep")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(periodMs, easing = LinearEasing)),
        label = "specularSweepTime"
    )
    val shader = remember { runCatching { RuntimeShader(PremiumShaders.SPECULAR_SWEEP_AGSL) }.getOrNull() }
        ?: return this
    return this.then(
        Modifier.graphicsLayer {
            runCatching {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", time)
                shader.setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "composable").asComposeRenderEffect()
            }.onFailure { renderEffect = null }
        }
    )
}

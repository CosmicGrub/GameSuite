package com.gamesuite.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

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

    // A one-shot warm bloom + expanding ring, drawn as its OWN additive-blended layer
    // rather than post-processing existing content the way SPECULAR_SWEEP_AGSL does --
    // there is no `composable` child shader to sample here, this shader computes its
    // color purely from coord/resolution/progress/tint, which is what lets callers just
    // drop it onto any existing modifier chain via `drawWithContent` instead of needing
    // to own the whole layer's RenderEffect. `progress` runs 0 (trigger moment, brightest)
    // to 1 (fully faded, effectively invisible) over one playback, never loops.
    internal const val VICTORY_GLOW_AGSL = """
        uniform float2 resolution;
        uniform float progress;
        uniform float4 tint;

        half4 main(float2 coord) {
            float2 uv = resolution.x > 0.0 && resolution.y > 0.0 ? coord / resolution : coord;
            float aspect = resolution.x > 0.0 && resolution.y > 0.0 ? resolution.x / resolution.y : 1.0;
            float2 centered = uv - float2(0.5, 0.5);
            centered.x *= aspect;
            float dist = length(centered);
            float ringRadius = progress * 0.9;
            float ring = smoothstep(0.16, 0.0, abs(dist - ringRadius)) * (1.0 - progress);
            float glow = (1.0 - progress) * (1.0 - progress) * 0.5;
            float intensity = glow + ring * 0.8;
            return half4(tint.rgb * tint.a * intensity, tint.a * intensity);
        }
    """

    // A softly drifting three-color "mesh gradient" (three moving blend centers,
    // inverse-square-weighted) for a screen's own root background -- meant to replace a
    // flat/static gradient behind chrome, never behind gameplay-color-coded content (see
    // AppTheme.kt's own rule: gameplay colors are fixed literals, only chrome reads
    // MaterialTheme.colorScheme -- these three tints should always come from the theme's
    // own container colors, never a game's own palette).
    internal const val MESH_GRADIENT_AGSL = """
        uniform float2 resolution;
        uniform float time;
        uniform float4 colorA;
        uniform float4 colorB;
        uniform float4 colorC;

        half4 main(float2 coord) {
            float2 uv = resolution.x > 0.0 && resolution.y > 0.0 ? coord / resolution : coord;
            float t = time * 6.2831853;
            float2 p1 = float2(0.3 + 0.18 * sin(t * 0.7), 0.25 + 0.18 * cos(t * 0.9));
            float2 p2 = float2(0.75 + 0.18 * cos(t * 0.5), 0.7 + 0.18 * sin(t * 1.1));
            float2 p3 = float2(0.5 + 0.22 * sin(t * 0.3 + 2.0), 0.5 + 0.22 * cos(t * 0.4 + 1.0));
            float d1 = distance(uv, p1);
            float d2 = distance(uv, p2);
            float d3 = distance(uv, p3);
            float w1 = 1.0 / (0.05 + d1 * d1 * 4.0);
            float w2 = 1.0 / (0.05 + d2 * d2 * 4.0);
            float w3 = 1.0 / (0.05 + d3 * d3 * 4.0);
            float wsum = w1 + w2 + w3;
            half3 blended = half3((colorA.rgb * w1 + colorB.rgb * w2 + colorC.rgb * w3) / wsum);
            return half4(blended, 1.0);
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

/**
 * The shared "big win" flourish: a warm bloom + expanding ring, additive-blended on top
 * of this modifier's own content. Fires once, immediately, the first time this modifier
 * enters composition — for panels/content that only exist AT ALL during the win moment
 * itself (e.g. a `MatchOverContent`/`RoundOverPanel`-style composable that a screen only
 * shows once the match is actually over), where there's no separate boolean left to key
 * a restart on. A pure no-op below API 33 or on any shader failure — see [PremiumShaders]'s
 * own doc for why that fallback is mandatory here, not optional.
 */
@Composable
fun Modifier.victoryGlow(tint: Color = Color(0xFFFFD54F), durationMs: Int = 900): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.victoryGlowApi33(active = true, restartKey = Unit, tint = tint, durationMs = durationMs)
}

/**
 * The same shared "big win" flourish as the no-arg [victoryGlow] above, but for screens
 * that stay mounted across a whole match/puzzle and expose a `solved`/`matchOver`-style
 * state field instead of being freshly composed at the win moment — fires each time
 * [trigger] flips from false to true, and does nothing when it flips back to false (a new
 * match/puzzle starting), so it never replays on anything but a genuine new win.
 */
@Composable
fun Modifier.victoryGlow(trigger: Boolean, tint: Color = Color(0xFFFFD54F), durationMs: Int = 900): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.victoryGlowApi33(active = trigger, restartKey = trigger, tint = tint, durationMs = durationMs)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.victoryGlowApi33(active: Boolean, restartKey: Any, tint: Color, durationMs: Int): Modifier {
    val progress = remember { Animatable(if (active) 0f else 1f) }
    LaunchedEffect(restartKey) {
        if (active) {
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = tween(durationMs, easing = LinearOutSlowInEasing))
        }
    }
    if (progress.value >= 1f) return this
    val shader = remember { runCatching { RuntimeShader(PremiumShaders.VICTORY_GLOW_AGSL) }.getOrNull() }
        ?: return this
    return this.drawWithContent {
        drawContent()
        runCatching {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("progress", progress.value)
            shader.setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
            drawRect(brush = ShaderBrush(shader), blendMode = BlendMode.Plus)
        }
    }
}

/**
 * A softly drifting, animated three-color "mesh gradient" background — meant to replace a
 * flat/static gradient behind a screen's own chrome (see [PremiumShaders.MESH_GRADIENT_AGSL]'s
 * own doc on why [colorA]/[colorB]/[colorC] should always be theme container colors, never a
 * game's own fixed-literal palette). Drawn as this modifier's own background, with the existing
 * content drawn on top of it afterward. A pure no-op below API 33, when [enabled] is false, or
 * on any shader failure — in every one of those cases callers get their existing content back
 * completely unchanged, never a crash and never a blank screen.
 */
@Composable
fun Modifier.meshGradientBackground(
    enabled: Boolean,
    colorA: Color,
    colorB: Color,
    colorC: Color,
    periodMs: Int = 14000
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.meshGradientBackgroundApi33(colorA, colorB, colorC, periodMs)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.meshGradientBackgroundApi33(
    colorA: Color,
    colorB: Color,
    colorC: Color,
    periodMs: Int
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "meshGradient")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(periodMs, easing = LinearEasing)),
        label = "meshGradientTime"
    )
    val shader = remember { runCatching { RuntimeShader(PremiumShaders.MESH_GRADIENT_AGSL) }.getOrNull() }
        ?: return this
    return this.drawWithContent {
        runCatching {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("colorA", colorA.red, colorA.green, colorA.blue, colorA.alpha)
            shader.setFloatUniform("colorB", colorB.red, colorB.green, colorB.blue, colorB.alpha)
            shader.setFloatUniform("colorC", colorC.red, colorC.green, colorC.blue, colorC.alpha)
            drawRect(brush = ShaderBrush(shader))
        }
        drawContent()
    }
}

/**
 * Requests real, OS-compositor-level backdrop blur (Android 12/API 31's
 * `Window.setBackgroundBlurRadius`) behind a Compose `Dialog`/`AlertDialog`'s own window --
 * call this from inside one of that dialog's own content slots (`title`, `text`, etc.), where
 * [LocalView]'s parent is a [DialogWindowProvider] wrapping the dialog's real platform `Window`.
 * This is deliberately NOT the AGSL/RenderEffect-blur approach the rest of this file uses: a
 * standard `AlertDialog` renders in its own separate platform Window, so blurring "what's
 * behind it" is a real compositor operation on that OTHER window, not something a Modifier on
 * the calling screen's own content could reach — casting `LocalView.current.parent` to
 * `DialogWindowProvider` inside the dialog's own composition is the well-established way to
 * reach that window from Compose without giving up AlertDialog's own built-in accessibility
 * behavior (focus trapping, back-button dismiss, screen-reader announcements) by reimplementing
 * it as a custom overlay just to get a blur.
 *
 * A true no-op below API 31, if the parent isn't a DialogWindowProvider for any reason (a
 * future Compose version changes this internal detail, a preview/test environment, etc.), or
 * on any other failure — this must never be able to crash a dialog, only skip its own blur.
 */
@Composable
fun DialogBackdropBlur(radiusPx: Int = 48) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    LaunchedEffect(view) {
        runCatching {
            (view.parent as? DialogWindowProvider)?.window?.setBackgroundBlurRadius(radiusPx)
        }
    }
}

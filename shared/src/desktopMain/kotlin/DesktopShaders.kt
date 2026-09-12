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
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Desktop-native shader parity for the Android app's real "premium 2026 vision" pitch
 * (com.gamesuite.ui.effects.PremiumShaders / .specularSweep on the Android side), landed
 * for docs/ENGINE_DECISION.md Action Item 8's Desktop-parity scope. NOT a shared-module
 * concern: shader/render code is UI, and UI deliberately stays native per-platform in this
 * ADR (see Main.kt's own top comment) -- this is the Desktop equivalent of Android's own
 * Android-only implementation, not something either side could have put in commonMain.
 *
 * The visual EFFECT is identical (a soft specular band sweeping diagonally across a
 * surface, looping), but the shader SOURCE is not the literal same text: Android's version
 * (PremiumShaders.SPECULAR_SWEEP_AGSL) is written in AGSL, which is Android's own
 * Tiramisu+-only extension of Skia's shading language -- it uses `half4`/`half2` (a
 * half-precision AGSL extension type) and Android's own `RuntimeShader` class
 * (android.graphics.RuntimeShader, API 33+, genuinely absent from any non-Android
 * classpath). Compose Multiplatform Desktop renders via Skia directly (Skiko), whose own
 * public shader entry point is `org.jetbrains.skia.RuntimeEffect` -- real Skia SkSL, which
 * does NOT accept AGSL's `half4`/`half2` types (`vec4`/`float2`/`float4` only) even though
 * the two dialects are closely related. So this is a real, deliberate re-authoring of the
 * same shader in Skia's own dialect, not a copy-paste of the AGSL source string -- verified
 * against Skia's actual documented RuntimeEffect API (org.jetbrains.skia.RuntimeEffect.
 * makeForShader / RuntimeShaderBuilder.uniform / ImageFilter.makeRuntimeShader), not
 * assumed from AGSL familiarity alone.
 */
object DesktopShaders {
    // Same shape as PremiumShaders.SPECULAR_SWEEP_AGSL, translated to real Skia SkSL:
    // vec4/float2 throughout (no AGSL half-precision types), `main` returns vec4 not half4.
    internal const val SPECULAR_SWEEP_SKSL = """
        uniform shader composable;
        uniform float2 resolution;
        uniform float time;
        uniform float4 tint;

        vec4 main(float2 coord) {
            vec4 base = composable.eval(coord);
            float2 uv = resolution.x > 0.0 && resolution.y > 0.0 ? coord / resolution : coord;
            float diag = uv.x + uv.y;
            float sweepPos = fract(time) * 2.6 - 0.8;
            float dist = abs(diag - sweepPos);
            float band = smoothstep(0.22, 0.0, dist);
            vec4 sheen = vec4(tint.rgb, 1.0) * band * tint.a;
            return vec4(base.rgb + sheen.rgb, base.a);
        }
    """

    /** Built once per process -- RuntimeEffect compilation is the expensive part; the
     *  builder created from it is cheap to re-configure every frame (see [specularSweep]). */
    val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(SPECULAR_SWEEP_SKSL) }.getOrNull()
    }

    val isSupported: Boolean get() = effect != null
}

/**
 * Desktop equivalent of the Android app's `Modifier.specularSweep` (see
 * com.gamesuite.ui.effects.PremiumShaders.kt) -- a soft specular band drifting across this
 * modifier's own rendered layer, tinted with [tint], looping every [periodMs]. A pure no-op
 * (returns this unchanged) if Skia shader compilation ever fails for any reason, mirroring
 * the Android version's own defensive fallback (a real AGSL/SkSL syntax mistake fails only
 * at shader-compile time, never at Kotlin compile time).
 */
@Composable
fun Modifier.specularSweep(tint: Color, periodMs: Int = 3200): Modifier {
    val effect = DesktopShaders.effect ?: return this

    val infiniteTransition = rememberInfiniteTransition(label = "specularSweep")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(periodMs, easing = LinearEasing)),
        label = "specularSweepTime"
    )
    val builder = remember(effect) { RuntimeShaderBuilder(effect) }

    return this.then(
        Modifier.graphicsLayer {
            runCatching {
                builder.uniform("resolution", size.width, size.height)
                builder.uniform("time", time)
                builder.uniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
                renderEffect = ImageFilter.makeRuntimeShader(
                    runtimeShaderBuilder = builder,
                    shaderNames = arrayOf("composable"),
                    inputs = arrayOf(null)
                ).asComposeRenderEffect()
            }.onFailure { renderEffect = null }
        }
    )
}

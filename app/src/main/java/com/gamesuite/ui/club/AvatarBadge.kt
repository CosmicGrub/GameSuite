package com.gamesuite.ui.club

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.Avatar
import com.gamesuite.core.AvatarCharacter
import com.gamesuite.core.AvatarColor
import com.gamesuite.core.AvatarEars
import com.gamesuite.core.AvatarEyes
import com.gamesuite.core.AvatarFrame
import com.gamesuite.core.AvatarMouth
import com.gamesuite.core.AvatarPalette
import com.gamesuite.core.AvatarProp
import com.gamesuite.settings.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@StringRes
internal fun characterNameRes(c: AvatarCharacter): Int = when (c) {
    AvatarCharacter.PIP -> R.string.club_avatar_pip
    AvatarCharacter.MOCHI -> R.string.club_avatar_mochi
    AvatarCharacter.BOLT -> R.string.club_avatar_bolt
    AvatarCharacter.NOVA -> R.string.club_avatar_nova
    AvatarCharacter.FERN -> R.string.club_avatar_fern
    AvatarCharacter.EMBER -> R.string.club_avatar_ember
    AvatarCharacter.WHISK -> R.string.club_avatar_whisk
    AvatarCharacter.BUN -> R.string.club_avatar_bun
    AvatarCharacter.DASH -> R.string.club_avatar_dash
    AvatarCharacter.PEBBLE -> R.string.club_avatar_pebble
    AvatarCharacter.SPROUT -> R.string.club_avatar_sprout
    AvatarCharacter.ZIP -> R.string.club_avatar_zip
    AvatarCharacter.TOAST -> R.string.club_avatar_toast
    AvatarCharacter.GIZMO -> R.string.club_avatar_gizmo
    AvatarCharacter.PUFF -> R.string.club_avatar_puff
    AvatarCharacter.REX -> R.string.club_avatar_rex
}

@StringRes
internal fun colorNameRes(c: AvatarColor): Int = when (c) {
    AvatarColor.ORANGE -> R.string.club_color_orange
    AvatarColor.SKY -> R.string.club_color_sky
    AvatarColor.GREEN -> R.string.club_color_green
    AvatarColor.YELLOW -> R.string.club_color_yellow
    AvatarColor.BLUE -> R.string.club_color_blue
    AvatarColor.VERMILION -> R.string.club_color_vermilion
    AvatarColor.PINK -> R.string.club_color_pink
    AvatarColor.GREY -> R.string.club_color_grey
}

@StringRes
internal fun frameNameRes(f: AvatarFrame): Int = when (f) {
    AvatarFrame.CIRCLE -> R.string.club_frame_circle
    AvatarFrame.ROUNDED_SQUARE -> R.string.club_frame_square
    AvatarFrame.HEXAGON -> R.string.club_frame_hexagon
    AvatarFrame.DIAMOND -> R.string.club_frame_diamond
}

@Composable
internal fun avatarDescription(avatar: Avatar): String = stringResource(
    R.string.club_avatar_cd,
    stringResource(characterNameRes(avatar.character)),
    stringResource(colorNameRes(avatar.color)),
    stringResource(frameNameRes(avatar.frame))
)

/**
 * A player's avatar: an original vector critter (ears, eyes, mouth, accessory) in a coloured frame,
 * drawn on a Canvas. [animated] adds a gentle bob and an occasional blink; it is skipped entirely when
 * the player has Reduced Motion on ([LocalReducedMotion]). Pass [animated] = false in dense grids.
 * [contentDescription] defaults to "Pip, sky blue, circle frame"; pass "" to hide it from TalkBack when
 * a label beside it already says the same thing.
 */
@Composable
fun AvatarBadge(
    avatar: Avatar,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    animated: Boolean = true,
    contentDescription: String? = null
) {
    val reduced = LocalReducedMotion.current
    val description = contentDescription ?: avatarDescription(avatar)
    val live = animated && !reduced
    val t = if (live) {
        rememberInfiniteTransition(label = "avatar-idle").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "avatar-phase"
        ).value
    } else 0f
    val bob = if (live) sin(t * 2f * PI.toFloat()) * 0.014f else 0f
    // A quick blink near the end of each cycle.
    val blink = if (live && t > 0.90f && t < 0.97f) 1f - abs((t - 0.935f) / 0.035f) else 0f
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Image
                if (description.isNotEmpty()) this.contentDescription = description
            }
    ) { drawAvatar(avatar, bob, blink.coerceIn(0f, 1f)) }
}

private fun argb(v: Long) = Color(v.toInt())

/** Draws [avatar] filling this DrawScope's square. [bob] shifts the critter vertically (fraction of size); [blink] 0..1 closes the eyes. */
internal fun DrawScope.drawAvatar(avatar: Avatar, bob: Float = 0f, blink: Float = 0f) {
    val s = size.minDimension
    val body = argb(avatar.color.argb)
    val ink = argb(AvatarPalette.inkFor(avatar.color.argb))
    val outline = argb(AvatarPalette.INK_DARK)
    fun p(v: Float) = v * s
    fun o(x: Float, y: Float) = Offset(p(x), p(y))
    val line = s * 0.018f

    // ---- frame ----
    val frameFill = body.copy(alpha = 0.30f)
    val frameStroke = Stroke(width = s * 0.04f, join = StrokeJoin.Round)
    val framePath = Path()
    when (avatar.frame) {
        AvatarFrame.CIRCLE -> {
            drawCircle(frameFill, radius = s * 0.48f, center = o(0.5f, 0.5f))
            drawCircle(body, radius = s * 0.48f, center = o(0.5f, 0.5f), style = frameStroke)
        }
        AvatarFrame.ROUNDED_SQUARE -> {
            val cr = CornerRadius(s * 0.22f)
            drawRoundRect(frameFill, o(0.03f, 0.03f), Size(s * 0.94f, s * 0.94f), cr)
            drawRoundRect(body, o(0.03f, 0.03f), Size(s * 0.94f, s * 0.94f), cr, style = frameStroke)
        }
        AvatarFrame.HEXAGON -> {
            for (i in 0 until 6) {
                val a = (PI / 3.0 * i - PI / 2.0).toFloat()
                val x = p(0.5f + 0.48f * cos(a))
                val y = p(0.5f + 0.48f * sin(a))
                if (i == 0) framePath.moveTo(x, y) else framePath.lineTo(x, y)
            }
            framePath.close()
            drawPath(framePath, frameFill)
            drawPath(framePath, body, style = frameStroke)
        }
        AvatarFrame.DIAMOND -> {
            framePath.moveTo(p(0.5f), p(0.02f)); framePath.lineTo(p(0.98f), p(0.5f))
            framePath.lineTo(p(0.5f), p(0.98f)); framePath.lineTo(p(0.02f), p(0.5f)); framePath.close()
            drawPath(framePath, frameFill)
            drawPath(framePath, body, style = frameStroke)
        }
    }

    val critterScale = when (avatar.frame) {
        AvatarFrame.CIRCLE -> 0.92f
        AvatarFrame.ROUNDED_SQUARE -> 0.94f
        AvatarFrame.HEXAGON -> 0.86f
        AvatarFrame.DIAMOND -> 0.72f
    }
    withTransform({
        translate(left = 0f, top = p(bob))
        scale(critterScale, critterScale, pivot = o(0.5f, 0.52f))
    }) {
        val ch = avatar.character
        val outlineStroke = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val outlineColor = outline.copy(alpha = 0.55f)

        fun blob(path: Path, fill: Color) {
            drawPath(path, fill)
            drawPath(path, outlineColor, style = outlineStroke)
        }

        // ---- ears (behind the body) ----
        when (ch.ears) {
            AvatarEars.NONE -> Unit
            AvatarEars.ROUND -> listOf(0.27f, 0.73f).forEach { cx ->
                drawCircle(body, p(0.095f), o(cx, 0.30f))
                drawCircle(outlineColor, p(0.095f), o(cx, 0.30f), style = outlineStroke)
            }
            AvatarEars.POINT -> listOf(false, true).forEach { mirror ->
                fun mx(x: Float) = if (mirror) 1f - x else x
                val path = Path().apply {
                    moveTo(p(mx(0.21f)), p(0.42f)); lineTo(p(mx(0.25f)), p(0.12f)); lineTo(p(mx(0.46f)), p(0.30f)); close()
                }
                blob(path, body)
            }
            AvatarEars.LONG -> listOf(0.36f, 0.64f).forEach { cx ->
                val path = Path().apply { addOval(Rect(p(cx - 0.065f), p(0.02f), p(cx + 0.065f), p(0.40f))) }
                blob(path, body)
            }
            AvatarEars.HORNS -> listOf(false, true).forEach { mirror ->
                fun mx(x: Float) = if (mirror) 1f - x else x
                val path = Path().apply {
                    moveTo(p(mx(0.28f)), p(0.34f))
                    quadraticTo(p(mx(0.20f)), p(0.16f), p(mx(0.30f)), p(0.08f))
                    quadraticTo(p(mx(0.38f)), p(0.20f), p(mx(0.42f)), p(0.30f))
                    close()
                }
                blob(path, Color(0xFFF5F0E1))
            }
            AvatarEars.ANTENNA -> {
                drawLine(outline, o(0.5f, 0.30f), o(0.5f, 0.13f), strokeWidth = line * 1.4f, cap = StrokeCap.Round)
                drawCircle(Color(0xFFFFC53D), p(0.05f), o(0.5f, 0.10f))
                drawCircle(outlineColor, p(0.05f), o(0.5f, 0.10f), style = outlineStroke)
            }
            AvatarEars.LEAF -> listOf(false, true).forEach { mirror ->
                fun mx(x: Float) = if (mirror) 1f - x else x
                val path = Path().apply {
                    moveTo(p(mx(0.48f)), p(0.30f))
                    cubicTo(p(mx(0.28f)), p(0.22f), p(mx(0.28f)), p(0.06f), p(mx(0.40f)), p(0.05f))
                    cubicTo(p(mx(0.52f)), p(0.08f), p(mx(0.54f)), p(0.22f), p(mx(0.48f)), p(0.30f))
                    close()
                }
                blob(path, Color(0xFF3BAA6B))
            }
            AvatarEars.TUFT -> for (dx in listOf(-0.07f, 0f, 0.07f)) {
                drawLine(
                    outline, o(0.5f + dx, 0.30f), o(0.5f + dx * 1.7f, if (dx == 0f) 0.12f else 0.15f),
                    strokeWidth = line * 1.6f, cap = StrokeCap.Round
                )
            }
        }

        // ---- body ----
        val bodyPath = Path().apply { addOval(Rect(p(0.19f), p(0.27f), p(0.81f), p(0.85f))) }
        drawPath(bodyPath, body)
        drawPath(bodyPath, outlineColor, style = outlineStroke)
        drawCircle(Color.White.copy(alpha = 0.22f), p(0.07f), o(0.34f, 0.40f))

        // ---- cheeks ----
        listOf(0.29f, 0.71f).forEach { cx -> drawCircle(Color(0xFFFF7A93).copy(alpha = 0.55f), p(0.05f), o(cx, 0.63f)) }

        // ---- eyes ----
        val eyeY = 0.52f
        val open = 1f - blink
        val eyeL = 0.38f
        val eyeR = 0.62f
        val arcStroke = Stroke(width = line * 1.5f, cap = StrokeCap.Round)
        fun dotEye(cx: Float) {
            drawOval(ink, o(cx - 0.032f, eyeY - 0.04f * open), Size(p(0.064f), p(0.08f * open).coerceAtLeast(line)))
        }
        fun happyEye(cx: Float) {
            drawArc(ink, 200f, 140f, false, o(cx - 0.045f, eyeY - 0.03f), Size(p(0.09f), p(0.08f)), style = arcStroke)
        }
        fun wideEye(cx: Float) {
            val r = p(0.062f)
            val h = (r * 2 * open).coerceAtLeast(line)
            drawOval(Color.White, o(cx - 0.062f, eyeY - 0.062f * open), Size(r * 2, h))
            drawOval(outlineColor, o(cx - 0.062f, eyeY - 0.062f * open), Size(r * 2, h), style = Stroke(width = line * 0.8f))
            if (open > 0.4f) drawCircle(outline, p(0.03f), o(cx + 0.008f, eyeY + 0.004f))
        }
        fun sleepyEye(cx: Float) {
            drawArc(ink, 20f, 140f, false, o(cx - 0.045f, eyeY - 0.045f), Size(p(0.09f), p(0.08f)), style = arcStroke)
        }
        when (ch.eyes) {
            AvatarEyes.DOT -> { dotEye(eyeL); dotEye(eyeR) }
            AvatarEyes.HAPPY -> { happyEye(eyeL); happyEye(eyeR) }
            AvatarEyes.WIDE -> { wideEye(eyeL); wideEye(eyeR) }
            AvatarEyes.SLEEPY -> { sleepyEye(eyeL); sleepyEye(eyeR) }
            AvatarEyes.WINK -> { dotEye(eyeL); happyEye(eyeR) }
        }

        // ---- mouth ----
        when (ch.mouth) {
            AvatarMouth.SMILE -> drawArc(ink, 20f, 140f, false, o(0.43f, 0.58f), Size(p(0.14f), p(0.10f)), style = arcStroke)
            AvatarMouth.TINY -> drawArc(ink, 30f, 120f, false, o(0.465f, 0.62f), Size(p(0.07f), p(0.05f)), style = arcStroke)
            AvatarMouth.GRIN -> {
                val path = Path().apply {
                    moveTo(p(0.40f), p(0.63f)); quadraticTo(p(0.5f), p(0.77f), p(0.60f), p(0.63f)); close()
                }
                drawPath(path, outline)
                drawRect(Color.White, o(0.45f, 0.63f), Size(p(0.10f), p(0.025f)))
            }
            AvatarMouth.OPEN -> {
                drawOval(outline, o(0.465f, 0.62f), Size(p(0.07f), p(0.09f)))
                drawOval(Color(0xFFFF7A93), o(0.48f, 0.665f), Size(p(0.04f), p(0.03f)))
            }
            AvatarMouth.CAT -> {
                drawArc(ink, 10f, 160f, false, o(0.43f, 0.60f), Size(p(0.07f), p(0.06f)), style = arcStroke)
                drawArc(ink, 10f, 160f, false, o(0.50f, 0.60f), Size(p(0.07f), p(0.06f)), style = arcStroke)
            }
        }

        // ---- accessory ----
        when (ch.prop) {
            AvatarProp.NONE -> Unit
            AvatarProp.BOW -> {
                val c = Color(0xFFE5484D)
                val left = Path().apply { moveTo(p(0.70f), p(0.29f)); lineTo(p(0.58f), p(0.22f)); lineTo(p(0.58f), p(0.36f)); close() }
                val right = Path().apply { moveTo(p(0.70f), p(0.29f)); lineTo(p(0.82f), p(0.22f)); lineTo(p(0.82f), p(0.36f)); close() }
                blob(left, c); blob(right, c)
                drawCircle(c, p(0.04f), o(0.70f, 0.29f))
                drawCircle(outlineColor, p(0.04f), o(0.70f, 0.29f), style = outlineStroke)
            }
            AvatarProp.GLASSES -> {
                val gs = Stroke(width = line * 1.7f)
                drawCircle(outline, p(0.095f), o(eyeL, eyeY), style = gs)
                drawCircle(outline, p(0.095f), o(eyeR, eyeY), style = gs)
                drawLine(outline, o(eyeL + 0.095f, eyeY), o(eyeR - 0.095f, eyeY), strokeWidth = line * 1.7f)
            }
            AvatarProp.CROWN -> {
                val path = Path().apply {
                    moveTo(p(0.36f), p(0.32f)); lineTo(p(0.35f), p(0.15f)); lineTo(p(0.43f), p(0.23f)); lineTo(p(0.5f), p(0.11f))
                    lineTo(p(0.57f), p(0.23f)); lineTo(p(0.65f), p(0.15f)); lineTo(p(0.64f), p(0.32f)); close()
                }
                blob(path, Color(0xFFFFC53D))
                drawCircle(Color(0xFFE5484D), p(0.018f), o(0.5f, 0.27f))
            }
            AvatarProp.SCARF -> {
                val band = Path().apply { addRoundRect(RoundRect(p(0.23f), p(0.75f), p(0.77f), p(0.84f), CornerRadius(p(0.04f)))) }
                blob(band, Color(0xFFE5484D))
                val tail = Path().apply { addRoundRect(RoundRect(p(0.60f), p(0.80f), p(0.72f), p(0.97f), CornerRadius(p(0.03f)))) }
                blob(tail, Color(0xFFE5484D))
            }
            AvatarProp.BEANIE -> {
                val cap = Path().apply {
                    moveTo(p(0.24f), p(0.38f)); cubicTo(p(0.24f), p(0.10f), p(0.76f), p(0.10f), p(0.76f), p(0.38f)); close()
                }
                blob(cap, Color(0xFF3E63DD))
                val brim = Path().apply { addRoundRect(RoundRect(p(0.22f), p(0.35f), p(0.78f), p(0.43f), CornerRadius(p(0.04f)))) }
                blob(brim, Color(0xFFDDE4FF))
                drawCircle(Color.White, p(0.045f), o(0.5f, 0.13f))
                drawCircle(outlineColor, p(0.045f), o(0.5f, 0.13f), style = outlineStroke)
            }
            AvatarProp.FLOWER -> {
                val cx = 0.70f
                val cy = 0.29f
                for (i in 0 until 5) {
                    val a = (2.0 * PI / 5.0 * i).toFloat()
                    drawCircle(Color(0xFFFF8FB1), p(0.04f), o(cx + 0.05f * cos(a), cy + 0.05f * sin(a)))
                }
                drawCircle(Color(0xFFFFD84D), p(0.032f), o(cx, cy))
            }
        }
    }
}

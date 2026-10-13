package com.zain.holoos.holo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The HoloOS palette. Everything is drawn additively on [Void], so cyan reads as light. */
object HoloColors {
    val Void = Color(0xFF03080C)
    val Panel = Color(0xFF061219)
    val Cyan = Color(0xFF5FE6FF)
    val CyanDim = Color(0xFF2A8AA3)
    val Ice = Color(0xFFE6FBFF)
    val Amber = Color(0xFFFFB547)
    val Alert = Color(0xFFFF4D6A)
    val Text = Color(0xFFC9F4FF)
    val Muted = Color(0xFF6C9AA8)
}

fun holoText(
    size: TextUnit = 12.sp,
    color: Color = HoloColors.Text,
    weight: FontWeight = FontWeight.Normal,
    glow: Float = 10f,
    spacing: TextUnit = 1.5.sp,
) = TextStyle(
    color = color,
    fontSize = size,
    fontFamily = FontFamily.Monospace,
    fontWeight = weight,
    letterSpacing = spacing,
    shadow = if (glow > 0f) Shadow(color.copy(alpha = 0.8f), Offset.Zero, glow) else null,
)

/*
 * Fake bloom: every hologram stroke is drawn three times with additive blending —
 * a wide faint halo, a medium glow and a crisp core. Cheap on the GPU and portable
 * across Skia (desktop) and Android without shaders.
 */
@PublishedApi
internal val haloLayers = floatArrayOf(7f, 0.05f, 3f, 0.13f, 1f, 1f)

inline fun DrawScope.glow(
    intensity: Float = 1f,
    baseWidth: Float,
    draw: DrawScope.(width: Float, alpha: Float) -> Unit,
) {
    var i = 0
    while (i < haloLayers.size) {
        val w = baseWidth * haloLayers[i]
        val a = (haloLayers[i + 1] * intensity).coerceIn(0f, 1f)
        if (a > 0.003f) draw(w, a)
        i += 2
    }
}

fun DrawScope.glowArc(
    color: Color, center: Offset, radius: Float, startDeg: Float, sweepDeg: Float,
    width: Float = 1.5f, intensity: Float = 1f, cap: StrokeCap = StrokeCap.Butt,
) = glow(intensity, width) { w, a ->
    drawArc(
        color, startDeg, sweepDeg, false,
        topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
        alpha = a, style = Stroke(w, cap = cap), blendMode = BlendMode.Plus,
    )
}

fun DrawScope.glowCircle(color: Color, center: Offset, radius: Float, width: Float = 1.5f, intensity: Float = 1f) =
    glow(intensity, width) { w, a ->
        drawCircle(color, radius, center, alpha = a, style = Stroke(w), blendMode = BlendMode.Plus)
    }

fun DrawScope.glowLine(color: Color, from: Offset, to: Offset, width: Float = 1.5f, intensity: Float = 1f) =
    glow(intensity, width) { w, a ->
        drawLine(color, from, to, w, StrokeCap.Round, alpha = a, blendMode = BlendMode.Plus)
    }

fun DrawScope.glowPath(color: Color, path: Path, width: Float = 1.5f, intensity: Float = 1f) =
    glow(intensity, width) { w, a ->
        drawPath(path, color, alpha = a, style = Stroke(w), blendMode = BlendMode.Plus)
    }

fun DrawScope.glowDot(color: Color, center: Offset, radius: Float, intensity: Float = 1f) {
    drawCircle(color, radius * 4f, center, alpha = 0.08f * intensity, blendMode = BlendMode.Plus)
    drawCircle(color, radius * 2f, center, alpha = 0.18f * intensity, blendMode = BlendMode.Plus)
    drawCircle(color, radius, center, alpha = intensity.coerceIn(0f, 1f), blendMode = BlendMode.Plus)
}

/** A panel outline with the two diagonal corner cuts used throughout the UI. */
fun cutCornerPath(size: Size, cut: Float, inset: Float = 0f): Path = Path().apply {
    val l = inset; val t = inset; val r = size.width - inset; val b = size.height - inset
    moveTo(l + cut, t); lineTo(r, t); lineTo(r, b - cut); lineTo(r - cut, b); lineTo(l, b); lineTo(l, t + cut); close()
}

fun hexPath(center: Offset, radius: Float, rotationRad: Float = 0f): Path = Path().apply {
    for (i in 0 until 6) {
        val a = rotationRad + i * (kotlin.math.PI.toFloat() / 3f)
        val p = Offset(center.x + kotlin.math.cos(a) * radius, center.y + kotlin.math.sin(a) * radius)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

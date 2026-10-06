package com.zain.holoos.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.core.CalculatorScreen
import com.zain.holoos.core.FilesScreen
import com.zain.holoos.core.GlobeScreen
import com.zain.holoos.core.LocalHolo
import com.zain.holoos.core.Screen
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.glowArc
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.glowDot
import com.zain.holoos.holo.glowLine
import com.zain.holoos.holo.glowPath
import com.zain.holoos.holo.hexPath
import com.zain.holoos.holo.holoText
import com.zain.holoos.input.HoloTarget
import com.zain.holoos.tracking.handHologram
import com.zain.holoos.input.HoloTargetEntry
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class Glyph { Folder, Calculator, Globe, Hand }

private class AppNode(val id: String, val label: String, val glyph: Glyph, val angleDeg: Float, val screen: () -> Screen)

@Composable
fun HomeView() {
    val holo = LocalHolo.current
    val nodes = listOf(
        AppNode("app-files", "FILES", Glyph.Folder, 200f) { FilesScreen(holo.files.root()) },
        AppNode("app-globe", "GLOBE", Glyph.Globe, 340f) { GlobeScreen },
        AppNode("app-calc", "CALCULATOR", Glyph.Calculator, 90f) { CalculatorScreen },
        AppNode("app-hands", "HANDS", Glyph.Hand, 270f) { com.zain.holoos.core.HandsScreen },
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth; val h = maxHeight
        val base = min(w.value, h.value)
        val coreR = base * 0.2f
        val orbitX = min(w.value * 0.34f, base * 0.62f)
        val orbitY = base * 0.33f
        val nodeSize: Dp = (base * 0.24f).coerceIn(120f, 190f).dp

        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val t = holo.seconds
            arcReactor(c, coreR.dp.toPx(), t)
            for (n in nodes) {
                val a = n.angleDeg * 0.0174533f
                val p = Offset(c.x + cos(a) * orbitX.dp.toPx(), c.y + sin(a) * orbitY.dp.toPx())
                tether(c, p, coreR.dp.toPx() * 1.05f, nodeSize.toPx() * 0.42f, t)
            }
        }

        for (n in nodes) {
            val a = (n.angleDeg * 0.0174533f)
            val x = w / 2 + (cos(a) * orbitX).dp - nodeSize / 2
            val y = h / 2 + (sin(a) * orbitY).dp - nodeSize / 2
            HoloTarget(
                n.id,
                Modifier.offset(x, y).size(nodeSize),
                onDive = { r -> holo.navigator.push(n.screen(), r) },
                grab = com.zain.holoos.input.Grab.Keep,
            ) { e -> AppNodeView(n, e) }
        }
    }
}

@Composable
private fun AppNodeView(n: AppNode, e: HoloTargetEntry) {
    val holo = LocalHolo.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = holo.seconds
            val c = Offset(size.width / 2, size.height * 0.44f)
            val r = size.minDimension * 0.36f
            val lit = 0.55f + e.hover * 0.6f + e.pulse * 0.8f + e.zoom
            val col = if (e.charge > 0.15f) HoloColors.Amber else HoloColors.Cyan
            glowPath(col, hexPath(c, r, t * 0.15f), 1.4f, lit)
            glowPath(HoloColors.CyanDim, hexPath(c, r * 0.82f, -t * 0.25f), 1f, 0.5f)
            glowArc(HoloColors.Cyan, c, r * 1.18f, t * 40f, 70f, 2.5f, 0.5f + e.hover * 0.5f)
            glowArc(HoloColors.Cyan, c, r * 1.18f, t * 40f + 180f, 70f, 2.5f, 0.5f + e.hover * 0.5f)
            if (e.charge > 0.02f) glowArc(HoloColors.Amber, c, r * 1.3f, -90f, 360f * e.charge, 3f, 0.9f)
            if (e.pulse > 0f) glowCircle(HoloColors.Ice, c, r * (1.2f + (1f - e.pulse) * 0.8f), 2f, e.pulse)
            glyph(n.glyph, c, r * 0.5f, col, t, lit)
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(n.label, style = holoText(12.sp, if (e.charge > 0.15f) HoloColors.Amber else HoloColors.Cyan, spacing = 3.sp))
        }
    }
}

private fun DrawScope.glyph(g: Glyph, c: Offset, r: Float, col: Color, t: Float, lit: Float) {
    when (g) {
        Glyph.Folder -> {
            val p = Path().apply {
                moveTo(c.x - r, c.y - r * 0.55f); lineTo(c.x - r * 0.3f, c.y - r * 0.55f); lineTo(c.x - r * 0.15f, c.y - r * 0.35f)
                lineTo(c.x + r, c.y - r * 0.35f); lineTo(c.x + r, c.y + r * 0.65f); lineTo(c.x - r, c.y + r * 0.65f); close()
            }
            glowPath(col, p, 1.5f, lit)
            for (i in 0..2) glowLine(col, Offset(c.x - r * 0.6f, c.y + i * r * 0.25f), Offset(c.x + r * 0.6f, c.y + i * r * 0.25f), 1f, 0.5f)
        }
        Glyph.Calculator -> {
            val s = r * 0.42f
            for (i in 0..2) for (j in 0..2) {
                val p = Offset(c.x + (i - 1) * s * 1.35f, c.y + (j - 1) * s * 1.35f)
                glowCircle(col, p, s * 0.42f, 1.2f, lit * (if ((i + j + (t * 2).toInt()) % 4 == 0) 1.3f else 0.7f))
            }
        }
        Glyph.Hand -> {
            val pts = com.zain.holoos.apps.REST_HAND
            val k = r * 1.5f
            val wave = kotlin.math.sin(t * 2f) * 0.06f
            val scaled = FloatArray(42) { i -> if (i % 2 == 0) (pts[i] + wave * (pts[i + 1] + 0.5f)) * k else pts[i] * k }
            handHologram(c, scaled, col, lit * 0.8f, tipRings = false)
        }
        Glyph.Globe -> {
            glowCircle(col, c, r, 1.4f, lit)
            for (k in 0..3) {
                val ph = (t * 0.6f + k * 0.785f) % 3.1416f
                val rx = r * kotlin.math.abs(cos(ph))
                drawOval(col, Offset(c.x - rx, c.y - r), Size(rx * 2, r * 2), alpha = 0.6f * lit.coerceAtMost(1f), style = Stroke(1f), blendMode = BlendMode.Plus)
            }
            glowLine(col, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f, 0.6f)
        }
    }
}

private fun DrawScope.tether(from: Offset, to: Offset, startR: Float, endR: Float, t: Float) {
    val dx = to.x - from.x; val dy = to.y - from.y
    val len = kotlin.math.hypot(dx, dy)
    val ux = dx / len; val uy = dy / len
    val a = Offset(from.x + ux * startR, from.y + uy * startR)
    val b = Offset(to.x - ux * endR, to.y - uy * endR)
    drawLine(HoloColors.CyanDim, a, b, 1f, alpha = 0.5f, blendMode = BlendMode.Plus,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), -t * 30f))
    val k = (t * 0.6f) % 1f
    glowDot(HoloColors.Cyan, Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k), 2f)
}

/** The centrepiece: counter-rotating segmented rings around a triangular core. */
fun DrawScope.arcReactor(c: Offset, r: Float, t: Float) {
    glowCircle(HoloColors.CyanDim, c, r * 1.12f, 1f, 0.5f)
    for ((i, seg) in listOf(0f to 250f, 280f to 50f).withIndex()) glowArc(HoloColors.Cyan, c, r, t * 9f + seg.first, seg.second, 1.2f, 0.8f + i * 0.1f)
    for (k in 0 until 3) glowArc(HoloColors.Cyan, c, r * 0.9f, -t * 22f + k * 120f + 10f, 70f, 7f, 0.45f)
    val ticks = 90
    for (i in 0 until ticks) {
        val a = (t * 0.05f) + i * (6.2832f / ticks)
        val l = if (i % 10 == 0) r * 0.07f else r * 0.03f
        drawLine(HoloColors.Cyan, Offset(c.x + cos(a) * r * 0.8f, c.y + sin(a) * r * 0.8f),
            Offset(c.x + cos(a) * (r * 0.8f - l), c.y + sin(a) * (r * 0.8f - l)), 1f, alpha = 0.6f, blendMode = BlendMode.Plus)
    }
    for (k in 0 until 4) glowArc(HoloColors.Cyan, c, r * 0.64f, t * 36f + k * 90f, 55f, 2f, 0.8f)
    glowCircle(HoloColors.Cyan, c, r * 0.5f, 1f, 0.5f)
    val tri = Path().apply {
        for (i in 0 until 3) {
            val a = -1.5708f + i * 2.0944f
            val p = Offset(c.x + cos(a) * r * 0.36f, c.y + sin(a) * r * 0.36f)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    val breathe = 0.75f + 0.25f * sin(t * 2.2f)
    glowPath(HoloColors.Ice, tri, 2f, breathe)
    drawCircle(HoloColors.Cyan, r * 0.3f, c, alpha = 0.10f * breathe, blendMode = BlendMode.Plus)
    glowDot(HoloColors.Ice, c, r * 0.05f, breathe)
}

/** A relaxed open hand (wrist at the bottom), unit scale centred on the palm. */
internal val REST_HAND = floatArrayOf(
    0f, 0.55f, -0.22f, 0.42f, -0.36f, 0.26f, -0.46f, 0.12f, -0.55f, 0.0f,
    -0.16f, 0.05f, -0.19f, -0.18f, -0.21f, -0.32f, -0.22f, -0.44f,
    0f, 0f, 0f, -0.26f, 0f, -0.41f, 0f, -0.54f,
    0.14f, 0.03f, 0.17f, -0.2f, 0.19f, -0.34f, 0.2f, -0.45f,
    0.26f, 0.1f, 0.32f, -0.08f, 0.36f, -0.18f, 0.39f, -0.27f,
)

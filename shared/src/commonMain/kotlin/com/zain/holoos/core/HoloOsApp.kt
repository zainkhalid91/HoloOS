package com.zain.holoos.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import com.zain.holoos.apps.CalculatorView
import com.zain.holoos.apps.FilesView
import com.zain.holoos.apps.GlobeView
import com.zain.holoos.apps.HomeView
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.glowDot
import com.zain.holoos.holo.glowLine
import com.zain.holoos.holo.glowPath
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@Composable
fun HoloOsApp(state: HoloOsState = rememberHoloOsState()) {
    val focus = remember { FocusRequester() }

    LaunchedEffect(state) {
        while (true) withFrameNanos { state.frame(it) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    CompositionLocalProvider(LocalHolo provides state) {
        Box(
            Modifier
                .fillMaxSize()
                .background(HoloColors.Void)
                .onSizeChanged { state.size = it.toSize() }
                .handTracking(state)
                .hiddenCursor()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (state.navigator.top.screen == CalculatorScreen) {
                        val k = calculatorKey(e.key, e.utf16CodePoint)
                        if (k != null) { com.zain.holoos.apps.pressWithSound(state, k); return@onPreviewKeyEvent true }
                    }
                    when (e.key) {
                        Key.Escape, Key.Backspace -> { state.navigator.back(); true }
                        Key.P -> { state.predictionOn = !state.predictionOn; true }
                        Key.H -> { state.navigator.home(); true }
                        Key.M -> { state.toggleMute(); true }
                        Key.G -> { state.earthMode = if (state.earthMode == EarthMode.Real) EarthMode.Holo else EarthMode.Real; true }
                        else -> false
                    }
                }
        ) {
            Backdrop()
            ScreenHost()
            Hud()
            Overlay()
        }
    }
}

/**
 * Observes every pointer event on the Initial pass without consuming it, so children
 * still receive taps and drags. Mouse, pen and touch all collapse into [HandState].
 */
private fun Modifier.handTracking(state: HoloOsState): Modifier = pointerInput(state) {
    var pinchDist = 0f
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val primary = ev.changes.firstOrNull() ?: continue
            val down = ev.changes.filter { it.pressed }
            when (ev.type) {
                PointerEventType.Exit -> { state.predictor.reset(); continue }
                PointerEventType.Scroll -> state.zoomGesture(-primary.scrollDelta.y * 0.34f)
                else -> {}
            }
            val p = (down.firstOrNull() ?: primary).position
            state.predictor.onSample(p.x, p.y, primary.uptimeMillis)
            state.hand.pressed = down.isNotEmpty()

            if (down.size >= 2) {
                val a = down[0].position; val b = down[1].position
                val d = hypot(a.x - b.x, a.y - b.y)
                if (pinchDist > 0f) {
                    val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                    state.zoomGesture((d / pinchDist - 1f) * 3.2f, mid)
                }
                pinchDist = d
            } else pinchDist = 0f

            if (down.isEmpty() && primary.type == PointerType.Touch && ev.type == PointerEventType.Release) {
                state.predictor.reset()
            }
        }
    }
}

@Composable
private fun ScreenHost() {
    val holo = LocalHolo.current
    val nav = holo.navigator
    val saveable = rememberSaveableStateHolder()
    val t = nav.transition
    val visible = if (t == null) listOf(nav.top) else listOf(t.parent, t.child)

    for (entry in visible) {
        key(entry.id) {
            val role = when {
                t == null -> 0
                entry === t.parent -> 1
                else -> 2
            }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val w = size.width; val h = size.height
                    val e = nav.dive.value
                    val o = t?.child?.origin
                    when {
                        role == 0 -> {
                            val s = 1f - nav.backZoom * 0.1f
                            scaleX = s; scaleY = s; alpha = 1f - nav.backZoom * 0.35f
                        }
                        o == null || w == 0f -> {}
                        else -> {
                            // Child-at-rest scale inside the origin element.
                            val kc = min(o.width / w, o.height / h).coerceIn(0.02f, 1f)
                            val px = o.center.x; val py = o.center.y
                            if (role == 1) {
                                val s = (1f / kc).pow(e)
                                scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(px / w, py / h)
                                translationX = (w / 2 - px) * e; translationY = (h / 2 - py) * e
                                alpha = (1f - e * 1.4f).coerceIn(0f, 1f)
                            } else {
                                val s = kc.pow(1f - e)
                                scaleX = s; scaleY = s
                                translationX = (px - w / 2) * (1f - e); translationY = (py - h / 2) * (1f - e)
                                alpha = (e * 1.6f).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
            ) {
                saveable.SaveableStateProvider(entry.id) { ScreenContent(entry.screen) }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        HomeScreen -> HomeView()
        is FilesScreen -> FilesView(screen.node)
        CalculatorScreen -> CalculatorView()
        GlobeScreen -> GlobeView()
        is CityScreen -> com.zain.holoos.apps.CityView(screen.city)
    }
}

private fun calculatorKey(key: Key, code: Int): String? {
    if (key == Key.Backspace) return "⌫"
    if (key == Key.Escape) return null
    if (key == Key.Enter || key == Key.NumPadEnter) return "="
    return when (val ch = code.toChar()) {
        in '0'..'9' -> ch.toString()
        '.', ',' -> "."
        '+' -> "+"; '-' -> "−"; '*', 'x', 'X' -> "×"; '/' -> "÷"; '=' -> "="; '%' -> "%"
        'c', 'C' -> "C"
        else -> null
    }
}

/** Ambient depth: radial core light, a perspective floor grid and drifting dust. */
@Composable
private fun Backdrop() {
    val holo = LocalHolo.current
    val dust = remember { Array(90) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Canvas(Modifier.fillMaxSize()) {
        val t = holo.seconds
        drawRect(
            Brush.radialGradient(
                listOf(HoloColors.Cyan.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.45f), radius = max(size.width, size.height) * 0.6f,
            )
        )
        floorGrid(t)
        for (d in dust) {
            val x = ((d[0] + t * 0.004f * (0.5f + d[2])) % 1f) * size.width
            val y = ((d[1] - t * 0.006f * d[2] + 1f) % 1f) * size.height
            drawCircle(HoloColors.Cyan, 1f + d[2] * 1.2f, Offset(x, y), alpha = 0.10f + d[2] * 0.25f, blendMode = BlendMode.Plus)
        }
    }
}

private fun DrawScope.floorGrid(t: Float) {
    val horizon = size.height * 0.62f
    val cx = size.width / 2
    val color = HoloColors.Cyan
    for (i in -14..14) {
        val xb = cx + i * size.width * 0.09f
        drawLine(color, Offset(cx + i * 6f, horizon), Offset(xb, size.height), 1f, alpha = 0.06f, blendMode = BlendMode.Plus)
    }
    val scroll = (t * 0.25f) % 1f
    for (j in 0 until 14) {
        val z = (j + scroll) / 14f
        val y = horizon + (size.height - horizon) * z * z
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f, alpha = 0.02f + 0.08f * z, blendMode = BlendMode.Plus)
    }
}

private class ScanLines { var size = androidx.compose.ui.geometry.Size.Zero; val path = androidx.compose.ui.graphics.Path() }

/** Cursor reticle, prediction ghost, dive streaks, particles and scanlines. */
@Composable
private fun Overlay() {
    val holo = LocalHolo.current
    val scan = remember { ScanLines() }
    Canvas(Modifier.fillMaxSize()) {
        val t = holo.seconds
        val hand = holo.hand

        holo.navigator.transition?.let { tr ->
            val o = tr.child.origin ?: return@let
            val e = holo.navigator.dive.value
            val k = (1f - kotlin.math.abs(e - 0.5f) * 2f).coerceIn(0f, 1f)
            for (i in 0 until 48) {
                val a = i / 48f * 6.2832f + t * 0.3f
                val r0 = 30f + e * size.maxDimension * 0.4f
                val r1 = r0 + 60f + (i % 5) * 40f
                val c = o.center
                drawLine(
                    if (i % 7 == 0) HoloColors.Amber else HoloColors.Cyan,
                    Offset(c.x + kotlin.math.cos(a) * r0, c.y + kotlin.math.sin(a) * r0),
                    Offset(c.x + kotlin.math.cos(a) * r1, c.y + kotlin.math.sin(a) * r1),
                    1.2f, alpha = 0.35f * k, blendMode = BlendMode.Plus,
                )
            }
        }

        val p = holo.particles
        for (i in 0 until p.capacity) {
            val l = p.life[i]
            if (l <= 0f) continue
            drawCircle(
                if (p.amber[i]) HoloColors.Amber else HoloColors.Cyan, 1.6f, Offset(p.x[i], p.y[i]),
                alpha = l.coerceIn(0f, 1f), blendMode = BlendMode.Plus,
            )
        }

        if (hand.visible) {
            val c = hand.position
            val r = if (hand.pressed) 9f else 12f
            glowCircle(HoloColors.Cyan, c, r, 1.3f)
            for (q in 0 until 4) {
                val a = q * 1.5708f + t * 0.8f
                glowLine(
                    HoloColors.Cyan,
                    Offset(c.x + kotlin.math.cos(a) * (r + 5), c.y + kotlin.math.sin(a) * (r + 5)),
                    Offset(c.x + kotlin.math.cos(a) * (r + 11), c.y + kotlin.math.sin(a) * (r + 11)), 1.2f,
                )
            }
            if (holo.predictionOn && hand.decelerating) {
                // Kinematic endpoint: where the movement will come to rest.
                val ep = hand.endpoint
                val s = 7f
                val diamond = androidx.compose.ui.graphics.Path().apply {
                    moveTo(ep.x, ep.y - s); lineTo(ep.x + s, ep.y); lineTo(ep.x, ep.y + s); lineTo(ep.x - s, ep.y); close()
                }
                glowPath(HoloColors.Amber, diamond, 1.2f, 0.9f)
            }
            if (holo.predictionOn) {
                val pr = hand.predicted
                if (hypot(pr.x - c.x, pr.y - c.y) > 8f) {
                    drawLine(HoloColors.Amber, c, pr, 1f, alpha = 0.55f, blendMode = BlendMode.Plus,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 5f)))
                    glowCircle(HoloColors.Amber, pr, 6f, 1f, 0.8f)
                    glowDot(HoloColors.Amber, pr, 1.5f)
                }
            }
        }

        if (scan.size != size) {
            scan.size = size; scan.path.reset()
            var y = 0f
            while (y < size.height) { scan.path.moveTo(0f, y); scan.path.lineTo(size.width, y); y += 3f }
        }
        drawPath(scan.path, HoloColors.Cyan, alpha = 0.018f, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
}

package com.zain.holoos.tracking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.core.HoloOsState
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.cutCornerPath
import com.zain.holoos.holo.glowArc
import com.zain.holoos.holo.glowDot
import com.zain.holoos.holo.glowLine
import com.zain.holoos.holo.glowPath
import com.zain.holoos.holo.holoText

/** MediaPipe hand topology: pairs of landmark indices joined by a bone. */
val HAND_BONES = intArrayOf(
    0, 1, 1, 2, 2, 3, 3, 4,
    0, 5, 5, 6, 6, 7, 7, 8,
    5, 9, 9, 10, 10, 11, 11, 12,
    9, 13, 13, 14, 14, 15, 15, 16,
    13, 17, 17, 18, 18, 19, 19, 20, 0, 17,
)
private val TIPS = intArrayOf(4, 8, 12, 16, 20)

/** Draws a hand as a hologram skeleton: glowing bones, joints and fingertip rings. */
fun DrawScope.handHologram(origin: Offset, pts: FloatArray, color: Color, intensity: Float = 1f, tipRings: Boolean = true) {
    var i = 0
    while (i < HAND_BONES.size) {
        val a = HAND_BONES[i]; val b = HAND_BONES[i + 1]
        glowLine(color, Offset(origin.x + pts[a * 2], origin.y + pts[a * 2 + 1]), Offset(origin.x + pts[b * 2], origin.y + pts[b * 2 + 1]), 1.6f, intensity)
        i += 2
    }
    for (j in 0 until 21) {
        val p = Offset(origin.x + pts[j * 2], origin.y + pts[j * 2 + 1])
        drawCircle(color, 2.2f, p, alpha = 0.9f * intensity, blendMode = BlendMode.Plus)
    }
    if (tipRings) for (t in TIPS) {
        drawCircle(color, 6f, Offset(origin.x + pts[t * 2], origin.y + pts[t * 2 + 1]), alpha = 0.6f * intensity, style = Stroke(1f), blendMode = BlendMode.Plus)
    }
}

/** On-screen hand holograms at the mapped hand positions, plus the hold-progress ring. */
fun DrawScope.drawTrackedHands(holo: HoloOsState) {
    val g = holo.gestures ?: return
    for (h in g.hands) {
        if (!h.visible) continue
        val col = if (h.pose == HandPose.Pinch) HoloColors.Amber else HoloColors.Cyan
        handHologram(Offset(h.cursorX, h.cursorY), h.pts, col, 0.75f)
    }
    if (g.holdProgress > 0f && g.hands[0].visible) {
        val c = Offset(g.hands[0].cursorX, g.hands[0].cursorY)
        glowArc(HoloColors.Amber, c, 34f, -90f, 360f * g.holdProgress, 3f, 1f)
    }
}

/** The gesture legend: status, a privacy-safe skeleton view, and every gesture with the active one lit. */
@Composable
fun GesturePanel(holo: HoloOsState, modifier: Modifier = Modifier) {
    val g = holo.gestures
    val status = holo.tracker?.status ?: TrackerStatus.Off
    Box(modifier.width(300.dp)) {
        Canvas(Modifier.matchParentSize()) {
            drawPath(cutCornerPath(size, 14f), HoloColors.Panel, alpha = 0.75f)
            glowPath(HoloColors.Cyan, cutCornerPath(size, 14f, 1f), 1f, 0.45f)
        }
        Column(Modifier.padding(12.dp)) {
            val statusText = when (status) {
                TrackerStatus.Running -> if (g?.hands?.get(0)?.visible == true) "TRACKING" else "NO HANDS"
                TrackerStatus.NotInstalled -> "RUN tools/tracker/setup.ps1"
                TrackerStatus.Starting -> "STARTING CAMERA…"
                TrackerStatus.NoSignal -> "NO SIGNAL"
                TrackerStatus.Failed -> "TRACKER STOPPED"
                TrackerStatus.Off -> "CAMERA OFF"
            }
            val ok = status == TrackerStatus.Running
            val src = if (ok) " · ${holo.tracker?.source}" else ""
            BasicText("HAND CONTROL · $statusText$src", style = holoText(10.sp, if (ok) HoloColors.Cyan else HoloColors.Amber, spacing = 2.sp))
            if (g != null && ok) {
                BasicText(
                    "CAM ${g.cameraFps.toInt()} FPS · PIPE ${g.pipeLatencyMs.toInt()} MS · ${if (g.calibration != null) "CALIBRATED" else "DEFAULT MAP"}",
                    style = holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 1.sp),
                )
            }
            if (g != null && g.interpretation.isNotEmpty()) {
                val fresh = holo.timeMs == 0L || com.zain.holoos.geo.epochMillis() - g.interpretationAtMs < 2500
                BasicText(
                    "READ: ${g.interpretation}",
                    style = holoText(9.sp, if (fresh) HoloColors.Amber else HoloColors.Muted, glow = if (fresh) 6f else 0f, spacing = 1.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonView(holo, Modifier.size(104.dp, 132.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (gesture in Gesture.entries) {
                        val on = g?.active?.contains(gesture) == true
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(7.dp)) {
                                drawCircle(if (on) HoloColors.Amber else HoloColors.CyanDim, size.minDimension / 2, alpha = if (on) 1f else 0.5f)
                            }
                            Spacer(Modifier.width(6.dp))
                            Column {
                                BasicText(gesture.label, style = holoText(9.sp, if (on) HoloColors.Amber else HoloColors.Text, glow = if (on) 8f else 0f, spacing = 1.sp))
                            }
                        }
                        if (on) BasicText("  ${gesture.action.uppercase()}", style = holoText(8.sp, HoloColors.Amber, glow = 0f, spacing = 1.sp))
                    }
                }
            }
        }
    }
}

/** Camera-space view built only from landmarks: shoulders and hand skeletons, never pixels. */
@Composable
fun SkeletonView(holo: HoloOsState, modifier: Modifier = Modifier, large: Boolean = false) {
    Canvas(modifier) {
        val g = holo.gestures
        @Suppress("UNUSED_VARIABLE") val tick = holo.timeMs
        drawRect(HoloColors.Cyan, alpha = 0.03f)
        drawPath(cutCornerPath(size, 8f), HoloColors.CyanDim, alpha = 0.6f, style = Stroke(1f))
        if (g == null) return@Canvas
        val a = g.aspect
        // Fit the camera frame (a × 1) into the box.
        val s = minOf(size.width / a, size.height)
        val ox = (size.width - a * s) / 2; val oy = (size.height - s) / 2
        fun map(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        g.shoulderRaw?.let { sh ->
            val l = map(sh[0], sh[1]); val r = map(sh[2], sh[3])
            glowLine(HoloColors.CyanDim, l, r, if (large) 2f else 1.2f, 0.8f)
            val mid = Offset((l.x + r.x) / 2, (l.y + r.y) / 2)
            glowLine(HoloColors.CyanDim, mid, Offset(mid.x, mid.y + (r.x - l.x).let { kotlin.math.abs(it) } * 0.9f), if (large) 2f else 1f, 0.5f)
            glowDot(HoloColors.Cyan, l, if (large) 4f else 2f); glowDot(HoloColors.Cyan, r, if (large) 4f else 2f)
        }
        for (h in g.hands) {
            if (!h.visible) continue
            val pts = FloatArray(42) { i -> if (i % 2 == 0) ox + h.raw[i] * s else oy + h.raw[i] * s }
            handHologram(Offset.Zero, pts, if (h.pose == HandPose.Pinch) HoloColors.Amber else HoloColors.Cyan, 0.9f, tipRings = large)
        }
    }
}



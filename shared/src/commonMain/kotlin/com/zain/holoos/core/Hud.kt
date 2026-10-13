package com.zain.holoos.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.cutCornerPath
import com.zain.holoos.holo.glowPath
import com.zain.holoos.holo.holoText
import com.zain.holoos.input.HoloTarget
import kotlinx.coroutines.delay

/** Live diagnostics: what the tracker sees, how it was classified, and the latest events. */
@Composable
private fun DebugPanel(holo: HoloOsState, modifier: Modifier) {
    val g = holo.gestures
    @Suppress("UNUSED_VARIABLE") val tick = holo.fps
    Column(modifier) {
        val h = g?.hands?.get(0)
        BasicText("DEBUG · D TO HIDE · LOG ${holo.log?.path?.substringAfterLast('\\')?.substringAfterLast('/') ?: "OFF"}", style = holoText(10.sp, HoloColors.Alert, glow = 0f, spacing = 1.sp))
        if (g == null || h == null) { BasicText("NO TRACKER", style = holoText(10.sp, HoloColors.Muted, glow = 0f)); return@Column }
        val lines = listOf(
            "HAND ${if (h.visible) "YES" else "NO"}  POSE ${h.pose}  FINGERS ${h.extended}  SCORE ${(h.score * 100).toInt()}%",
            "PINCH RATIO ${(h.pinchRatio * 100).toInt() / 100f}  (ON < 0.28, OFF > 0.42)  PINCHING ${g.isPinching}  DRAG ${g.isDragging}",
            "SPACE ${if (h.bodySpace) "BODY" else "CAMERA"}  REF ${(h.refX * 100).toInt() / 100f}, ${(h.refY * 100).toInt() / 100f}  BODY SEEN ${g.bodyVisible}",
            "RAW ${h.rawCursorX.toInt()},${h.rawCursorY.toInt()}  CURSOR ${holo.hand.x.toInt()},${holo.hand.y.toInt()}  SPEED ${holo.hand.speed.toInt()} PX/S",
            "UNDER CURSOR ${if (holo.hand.visible) holo.targets.at(holo.hand.x, holo.hand.y)?.id ?: "-" else "-"}",
        )
        lines.forEach { BasicText(it, style = holoText(10.sp, HoloColors.Text, glow = 0f, spacing = 0.5.sp)) }
        holo.recentEvents.forEachIndexed { i, ev ->
            BasicText("› $ev", style = holoText(10.sp, if (i == 0) HoloColors.Amber else HoloColors.Muted, glow = 0f, spacing = 0.5.sp))
        }
    }
}

@Composable
private fun ExitButton(holo: HoloOsState) {
    val armed = holo.exitArmed
    HoloTarget("hud-exit", onTap = { holo.requestExit() }) { e ->
        Box(Modifier.size(if (armed) 168.dp else 84.dp, 30.dp), contentAlignment = Alignment.Center) {
            val col = if (armed) HoloColors.Alert else if (e.hover > 0.5f) HoloColors.Amber else HoloColors.Cyan
            Canvas(Modifier.matchParentSize()) {
                drawPath(cutCornerPath(size, 8f), col, alpha = 0.05f + e.hover * 0.08f + if (armed) 0.08f else 0f, blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
                glowPath(col, cutCornerPath(size, 8f, 1f), 1.2f, 0.6f + e.hover * 0.6f + e.pulse + if (armed) 0.5f else 0f)
            }
            BasicText(if (armed) "TAP AGAIN TO EXIT" else "⏻ EXIT", style = holoText(11.sp, col, spacing = 2.sp))
        }
    }
}

@Composable
fun Hud() {
    val holo = LocalHolo.current
    val nav = holo.navigator
    var clock by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) { while (true) { clock = clockText(); delay(1000) } }

    Box(Modifier.fillMaxSize().padding(18.dp)) {
        Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (nav.canGoBack) {
                HoloTarget("hud-back", onTap = { nav.back() }) { e ->
                    Box(Modifier.size(96.dp, 34.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.matchParentSize()) {
                            val col = if (e.charge > 0.2f) HoloColors.Amber else HoloColors.Cyan
                            glowPath(col, cutCornerPath(size, 8f, 1f), 1.2f, 0.6f + e.hover * 0.6f + e.pulse)
                        }
                        BasicText("◄ BACK", style = holoText(11.sp, if (e.charge > 0.2f) HoloColors.Amber else HoloColors.Cyan))
                    }
                }
            }
            Column {
                BasicText("HOLO OS", style = holoText(16.sp, HoloColors.Cyan, spacing = 6.sp))
                BasicText(nav.stack.joinToString("  ›  ") { it.screen.title.uppercase() }, style = holoText(10.sp, HoloColors.Muted, glow = 0f))
            }
        }

        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText(clock, style = holoText(16.sp, HoloColors.Cyan, spacing = 3.sp))
                ExitButton(holo)
            }
            BasicText(
                "${holo.fps} FPS · ${(holo.frameMs * 10).toInt() / 10f} MS · PRED ${if (holo.predictionOn) "ON" else "OFF"} · SFX ${if (holo.muted) "OFF" else "ON"}",
                style = holoText(10.sp, HoloColors.Muted, glow = 0f),
            )
            val sc = holo.intentScore
            BasicText(
                if (sc.total == 0) "INTENT HIT RATE · AWAITING DATA" else "INTENT HIT RATE ${(sc.accuracy * 100).toInt()}% · ${sc.hits}/${sc.total} @150MS",
                style = holoText(10.sp, if (sc.total > 0 && sc.accuracy > 0.7f) HoloColors.Amber else HoloColors.Muted, glow = 0f),
            )
        }

        if (holo.tracker != null) {
            com.zain.holoos.tracking.GesturePanel(holo, Modifier.align(Alignment.BottomStart).padding(bottom = 18.dp))
        }
        if (holo.debugOn) DebugPanel(holo, Modifier.align(Alignment.TopCenter).padding(top = 52.dp))

        BasicText(
            "TAP OR SCROLL-ZOOM TO DIVE  ·  SCROLL OUT / ESC TO RISE  ·  H HOME  ·  P PREDICTION  ·  M SOUND  ·  G GLOBE  ·  C CALIBRATE  ·  D DEBUG  ·  V VERTICAL ${if (holo.invertVertical) "REVERSED" else "NORMAL"}  ·  CTRL+Q QUIT",
            style = holoText(10.sp, HoloColors.Muted, glow = 0f),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

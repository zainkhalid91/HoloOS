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
            BasicText(clock, style = holoText(16.sp, HoloColors.Cyan, spacing = 3.sp))
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

        BasicText(
            "TAP OR SCROLL-ZOOM TO DIVE  ·  SCROLL OUT / ESC TO RISE  ·  H HOME  ·  P PREDICTION  ·  M SOUND  ·  G GLOBE MODE",
            style = holoText(10.sp, HoloColors.Muted, glow = 0f),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

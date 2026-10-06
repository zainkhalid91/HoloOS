package com.zain.holoos.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.core.LocalHolo
import com.zain.holoos.geo.epochMillis
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.cutCornerPath
import com.zain.holoos.holo.glowArc
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.glowDot
import com.zain.holoos.holo.glowPath
import com.zain.holoos.holo.holoText
import com.zain.holoos.input.HoloTarget
import com.zain.holoos.tracking.CalibrationSession.Step
import com.zain.holoos.tracking.SkeletonView

@Composable
fun HandsSetupView() {
    val holo = LocalHolo.current
    val session = holo.calibration
    DisposableEffect(session) {
        session?.start(holo.tracker != null && holo.gestures != null, epochMillis())
        onDispose { session?.stop() }
    }
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("CAMERA TRACKING IS NOT AVAILABLE ON THIS DEVICE YET", style = holoText(14.sp, HoloColors.Amber, spacing = 3.sp))
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        // Target rings are drawn in screen space so the user reaches for the real corners.
        Canvas(Modifier.fillMaxSize()) {
            val t = holo.seconds
            if (session.step == Step.Targets) {
                session.targets.forEachIndexed { i, (tx, ty) ->
                    val c = Offset(tx * size.width, ty * size.height)
                    when {
                        i < session.index -> { glowCircle(HoloColors.Cyan, c, 16f, 1.2f, 0.6f); glowDot(HoloColors.Cyan, c, 3f) }
                        i == session.index -> {
                            val pulse = (t * 1.2f) % 1f
                            glowCircle(HoloColors.Amber, c, 26f, 2f, 1f)
                            drawCircle(HoloColors.Amber, 26f + pulse * 40f, c, alpha = (1f - pulse) * 0.8f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f), blendMode = BlendMode.Plus)
                            glowArc(HoloColors.Amber, c, 40f, t * 120f, 80f, 2f, 0.8f)
                            glowDot(HoloColors.Amber, c, 4f)
                        }
                        else -> glowCircle(HoloColors.CyanDim, c, 12f, 1f, 0.4f)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("HAND CALIBRATION", style = holoText(11.sp, HoloColors.Muted, glow = 0f, spacing = 5.sp))
            val (title, body) = when (session.step) {
                Step.NoTracker -> "TRACKER NOT RUNNING" to "Run tools\\tracker\\setup.ps1 once, then restart HoloOS.\nThe camera starts automatically after that."
                Step.FindUser -> "LOCATING OPERATOR" to "Sit or stand where you normally will, about an arm and a half from the camera.\nYour shoulders anchor the control zone. Only a skeleton is used: no face, no video."
                Step.RaiseHand -> "RAISE AN OPEN HAND" to "Hold your palm toward the camera, fingers spread, until the ring fills."
                Step.Targets -> "REACH TARGET ${session.index + 1} OF ${session.targets.size}" to "Move your hand toward the amber target, hold it still for a moment, then pinch.\nReach clearly toward each corner, but stay relaxed."
                Step.Done -> "CALIBRATED" to "Average fit error ${session.errorPx.toInt()} px. Your whole screen now sits inside your relaxed reach."
            }
            BasicText(title, style = holoText(28.sp, if (session.step == Step.Done) HoloColors.Amber else HoloColors.Cyan, glow = 18f, spacing = 5.sp))
            Spacer(Modifier.height(8.dp))
            BasicText(body, style = holoText(12.sp, HoloColors.Text, glow = 0f, spacing = 1.sp).copy(textAlign = TextAlign.Center))
            if (session.hint.isNotEmpty()) {
                BasicText(session.hint.uppercase(), style = holoText(12.sp, HoloColors.Alert, spacing = 1.sp).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(top = 8.dp))
            }
            BasicText(
                "CAMERA: ${holo.tracker?.source ?: "-"} · ${if (holo.gestures?.calibration != null) "CALIBRATED" else "DEFAULT MAP"}",
                style = holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp), modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(18.dp))

            Box(contentAlignment = Alignment.Center) {
                SkeletonView(holo, Modifier.size(420.dp, 250.dp), large = true)
                if (session.step == Step.FindUser || session.step == Step.RaiseHand) {
                    Canvas(Modifier.size(90.dp)) {
                        val c = Offset(size.width / 2, size.height / 2)
                        glowCircle(HoloColors.CyanDim, c, size.minDimension * 0.4f, 1f, 0.5f)
                        glowArc(HoloColors.Amber, c, size.minDimension * 0.4f, -90f, 360f * session.progress, 4f, 1f)
                    }
                }
            }
            BasicText("SKELETON VIEW · LANDMARKS ONLY · NO CAMERA IMAGE IS SHOWN OR STORED", style = holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp), modifier = Modifier.padding(top = 6.dp))

            if (session.step == Step.Done || session.step == Step.Targets) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SetupButton("cal-redo", "RECALIBRATE") { session.restart(epochMillis()) }
                    SetupButton("cal-reset", "USE DEFAULT") { session.reset() }
                    if (session.step == Step.Done) SetupButton("cal-done", "DONE ►", amber = true) { holo.navigator.back() }
                }
            }
        }
    }
}

@Composable
private fun SetupButton(id: String, label: String, amber: Boolean = false, onTap: () -> Unit) {
    HoloTarget(id, Modifier.size(150.dp, 40.dp), onTap = onTap) { e ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val col = if (amber || e.charge > 0.2f) HoloColors.Amber else HoloColors.Cyan
            Canvas(Modifier.fillMaxSize()) {
                drawPath(cutCornerPath(size, 10f), col, alpha = 0.05f + e.hover * 0.08f, blendMode = BlendMode.Plus)
                glowPath(col, cutCornerPath(size, 10f, 1f), 1.1f, 0.6f + e.hover * 0.6f + e.pulse)
            }
            BasicText(label, style = holoText(12.sp, col, spacing = 3.sp))
        }
    }
}


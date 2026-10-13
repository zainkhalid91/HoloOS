package com.zain.holoos.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.core.LocalHolo
import com.zain.holoos.geo.WorldCity
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.glowArc
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.holoText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private class Building(val gx: Float, val gz: Float, val w: Float, val d: Float, val h: Float, val delay: Float, val tower: Boolean)

/**
 * A procedural holographic model of the city: density and skyline height scale with the
 * real population, the layout is seeded by the city's name so every city is distinct
 * and stable. It is a stylised model, not survey data, and the UI says so.
 */
private fun buildCity(city: WorldCity): List<Building> {
    val rnd = Random(city.name.hashCode())
    val scale = ((log10(city.population.coerceAtLeast(100_000).toFloat()) - 5f) / 2.5f).coerceIn(0.15f, 1f)
    val n = 13
    val out = ArrayList<Building>()
    for (i in 0 until n) for (j in 0 until n) {
        val gx = i - (n - 1) / 2f; val gz = j - (n - 1) / 2f
        val dist = hypot(gx, gz) / (n / 2f)
        if (rnd.nextFloat() < 0.18f + dist * 0.25f) continue
        val core = exp(-dist * dist * 3f)
        val h = (0.25f + rnd.nextFloat() * 0.6f + core * 3.2f * scale * (0.6f + rnd.nextFloat() * 0.8f))
        out += Building(gx, gz, 0.35f + rnd.nextFloat() * 0.3f, 0.35f + rnd.nextFloat() * 0.3f, h, dist * 0.9f + rnd.nextFloat() * 0.25f, core > 0.6f && rnd.nextFloat() < 0.35f)
    }
    return out
}

private class Orbit { var angle = 0.6f; var vel = 0.18f; var dragging = false; var lastT = -1f; var start = -1f }

@Composable
fun CityView(city: WorldCity) {
    val holo = LocalHolo.current
    val buildings = remember(city) { buildCity(city) }
    val orbit = remember { Orbit() }
    val path = remember { Path() }
    androidx.compose.runtime.LaunchedEffect(city) {
        kotlinx.coroutines.delay(250)
        holo.sfx(com.zain.holoos.audio.Sfx.Telemetry)
        kotlinx.coroutines.delay(900)
        holo.sfx(com.zain.holoos.audio.Sfx.Notify, gain = 0.7f)
    }
    val tops = remember { Path() }

    androidx.compose.runtime.DisposableEffect(orbit) {
        holo.screenDrag = { dx, _ -> orbit.angle += dx * 0.006f; orbit.vel = dx * 0.2f }
        // Swipe left / right spins the city; back out with two hands closing (or ✌).
        holo.screenSwipe = { dx, dy ->
            if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) { orbit.vel = if (dx > 0f) 1.6f else -1.6f; true } else false
        }
        holo.screenHold = { orbit.vel = 0f }
        onDispose { holo.screenDrag = null; holo.screenSwipe = null; holo.screenHold = null }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(onDragStart = { orbit.dragging = true }, onDragEnd = { orbit.dragging = false }, onDragCancel = { orbit.dragging = false }) { ch, d ->
                    ch.consume(); orbit.angle += d.x * 0.006f; orbit.vel = d.x * 0.35f
                }
            }
        ) {
            val t = holo.seconds
            if (orbit.start < 0f) orbit.start = t
            val dt = if (orbit.lastT < 0f) 0f else (t - orbit.lastT).coerceIn(0f, 0.05f); orbit.lastT = t
            if (!orbit.dragging) { orbit.angle += orbit.vel * dt; orbit.vel += (0.18f - orbit.vel) * (1f - exp(-dt)) }
            val age = t - orbit.start

            val c = Offset(size.width * 0.5f, size.height * 0.62f)
            val unit = min(size.width, size.height) / 15f
            val ca = cos(orbit.angle); val sa = sin(orbit.angle)
            val tilt = 0.55f; val ct = cos(tilt); val st = sin(tilt)
            fun px(x: Float, y: Float, z: Float): Offset {
                val x1 = x * ca - z * sa; val z1 = x * sa + z * ca
                val y1 = y * ct - z1 * st; val z2 = y * st + z1 * ct
                val persp = 1f / (1f + z2 * 0.035f)
                return Offset(c.x + x1 * unit * persp, c.y - y1 * unit * persp)
            }

            // Ground: rings, radial spokes and a radar sweep.
            for (k in 1..4) {
                var prev = px(k * 2f, 0f, 0f)
                for (s in 1..64) {
                    val a = s / 64f * 6.2832f
                    val p = px(cos(a) * k * 2f, 0f, sin(a) * k * 2f)
                    drawLine(HoloColors.Cyan, prev, p, 1f, alpha = 0.18f, blendMode = BlendMode.Plus); prev = p
                }
            }
            val sweep = t * 1.4f
            for (k in 0 until 18) {
                val a = sweep - k * 0.035f
                drawLine(HoloColors.Cyan, px(0f, 0f, 0f), px(cos(a) * 8.5f, 0f, sin(a) * 8.5f), 1.5f, alpha = 0.25f * (1f - k / 18f), blendMode = BlendMode.Plus)
            }

            // Buildings rise from the ground in a wave from the centre outward.
            path.reset(); tops.reset()
            for (b in buildings) {
                val grow = ((age - 0.2f - b.delay) / 0.7f).coerceIn(0f, 1f)
                if (grow <= 0f) continue
                val e = 1f - (1f - grow) * (1f - grow) * (1f - grow)
                val h = b.h * e
                val x0 = b.gx - b.w / 2; val x1 = b.gx + b.w / 2; val z0 = b.gz - b.d / 2; val z1 = b.gz + b.d / 2
                val p = arrayOf(px(x0, 0f, z0), px(x1, 0f, z0), px(x1, 0f, z1), px(x0, 0f, z1), px(x0, h, z0), px(x1, h, z0), px(x1, h, z1), px(x0, h, z1))
                for (q in 0 until 4) {
                    val a = p[q]; val bb = p[(q + 1) % 4]; val ta = p[q + 4]; val tb = p[(q + 1) % 4 + 4]
                    path.moveTo(a.x, a.y); path.lineTo(ta.x, ta.y)
                    tops.moveTo(ta.x, ta.y); tops.lineTo(tb.x, tb.y)
                    path.moveTo(a.x, a.y); path.lineTo(bb.x, bb.y)
                }
                if (b.tower && grow >= 1f) {
                    val top = px(b.gx, h, b.gz); val spire = px(b.gx, h + 0.9f, b.gz)
                    path.moveTo(top.x, top.y); path.lineTo(spire.x, spire.y)
                    val blink = if (((t * 1.6f + b.gx) % 1f) < 0.5f) 1f else 0.25f
                    drawCircle(HoloColors.Alert, 2.4f, spire, alpha = blink, blendMode = BlendMode.Plus)
                }
            }
            drawPath(path, HoloColors.Cyan, alpha = 0.10f, style = Stroke(4f), blendMode = BlendMode.Plus)
            drawPath(path, HoloColors.Cyan, alpha = 0.75f, style = Stroke(1f), blendMode = BlendMode.Plus)
            drawPath(tops, HoloColors.Ice, alpha = 0.9f, style = Stroke(1.2f), blendMode = BlendMode.Plus)

            // Scanning plane sweeping up and down the skyline.
            val scanY = (sin(t * 0.9f) * 0.5f + 0.5f) * 4f
            val a = px(-7f, scanY, -7f); val b2 = px(7f, scanY, -7f); val c2 = px(7f, scanY, 7f); val d2 = px(-7f, scanY, 7f)
            val plane = Path().apply { moveTo(a.x, a.y); lineTo(b2.x, b2.y); lineTo(c2.x, c2.y); lineTo(d2.x, d2.y); close() }
            drawPath(plane, HoloColors.Amber, alpha = 0.05f, blendMode = BlendMode.Plus)
            drawPath(plane, HoloColors.Amber, alpha = 0.5f, style = Stroke(1f), blendMode = BlendMode.Plus)

            glowCircle(HoloColors.CyanDim, c, unit * 9f, 1f, 0.25f)
            glowArc(HoloColors.Cyan, Offset(size.width * 0.12f, size.height * 0.5f), 46f, t * 90f, 270f * min(1f, age / 2.2f), 3f, 0.8f)
        }

        Column(Modifier.align(Alignment.CenterStart).padding(start = 40.dp)) {
            BasicText("CITY HOLOGRAM", style = holoText(10.sp, HoloColors.Muted, glow = 0f, spacing = 4.sp))
            BasicText(city.name.uppercase(), style = holoText(34.sp, HoloColors.Cyan, glow = 18f, spacing = 5.sp))
            BasicText(city.country.uppercase(), style = holoText(12.sp, HoloColors.Amber))
            Spacer(Modifier.height(14.dp))
            BasicText("POPULATION   ${formatPop(city.population)}", style = holoText(12.sp, HoloColors.Text, glow = 3f))
            BasicText("STRUCTURES   ${buildings.size}", style = holoText(12.sp, HoloColors.Text, glow = 3f))
            BasicText("SOLAR TIME   ${solarTime(city.lon)}", style = holoText(12.sp, HoloColors.Text, glow = 3f))
            BasicText("LAT / LON    ${(city.lat * 100).toInt() / 100f} / ${(city.lon * 100).toInt() / 100f}", style = holoText(12.sp, HoloColors.Text, glow = 3f))
            Spacer(Modifier.height(14.dp))
            BasicText("PROCEDURAL MODEL · DRAG TO ORBIT", style = holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp))
        }
    }
}


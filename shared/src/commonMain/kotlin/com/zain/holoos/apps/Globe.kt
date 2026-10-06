package com.zain.holoos.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.core.LocalHolo
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.glowArc
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.glowDot
import com.zain.holoos.holo.holoText
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private class City(val name: String, val lat: Float, val lon: Float, val note: String)

private val cities = listOf(
    City("LAHORE", 31.55f, 74.34f, "HOME BASE"),
    City("BUDAPEST", 47.50f, 19.04f, "EU NODE"),
    City("LONDON", 51.51f, -0.13f, "UPLINK"),
    City("NEW YORK", 40.71f, -74.01f, "TOWER"),
    City("SAN FRANCISCO", 37.77f, -122.42f, "R&D"),
    City("TOKYO", 35.68f, 139.69f, "ASIA NODE"),
    City("DUBAI", 25.20f, 55.27f, "RELAY"),
)

/** Rotation state with momentum; plain fields because only the draw block reads them. */
private class Spin {
    var yaw = -1.3f; var pitch = 0.35f
    var vYaw = 0.12f; var vPitch = 0f
    var dragging = false
    var lastT = 0f
}

@Composable
fun GlobeView() {
    val holo = LocalHolo.current
    val spin = remember { Spin() }
    var selected by remember { mutableIntStateOf(0) }
    val projected = remember { Array(cities.size) { floatArrayOf(0f, 0f, 0f) } }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { spin.dragging = true },
                        onDragEnd = { spin.dragging = false },
                        onDragCancel = { spin.dragging = false },
                    ) { change, d ->
                        change.consume()
                        val r = min(size.width, size.height) * 0.34f
                        spin.yaw += d.x / r; spin.pitch = (spin.pitch + d.y / r).coerceIn(-1.3f, 1.3f)
                        spin.vYaw = d.x / r * 60f; spin.vPitch = d.y / r * 60f
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        var best = -1; var bestD = 46f
                        projected.forEachIndexed { i, q ->
                            if (q[2] > 0f) { val d = hypot(q[0] - p.x, q[1] - p.y); if (d < bestD) { bestD = d; best = i } }
                        }
                        if (best >= 0) { selected = best; holo.burst(Offset(projected[best][0], projected[best][1]), warm = true) }
                    }
                }
        ) {
            val t = holo.seconds
            val dt = (t - spin.lastT).coerceIn(0f, 0.05f); spin.lastT = t
            if (!spin.dragging) {
                spin.yaw += spin.vYaw * dt; spin.pitch = (spin.pitch + spin.vPitch * dt).coerceIn(-1.3f, 1.3f)
                spin.vYaw += (0.12f - spin.vYaw) * (1f - kotlin.math.exp(-dt * 1.5f))
                spin.vPitch *= kotlin.math.exp(-dt * 3f)
            }
            val c = Offset(size.width * 0.44f, size.height * 0.54f)
            val r = min(size.width, size.height) * 0.34f
            drawGlobe(c, r, spin, t, selected, projected)
        }

        val city = cities[selected]
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 40.dp), horizontalAlignment = Alignment.End) {
            BasicText("TARGET LOCK", style = holoText(10.sp, HoloColors.Muted, glow = 0f, spacing = 4.sp))
            BasicText(city.name, style = holoText(28.sp, HoloColors.Amber, glow = 18f, spacing = 4.sp))
            BasicText(city.note, style = holoText(12.sp, HoloColors.Cyan))
            BasicText(
                "${fmt(city.lat)}° ${if (city.lat >= 0) "N" else "S"}   ${fmt(kotlin.math.abs(city.lon))}° ${if (city.lon >= 0) "E" else "W"}",
                style = holoText(12.sp, HoloColors.Text, glow = 0f),
            )
            val home = cities[0]
            if (selected != 0) BasicText("${greatCircleKm(home, city)} KM FROM ${home.name}", style = holoText(11.sp, HoloColors.Muted, glow = 0f))
            BasicText("DRAG TO SPIN · TAP A MARKER", style = holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp), modifier = Modifier.padding(top = 18.dp))
        }
    }
}

private fun fmt(v: Float) = ((v * 100).toInt() / 100f).toString()

private fun greatCircleKm(a: City, b: City): Int {
    val la1 = a.lat * PI / 180; val la2 = b.lat * PI / 180
    val dl = (b.lon - a.lon) * PI / 180
    val cosC = (sin(la1) * sin(la2) + cos(la1) * cos(la2) * cos(dl)).coerceIn(-1.0, 1.0)
    return (acos(cosC) * 6371).toInt()
}

private fun DrawScope.drawGlobe(c: Offset, r: Float, s: Spin, t: Float, selected: Int, out: Array<FloatArray>) {
    val cy = cos(s.yaw); val sy = sin(s.yaw); val cp = cos(s.pitch); val sp = sin(s.pitch)
    fun project(latDeg: Float, lonDeg: Float, alt: Float = 1f, dst: FloatArray) {
        val la = latDeg * 0.0174533f; val lo = lonDeg * 0.0174533f
        val x = cos(la) * sin(lo); val y = -sin(la); val z = cos(la) * cos(lo)
        val x1 = x * cy + z * sy; val z1 = -x * sy + z * cy
        val y2 = y * cp - z1 * sp; val z2 = y * sp + z1 * cp
        dst[0] = c.x + x1 * r * alt; dst[1] = c.y + y2 * r * alt; dst[2] = z2
    }

    drawCircle(HoloColors.Cyan, r, c, alpha = 0.05f, blendMode = BlendMode.Plus)
    glowCircle(HoloColors.Cyan, c, r, 1.2f, 0.8f)
    glowCircle(HoloColors.CyanDim, c, r * 1.25f, 1f, 0.4f)
    glowArc(HoloColors.Cyan, c, r * 1.25f, t * 20f, 50f, 4f, 0.5f)
    glowArc(HoloColors.Cyan, c, r * 1.33f, -t * 14f, 120f, 1f, 0.5f)

    val a = FloatArray(3); val b = FloatArray(3)
    fun line(alpha: Float) {
        val front = (a[2] + b[2]) / 2 > 0f
        drawLine(HoloColors.Cyan, Offset(a[0], a[1]), Offset(b[0], b[1]), 1f,
            alpha = if (front) alpha else alpha * 0.18f, blendMode = BlendMode.Plus)
    }
    for (lat in -75..75 step 15) {
        project(lat.toFloat(), -180f, 1f, a)
        for (lon in -175..180 step 5) { project(lat.toFloat(), lon.toFloat(), 1f, b); line(if (lat == 0) 0.7f else 0.35f); a[0] = b[0]; a[1] = b[1]; a[2] = b[2] }
    }
    for (lon in -180 until 180 step 20) {
        project(-90f, lon.toFloat(), 1f, a)
        for (lat in -85..90 step 5) { project(lat.toFloat(), lon.toFloat(), 1f, b); line(0.3f); a[0] = b[0]; a[1] = b[1]; a[2] = b[2] }
    }

    // Scanning latitude band.
    val scanLat = sin(t * 0.7f) * 70f
    project(scanLat, -180f, 1.01f, a)
    for (lon in -175..180 step 5) {
        project(scanLat, lon.toFloat(), 1.01f, b)
        if ((a[2] + b[2]) > 0f) drawLine(HoloColors.Amber, Offset(a[0], a[1]), Offset(b[0], b[1]), 1.5f, alpha = 0.6f, blendMode = BlendMode.Plus)
        a[0] = b[0]; a[1] = b[1]; a[2] = b[2]
    }

    // Arc from home to the selected city, lifted off the surface.
    if (selected != 0) {
        val h = cities[0]; val d = cities[selected]
        val path = Path(); var started = false
        for (i in 0..40) {
            val k = i / 40f
            val lat = h.lat + (d.lat - h.lat) * k
            val lon = h.lon + (d.lon - h.lon) * k
            project(lat, lon, 1f + sin(k * PI.toFloat()) * 0.18f, a)
            if (a[2] > -0.1f) { if (!started) { path.moveTo(a[0], a[1]); started = true } else path.lineTo(a[0], a[1]) } else started = false
        }
        drawPath(path, HoloColors.Amber, alpha = 0.9f, style = Stroke(2f), blendMode = BlendMode.Plus)
        drawPath(path, HoloColors.Amber, alpha = 0.15f, style = Stroke(8f), blendMode = BlendMode.Plus)
    }

    cities.forEachIndexed { i, city ->
        project(city.lat, city.lon, 1f, out[i])
        val q = out[i]
        if (q[2] <= 0f) return@forEachIndexed
        val p = Offset(q[0], q[1])
        val col = if (i == selected) HoloColors.Amber else HoloColors.Cyan
        glowDot(col, p, 2.5f)
        val ping = (t * 0.8f + i * 0.37f) % 1f
        drawCircle(col, 4f + ping * 16f, p, alpha = (1f - ping) * 0.7f, style = Stroke(1f), blendMode = BlendMode.Plus)
        if (i == selected) glowCircle(HoloColors.Amber, p, 12f, 1.2f, 0.9f)
    }

    // Satellite on an inclined orbit.
    val orbitT = t * 0.5f
    project(sin(orbitT) * 50f, orbitT * 57.3f % 360f - 180f, 1.22f, a)
    glowDot(if (a[2] > 0f) HoloColors.Ice else HoloColors.CyanDim, Offset(a[0], a[1]), 3f, if (a[2] > 0f) 1f else 0.3f)
}

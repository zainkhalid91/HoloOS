package com.zain.holoos.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.audio.Sfx
import com.zain.holoos.core.CityScreen
import com.zain.holoos.core.EarthMode
import com.zain.holoos.core.HoloOsState
import com.zain.holoos.core.LocalHolo
import com.zain.holoos.geo.Coastlines
import com.zain.holoos.geo.WorldCity
import com.zain.holoos.geo.createEarthRenderer
import com.zain.holoos.geo.epochMillis
import com.zain.holoos.geo.greatCircleKm
import com.zain.holoos.geo.rad
import com.zain.holoos.geo.sunDirection
import com.zain.holoos.geo.worldCities
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.cutCornerPath
import com.zain.holoos.holo.glowArc
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.glowDot
import com.zain.holoos.holo.glowPath
import com.zain.holoos.holo.holoText
import com.zain.holoos.input.HoloTarget
import com.zain.holoos.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Smoothed CPU cost of building the globe's draw commands, in µs. */
private val drawCost = floatArrayOf(0f)

private val home = worldCities.firstOrNull { it.name == "Lahore" } ?: worldCities.first()

/**
 * Trackball camera: orientation is a rotation matrix (world → screen rows), so the globe
 * turns freely about any axis with no pole limit. Swipes and drags rotate it about screen
 * axes (drag down moves the surface down); idle spin turns it about the Earth's own axis.
 */
private class GlobeCamera {
    val m = FloatArray(9)
    private val target = FloatArray(9)
    var zoom = 1f
    /** Angular velocity (rad/s) about the screen Y axis (from horizontal motion) and X axis. */
    var angY = 0.10f; var angX = 0f
    var dragging = false
    var flying = false
    var toZoom = 1f
    var lastT = -1f
    /** Gentle idle rotation; switched off once the user stops the globe with a fist. */
    var autoSpin = true

    init { facing(home, m) }

    /** Rotation that puts [c] at the front with north up. */
    private fun facing(c: WorldCity, out: FloatArray) {
        val fx = c.x; val fy = c.y; val fz = c.z
        // North pole is world (0,-1,0); screen "up" is the north direction projected off the view axis.
        val nd = -fy
        var ux = 0f - fx * nd; var uy = -1f - fy * nd; var uz = 0f - fz * nd
        val ul = sqrt(ux * ux + uy * uy + uz * uz).takeIf { it > 1e-4f } ?: 1f
        ux /= ul; uy /= ul; uz /= ul
        val ry0 = -ux; val ry1 = -uy; val ry2 = -uz
        // rx = ry × rz
        out[0] = ry1 * fz - ry2 * fy; out[1] = ry2 * fx - ry0 * fz; out[2] = ry0 * fy - ry1 * fx
        out[3] = ry0; out[4] = ry1; out[5] = ry2
        out[6] = fx; out[7] = fy; out[8] = fz
    }

    /** Rotate about the screen Y axis by [a] (horizontal motion) and the X axis by [b] (vertical). */
    fun rotateScreen(a: Float, b: Float) {
        val ca = cos(a); val sa = sin(a)
        for (k in 0..2) {
            val r0 = m[k]; val r2 = m[6 + k]
            m[k] = ca * r0 + sa * r2; m[6 + k] = -sa * r0 + ca * r2
        }
        val cb = cos(b); val sb = sin(b)
        for (k in 0..2) {
            val r1 = m[3 + k]; val r2 = m[6 + k]
            m[3 + k] = cb * r1 + sb * r2; m[6 + k] = -sb * r1 + cb * r2
        }
    }

    /** Spin about the Earth's polar axis (world Y). */
    private fun spinWorld(a: Float) {
        val c = cos(a); val s = sin(a)
        for (row in 0..2) {
            val x = m[row * 3]; val z = m[row * 3 + 2]
            m[row * 3] = x * c - z * s; m[row * 3 + 2] = x * s + z * c
        }
    }

    private fun orthonormalize() {
        fun norm(o: Int) { val l = sqrt(m[o] * m[o] + m[o + 1] * m[o + 1] + m[o + 2] * m[o + 2]); m[o] /= l; m[o + 1] /= l; m[o + 2] /= l }
        norm(6)
        val d = m[3] * m[6] + m[4] * m[7] + m[5] * m[8]
        m[3] -= d * m[6]; m[4] -= d * m[7]; m[5] -= d * m[8]; norm(3)
        m[0] = m[4] * m[8] - m[5] * m[7]; m[1] = m[5] * m[6] - m[3] * m[8]; m[2] = m[3] * m[7] - m[4] * m[6]
    }

    fun flyTo(c: WorldCity, z: Float) { facing(c, target); toZoom = z; flying = true; angY = 0f; angX = 0f }

    /** Move a fraction [k] of the way from the current orientation to [target] (axis-angle). */
    private fun approach(k: Float): Boolean {
        // R = T · Mᵀ (rotation still to apply, in screen space)
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = target[i * 3] * m[j * 3] + target[i * 3 + 1] * m[j * 3 + 1] + target[i * 3 + 2] * m[j * 3 + 2]
        val cosA = ((r[0] + r[4] + r[8] - 1f) / 2f).coerceIn(-1f, 1f)
        val angle = kotlin.math.acos(cosA)
        if (angle < 0.002f) { target.copyInto(m); return true }
        var ax = r[7] - r[5]; var ay = r[2] - r[6]; var az = r[3] - r[1]
        val al = sqrt(ax * ax + ay * ay + az * az).takeIf { it > 1e-6f } ?: return run { target.copyInto(m); true }
        ax /= al; ay /= al; az /= al
        val th = angle * k; val c = cos(th); val s = sin(th); val t1 = 1 - c
        val rot = floatArrayOf(
            c + ax * ax * t1, ax * ay * t1 - az * s, ax * az * t1 + ay * s,
            ay * ax * t1 + az * s, c + ay * ay * t1, ay * az * t1 - ax * s,
            az * ax * t1 - ay * s, az * ay * t1 + ax * s, c + az * az * t1,
        )
        val out = FloatArray(9)
        for (i in 0..2) for (j in 0..2) out[i * 3 + j] = rot[i * 3] * m[j] + rot[i * 3 + 1] * m[3 + j] + rot[i * 3 + 2] * m[6 + j]
        out.copyInto(m)
        return false
    }

    fun step(t: Float) {
        val dt = if (lastT < 0f) 0f else (t - lastT).coerceIn(0f, 0.05f); lastT = t
        if (flying) {
            val k = 1f - exp(-dt * 4.5f)
            val done = approach(k)
            zoom += (toZoom - zoom) * k
            if (done && abs(toZoom - zoom) < 0.01f) flying = false
        } else if (!dragging) {
            rotateScreen(angY * dt, angX * dt)
            // Momentum from a swipe fades over a couple of seconds, about any axis.
            angY *= exp(-dt * 0.8f); angX *= exp(-dt * 0.8f)
            if (autoSpin && zoom < 1.4f) spinWorld(0.06f * dt)
        }
        orthonormalize()
    }
}

/** Per-frame projected city positions for hit testing. */
private class Projected(n: Int) { val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n) }

@Composable
fun GlobeView() {
    val holo = LocalHolo.current
    val cam = remember { GlobeCamera() }
    val proj = remember { Projected(worldCities.size) }
    var selected by remember { mutableIntStateOf(worldCities.indexOf(home)) }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labels = remember { HashMap<String, TextLayoutResult>() }
    val hover = remember { intArrayOf(-1) }

    LaunchedEffect(Unit) {
        if (holo.earth == null) {
            holo.earth = withContext(Dispatchers.Default) {
                runCatching {
                    val day = Res.readBytes("files/earth_day.jpg").decodeToImageBitmap()
                    val night = Res.readBytes("files/earth_night.jpg").decodeToImageBitmap()
                    createEarthRenderer(day, night)
                }.getOrNull()
            }
            holo.earthLoaded = true
        }
    }
    DisposableEffect(cam) {
        holo.screenZoom = { amount, _ ->
            if (amount > 0f) { cam.flying = false; cam.zoom = min(16f, cam.zoom * (1f + amount * 0.6f)); true }
            else if (cam.zoom > 1.02f) { cam.flying = false; cam.zoom = maxOf(1f, cam.zoom / (1f - amount * 0.6f)); true }
            else false
        }
        holo.screenDrag = { dx, dy ->
            val r = min(holo.size.width, holo.size.height) * 0.36f * cam.zoom
            cam.flying = false
            cam.rotateScreen(dx / r, dy / r)
            cam.angY = 0f; cam.angX = 0f
        }
        // Open-hand swipe spins the globe along the swipe direction, any angle.
        holo.screenFling = { vx, vy ->
            val r = min(holo.size.width, holo.size.height) * 0.36f * cam.zoom
            cam.flying = false
            cam.angY = vx / r * 0.45f
            cam.angX = (if (holo.invertVertical) -vy else vy) / r * 0.45f
        }
        // A full palm held still stops it right there (a fist grabs and turns it).
        holo.screenHold = {
            if (abs(cam.angY) > 0.01f || abs(cam.angX) > 0.01f || cam.flying) holo.sfx(Sfx.KeyOp, pitch = 0.7f)
            cam.angY = 0f; cam.angX = 0f; cam.flying = false; cam.autoSpin = false
        }
        onDispose { holo.screenZoom = null; holo.screenDrag = null; holo.screenTap = null; holo.screenFling = null; holo.screenHold = null }
    }

    fun select(i: Int) {
        selected = i
        cam.flyTo(worldCities[i], maxOf(cam.zoom, 2.6f))
        holo.sfx(Sfx.Lock)
        holo.sfx(Sfx.Telemetry, gain = 0.5f)
        holo.burst(Offset(proj.x[i], proj.y[i]), warm = true)
    }

    holo.screenTap = { at ->
        // Prefer the city that is currently highlighted, so a tap selects what you see.
        // Log: 13/14 hand taps landed >60 px from any city (often over sea): fall back to
        // the nearest visible city within 140 px.
        val i = hover[0].takeIf { it >= 0 } ?: nearestCity(proj, at, 140f)
        holo.event("city-tap", if (i >= 0) worldCities[i].name else "miss @ ${at.x.toInt()},${at.y.toInt()}")
        if (i >= 0) { select(i); true } else false
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { cam.dragging = true; cam.flying = false },
                        onDragEnd = { cam.dragging = false },
                        onDragCancel = { cam.dragging = false },
                    ) { change, d ->
                        change.consume()
                        val r = min(size.width, size.height) * 0.36f * cam.zoom
                        cam.rotateScreen(d.x / r, d.y / r)
                        cam.angY = d.x / r * 60f; cam.angX = d.y / r * 60f
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val i = nearestCity(proj, p, 26f)
                        if (i >= 0) select(i)
                    }
                }
        ) {
            val t = holo.seconds
            cam.step(t)
            val c = Offset(size.width * 0.42f, size.height * 0.54f)
            val r = min(size.width, size.height) * 0.36f * cam.zoom
            // Hand cursors are coarser than a mouse: a 40 px catch radius for hover.
            val h = if (holo.hand.visible) nearestCity(proj, holo.hand.position, 40f) else -1
            if (h != hover[0]) { hover[0] = h; if (h >= 0) holo.sfx(Sfx.Hover, pitch = 1.2f) }
            val mark = kotlin.time.TimeSource.Monotonic.markNow()
            drawGlobe(holo, cam, c, r, t, selected, h, proj, measurer, labels)
            val us = mark.elapsedNow().inWholeMicroseconds
            drawCost[0] = drawCost[0] * 0.95f + us * 0.05f
        }

        CityPanel(holo, worldCities[selected], onEnter = { rect -> holo.navigator.push(CityScreen(worldCities[selected]), rect) }, onHome = { select(worldCities.indexOf(home)) })
    }
}

private fun nearestCity(p: Projected, at: Offset, maxPx: Float): Int {
    var best = -1; var bestD = maxPx
    for (i in worldCities.indices) {
        if (p.z[i] <= 0.05f) continue
        val d = hypot(p.x[i] - at.x, p.y[i] - at.y)
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

@Composable
private fun CityPanel(holo: HoloOsState, city: WorldCity, onEnter: (androidx.compose.ui.geometry.Rect) -> Unit, onHome: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(end = 36.dp, top = 90.dp), contentAlignment = Alignment.TopEnd) {
        Column(horizontalAlignment = Alignment.End) {
            BasicText("TARGET LOCK", style = holoText(10.sp, HoloColors.Muted, glow = 0f, spacing = 4.sp))
            BasicText(city.name.uppercase(), style = holoText(30.sp, HoloColors.Amber, glow = 18f, spacing = 4.sp))
            BasicText(city.country.uppercase() + if (city.capital) "  ·  CAPITAL" else "", style = holoText(12.sp, HoloColors.Cyan))
            Spacer(Modifier.height(10.dp))
            Stat("POPULATION", formatPop(city.population))
            Stat("COORDINATES", "${fmt2(abs(city.lat))}°${if (city.lat >= 0) "N" else "S"}  ${fmt2(abs(city.lon))}°${if (city.lon >= 0) "E" else "W"}")
            Stat("SOLAR TIME", solarTime(city.lon))
            if (city != home) Stat("FROM ${home.name.uppercase()}", "${greatCircleKm(home.lat, home.lon, city.lat, city.lon)} KM")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelButton("globe-mode", if (holo.earthMode == EarthMode.Real) "◐ HOLO" else "◑ REAL") {
                    holo.earthMode = if (holo.earthMode == EarthMode.Real) EarthMode.Holo else EarthMode.Real
                }
                PanelButton("globe-home", "⌂ HOME", onTap = onHome)
                PanelButton("globe-enter", "ENTER ►", amber = true, onDive = onEnter)
            }
            if (holo.earthMode == EarthMode.Real && holo.earthLoaded && holo.earth == null) {
                BasicText("REAL MODE NEEDS ANDROID 13+ · SHOWING HOLO", style = holoText(9.sp, HoloColors.Alert, glow = 0f), modifier = Modifier.padding(top = 8.dp))
            }
            BasicText(
                "DRAG SPIN · SCROLL / PINCH ZOOM · TAP A CITY",
                style = holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp), modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp))
        Spacer(Modifier.width(12.dp))
        BasicText(value, style = holoText(13.sp, HoloColors.Text, glow = 4f))
    }
}

@Composable
private fun PanelButton(
    id: String, label: String, amber: Boolean = false,
    onTap: (() -> Unit)? = null, onDive: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
) {
    HoloTarget(id, Modifier.size(104.dp, 36.dp), onTap = onTap, onDive = onDive) { e ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val base = if (amber || e.charge > 0.2f) HoloColors.Amber else HoloColors.Cyan
            Canvas(Modifier.fillMaxSize()) {
                drawPath(cutCornerPath(size, 9f), base, alpha = 0.05f + e.hover * 0.08f, blendMode = BlendMode.Plus)
                glowPath(base, cutCornerPath(size, 9f, 1f), 1.1f, 0.6f + e.hover * 0.6f + e.pulse)
            }
            BasicText(label, style = holoText(11.sp, base))
        }
    }
}

private fun fmt2(v: Float) = ((v * 100).toInt() / 100f).toString()

fun formatPop(p: Long): String = when {
    p >= 1_000_000 -> "${(p / 100_000) / 10.0} M"
    p >= 1_000 -> "${p / 1000} K"
    else -> p.toString()
}

/** Local mean solar time from longitude — honest about being solar, not civil, time. */
fun solarTime(lon: Float): String {
    val ms = epochMillis()
    val hours = ((ms % 86_400_000L) / 3_600_000.0 + lon / 15.0 + 48.0) % 24.0
    val h = hours.toInt(); val m = ((hours - h) * 60).toInt()
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

// ---------------------------------------------------------------- rendering

private val gratPath = Path(); private val coastFront = Path(); private val coastBack = Path()
private val arcPath = Path(); private val termPath = Path()
private var sunCache = sunDirection(); private var sunT = 0L

private fun DrawScope.drawGlobe(
    holo: HoloOsState, cam: GlobeCamera, c: Offset, r: Float, t: Float, selected: Int, hovered: Int,
    out: Projected, measurer: TextMeasurer, labels: HashMap<String, TextLayoutResult>,
) {
    val m = cam.m
    val real = holo.earthMode == EarthMode.Real && holo.earth != null
    val w = size.width; val h = size.height
    val margin = 60f

    // Live sun, refreshed every few seconds.
    val now = epochMillis()
    if (now - sunT > 5_000) { sunCache = sunDirection(now); sunT = now }
    val sun = sunCache
    val sunView = floatArrayOf(
        m[0] * sun[0] + m[1] * sun[1] + m[2] * sun[2],
        m[3] * sun[0] + m[4] * sun[1] + m[5] * sun[2],
        m[6] * sun[0] + m[7] * sun[1] + m[8] * sun[2],
    )

    // --- body
    if (real) {
        drawCircle(holo.earth!!.brush(c.x, c.y, r, m, sun, sunView), r * 1.14f, c)
    } else {
        drawCircle(HoloColors.Cyan, r, c, alpha = 0.05f, blendMode = BlendMode.Plus)
        drawCircle(HoloColors.Cyan, r * 1.06f, c, alpha = 0.04f, style = Stroke(r * 0.08f), blendMode = BlendMode.Plus)
    }
    glowCircle(HoloColors.Cyan, c, r, 1.2f, if (real) 0.35f else 0.85f)
    if (cam.zoom < 1.6f) {
        glowCircle(HoloColors.CyanDim, c, r * 1.25f, 1f, 0.4f)
        glowArc(HoloColors.Cyan, c, r * 1.25f, t * 20f, 50f, 4f, 0.5f)
        glowArc(HoloColors.Cyan, c, r * 1.33f, -t * 14f, 120f, 1f, 0.5f)
    }

    // --- graticule (one path)
    gratPath.reset()
    fun addLine(n: Int, point: (Int) -> Pair<Float, Float>) {
        var pen = false
        for (k in 0..n) {
            val (la, lo) = point(k)
            val x = cos(la) * sin(lo); val y = -sin(la); val z = cos(la) * cos(lo)
            val x1 = m[0] * x + m[1] * y + m[2] * z; val y2 = m[3] * x + m[4] * y + m[5] * z
            val z2 = m[6] * x + m[7] * y + m[8] * z
            val px = c.x + x1 * r; val py = c.y + y2 * r
            if (z2 > 0f) { if (pen) gratPath.lineTo(px, py) else gratPath.moveTo(px, py); pen = true } else pen = false
        }
    }
    for (lat in -75..75 step 15) addLine(72) { k -> lat.toFloat().rad to (k * 5f - 180f).rad }
    for (lon in -180 until 180 step 15) addLine(36) { k -> (k * 5f - 90f).rad to lon.toFloat().rad }
    drawPath(gratPath, HoloColors.Cyan, alpha = if (real) 0.10f else 0.22f, style = Stroke(1f), blendMode = BlendMode.Plus)

    // --- coastlines (one path front, one back)
    coastFront.reset(); coastBack.reset()
    val xyz = Coastlines.xyz; val starts = Coastlines.starts
    val drawBack = !real && cam.zoom < 1.6f
    for (li in 0 until starts.size - 1) {
        var penF = false; var penB = false
        for (pi in starts[li] until starts[li + 1]) {
            val x = xyz[pi * 3]; val y = xyz[pi * 3 + 1]; val z = xyz[pi * 3 + 2]
            val x1 = m[0] * x + m[1] * y + m[2] * z; val y2 = m[3] * x + m[4] * y + m[5] * z
            val z2 = m[6] * x + m[7] * y + m[8] * z
            val px = c.x + x1 * r; val py = c.y + y2 * r
            val onScreen = px > -margin && px < w + margin && py > -margin && py < h + margin
            if (z2 > 0f && onScreen) {
                if (penF) coastFront.lineTo(px, py) else coastFront.moveTo(px, py); penF = true
            } else penF = false
            if (drawBack && z2 <= 0f) {
                if (penB) coastBack.lineTo(px, py) else coastBack.moveTo(px, py); penB = true
            } else penB = false
        }
    }
    if (drawBack) drawPath(coastBack, HoloColors.Cyan, alpha = 0.08f, style = Stroke(1f), blendMode = BlendMode.Plus)
    if (real) {
        drawPath(coastFront, HoloColors.Cyan, alpha = 0.30f, style = Stroke(1f), blendMode = BlendMode.Plus)
    } else {
        drawPath(coastFront, HoloColors.Cyan, alpha = 0.10f, style = Stroke(5f), blendMode = BlendMode.Plus)
        drawPath(coastFront, HoloColors.Cyan, alpha = 0.95f, style = Stroke(1.2f), blendMode = BlendMode.Plus)
    }

    // --- day / night terminator: the great circle perpendicular to the sun
    run {
        val ax = if (abs(sun[1]) < 0.9f) 0f else 1f; val ay = if (abs(sun[1]) < 0.9f) 1f else 0f; val az = 0f
        var ux = ay * sun[2] - az * sun[1]; var uy = az * sun[0] - ax * sun[2]; var uz = ax * sun[1] - ay * sun[0]
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
        val vx = sun[1] * uz - sun[2] * uy; val vy = sun[2] * ux - sun[0] * uz; val vz = sun[0] * uy - sun[1] * ux
        termPath.reset(); var pen = false
        for (k in 0..120) {
            val a = k / 120f * 2 * PI.toFloat()
            val x = cos(a) * ux + sin(a) * vx; val y = cos(a) * uy + sin(a) * vy; val z = cos(a) * uz + sin(a) * vz
            val x1 = m[0] * x + m[1] * y + m[2] * z; val y2 = m[3] * x + m[4] * y + m[5] * z
            val z2 = m[6] * x + m[7] * y + m[8] * z
            if (z2 > 0f) { val px = c.x + x1 * r; val py = c.y + y2 * r; if (pen) termPath.lineTo(px, py) else termPath.moveTo(px, py); pen = true } else pen = false
        }
        drawPath(termPath, HoloColors.Amber, alpha = 0.55f, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), -t * 20f)), blendMode = BlendMode.Plus)
    }

    // --- scan band (holo)
    if (!real) {
        val scanLat = sin(t * 0.6f) * 65f
        gratPath.reset(); addLine(72) { k -> scanLat.rad to (k * 5f - 180f).rad }
        drawPath(gratPath, HoloColors.Amber, alpha = 0.55f, style = Stroke(1.5f), blendMode = BlendMode.Plus)
    }

    // --- arc from home to selection
    val sel = worldCities[selected]
    if (sel != home) {
        arcPath.reset(); var pen = false
        val a = home; val b = sel
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f)
        val om = kotlin.math.acos(dot); val so = sin(om).coerceAtLeast(1e-4f)
        for (k in 0..64) {
            val f = k / 64f
            val s0 = sin((1 - f) * om) / so; val s1 = sin(f * om) / so
            val lift = 1f + sin(f * PI.toFloat()) * (0.08f + om * 0.08f)
            val x = (a.x * s0 + b.x * s1) * lift; val y = (a.y * s0 + b.y * s1) * lift; val z = (a.z * s0 + b.z * s1) * lift
            val x1 = m[0] * x + m[1] * y + m[2] * z; val y2 = m[3] * x + m[4] * y + m[5] * z
            val z2 = m[6] * x + m[7] * y + m[8] * z
            if (z2 > -0.05f) { val px = c.x + x1 * r; val py = c.y + y2 * r; if (pen) arcPath.lineTo(px, py) else arcPath.moveTo(px, py); pen = true } else pen = false
        }
        drawPath(arcPath, HoloColors.Amber, alpha = 0.18f, style = Stroke(7f), blendMode = BlendMode.Plus)
        drawPath(arcPath, HoloColors.Amber, alpha = 0.95f, style = Stroke(1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f), -t * 60f)), blendMode = BlendMode.Plus)
    }

    // --- cities
    val maxLabels = (5f * cam.zoom * cam.zoom).toInt().coerceIn(6, 40)
    val placed = ArrayList<androidx.compose.ui.geometry.Rect>(maxLabels + 2)
    var labelCount = 0
    worldCities.forEachIndexed { i, city ->
        val x1 = m[0] * city.x + m[1] * city.y + m[2] * city.z; val y2 = m[3] * city.x + m[4] * city.y + m[5] * city.z
        val z2 = m[6] * city.x + m[7] * city.y + m[8] * city.z
        val px = c.x + x1 * r; val py = c.y + y2 * r
        out.x[i] = px; out.y[i] = py; out.z[i] = z2
        if (z2 <= 0.05f || px < 0 || py < 0 || px > w || py > h) return@forEachIndexed
        val isSel = i == selected; val isHover = i == hovered
        val col = when { isSel -> HoloColors.Amber; city.capital -> HoloColors.Ice; else -> HoloColors.Cyan }
        val rad = 1.3f + (kotlin.math.log10(city.population.coerceAtLeast(10_000).toFloat()) - 4f) * 0.6f
        drawCircle(col, rad * 3f, Offset(px, py), alpha = 0.10f * z2, blendMode = BlendMode.Plus)
        drawCircle(col, rad, Offset(px, py), alpha = 0.95f * z2, blendMode = BlendMode.Plus)
        if (isSel || isHover) {
            val ping = (t * 0.9f) % 1f
            drawCircle(col, 6f + ping * 22f, Offset(px, py), alpha = (1f - ping) * 0.8f, style = Stroke(1.2f), blendMode = BlendMode.Plus)
            glowCircle(col, Offset(px, py), 11f, 1.2f, 0.9f)
        }
        val wantLabel = isSel || isHover || labelCount < maxLabels
        if (wantLabel) {
            val key = city.name
            val layout = labels.getOrPut(key) {
                measurer.measure(city.name.uppercase(), holoText(10.sp, HoloColors.Text, glow = 0f, spacing = 1.5.sp))
            }
            val lx = px + 7f; val ly = py - layout.size.height - 3f
            val rect = androidx.compose.ui.geometry.Rect(lx, ly, lx + layout.size.width, ly + layout.size.height)
            val free = placed.none { it.overlaps(rect) }
            if (free || isSel || isHover) {
                placed += rect
                if (!isSel && !isHover) labelCount++
                drawText(layout, color = if (isSel) HoloColors.Amber else if (isHover) HoloColors.Ice else HoloColors.Text.copy(alpha = 0.75f * z2), topLeft = Offset(lx, ly))
            }
        }
    }

    // --- callout from the selected city to the info panel
    if (out.z[selected] > 0.05f) {
        val p = Offset(out.x[selected], out.y[selected])
        val elbow = Offset(w * 0.70f, h * 0.22f)
        val end = Offset(w - 300f, h * 0.22f)
        drawLine(HoloColors.Amber, p, elbow, 1f, alpha = 0.45f, blendMode = BlendMode.Plus)
        drawLine(HoloColors.Amber, elbow, end, 1f, alpha = 0.45f, blendMode = BlendMode.Plus)
        glowDot(HoloColors.Amber, elbow, 2f)
    }

    // --- satellites with trails
    for (s in 0 until 3) {
        val inc = 0.4f + s * 0.45f; val speed = 0.35f + s * 0.12f; val alt = 1.18f + s * 0.07f
        for (k in 0 until 14) {
            val a = (t - k * 0.04f) * speed + s * 2.1f
            val x0 = cos(a); val z0 = sin(a)
            val y = -z0 * sin(inc); val z = z0 * cos(inc)
            val x1 = m[0] * x0 + m[1] * y + m[2] * z; val y2 = m[3] * x0 + m[4] * y + m[5] * z
            val z2 = m[6] * x0 + m[7] * y + m[8] * z
            val front = z2 > 0f || hypot(x1, y2) > 1f
            val col = if (s == 1) HoloColors.Amber else HoloColors.Ice
            val alpha = (1f - k / 14f) * (if (front) 1f else 0.15f)
            if (k == 0) glowDot(col, Offset(c.x + x1 * r * alt, c.y + y2 * r * alt), 2.4f, alpha)
            else drawCircle(col, 1.2f, Offset(c.x + x1 * r * alt, c.y + y2 * r * alt), alpha = alpha * 0.6f, blendMode = BlendMode.Plus)
        }
    }

    // --- zoom readout
    val zl = labels.getOrPut("zoom:${(cam.zoom * 10).toInt()}:${(drawCost[0] / 100).toInt()}") {
        measurer.measure("ZOOM ×${(cam.zoom * 10).toInt() / 10f}   ${worldCities.size} CITIES   ${Coastlines.pointCount} COAST PTS   CPU ${(drawCost[0] / 100).toInt() / 10f} MS", holoText(9.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp))
    }
    drawText(zl, topLeft = Offset(28f, h - 60f))
    if (labels.size > 600) labels.keys.filter { it.startsWith("zoom:") }.forEach { labels.remove(it) }
}

private fun Color.dim(a: Float) = copy(alpha = alpha * a)

package com.zain.holoos.tracking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/** Every gesture HoloOS understands, in the order the legend shows them. */
enum class Gesture(val label: String, val action: String) {
    Point("POINT", "Move cursor"),
    Pinch("PINCH", "Select / open"),
    PinchDrag("PINCH + MOVE", "Drag / spin"),
    TwoHandZoom("TWO-HAND PINCH", "Zoom in / out"),
    SwipeBack("PALM SWIPE ←", "Back"),
    FistHold("FIST · HOLD", "Home"),
    ThumbsUp("THUMBS UP · HOLD", "Accept prediction"),
    OpenPalm("OPEN PALM", "Hover"),
}

enum class HandPose { None, Open, Point, Pinch, Fist, ThumbsUp, Victory }

/**
 * One Euro filter (Casiez et al.): heavy smoothing when the hand is still, almost none
 * when it moves fast. Removes camera jitter without adding lag to quick motions.
 */
class OneEuro(private val minCutoff: Float, private val beta: Float, private val dCutoff: Float = 1f) {
    private var x = 0f; private var dx = 0f; private var init = false
    private fun alpha(cutoff: Float, dt: Float): Float { val tau = 1f / (2f * PI.toFloat() * cutoff); return 1f / (1f + tau / dt) }
    fun reset() { init = false }
    fun filter(v: Float, dt: Float): Float {
        if (!init) { x = v; dx = 0f; init = true; return v }
        val d = (v - x) / dt
        dx += alpha(dCutoff, dt) * (d - dx)
        x += alpha(minCutoff + beta * abs(dx), dt) * (v - x)
        return x
    }
}

/**
 * Maps a hand point to the screen. Coordinates are first made body-relative (origin at
 * the shoulder midpoint, unit = shoulder width) so the mapping survives the user moving
 * around; calibration fits an affine transform from that space to the screen.
 */
class Calibration(val bodySpace: Boolean, val m: FloatArray) {
    fun apply(bx: Float, by: Float) = Offset(m[0] * bx + m[1] * by + m[2], m[3] * bx + m[4] * by + m[5])
    fun serialize() = (if (bodySpace) "body" else "camera") + "|" + m.joinToString(",")

    companion object {
        fun parse(s: String?): Calibration? = runCatching {
            val (kind, nums) = s!!.trim().split("|")
            Calibration(kind == "body", nums.split(",").map { it.toFloat() }.toFloatArray().also { require(it.size == 6) })
        }.getOrNull()

        /** Least squares affine fit from (bx, by) to (sx, sy). Returns the transform and its RMS error. */
        fun fit(bodySpace: Boolean, samples: List<FloatArray>): Pair<Calibration, Float>? {
            if (samples.size < 3) return null
            val ata = Array(3) { DoubleArray(3) }
            val atx = DoubleArray(3); val aty = DoubleArray(3)
            for (s in samples) {
                val row = doubleArrayOf(s[0].toDouble(), s[1].toDouble(), 1.0)
                for (i in 0..2) { for (j in 0..2) ata[i][j] += row[i] * row[j]; atx[i] += row[i] * s[2]; aty[i] += row[i] * s[3] }
            }
            val px = solve3(ata, atx) ?: return null
            val py = solve3(ata, aty) ?: return null
            val cal = Calibration(bodySpace, floatArrayOf(px[0].toFloat(), px[1].toFloat(), px[2].toFloat(), py[0].toFloat(), py[1].toFloat(), py[2].toFloat()))
            var err = 0f
            for (s in samples) { val p = cal.apply(s[0], s[1]); err += (p.x - s[2]) * (p.x - s[2]) + (p.y - s[3]) * (p.y - s[3]) }
            return cal to kotlin.math.sqrt(err / samples.size)
        }

        private fun solve3(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
            val a = Array(3) { a0[it].copyOf() }; val b = b0.copyOf()
            for (c in 0..2) {
                var p = c
                for (r in c + 1..2) if (abs(a[r][c]) > abs(a[p][c])) p = r
                if (abs(a[p][c]) < 1e-12) return null
                val t = a[c]; a[c] = a[p]; a[p] = t; val tb = b[c]; b[c] = b[p]; b[p] = tb
                for (r in 0..2) if (r != c) {
                    val f = a[r][c] / a[c][c]
                    for (k in c..2) a[r][k] -= f * a[c][k]
                    b[r] -= f * b[c]
                }
            }
            return DoubleArray(3) { b[it] / a[it][it] }
        }
    }
}

/** What the engine asks the app to do. Implemented by HoloOsState. */
interface GestureSink {
    fun onCursor(x: Float, y: Float, timeMs: Long)
    fun onPress(pressed: Boolean)
    fun onTap(x: Float, y: Float)
    fun onDrag(dx: Float, dy: Float)
    fun onZoom(amount: Float, at: Offset)
    fun onBack()
    fun onHome()
    fun onAccept()
    fun onHandsLost()
}

/** Per-hand state the renderer reads: screen-space skeleton plus pose. */
class HandView {
    val pts = FloatArray(42)
    var visible = false
    var isRight = true
    var pose = HandPose.None
    var cursorX = 0f; var cursorY = 0f
    /** Raw camera-space landmarks (aspect-corrected, 0..aspect × 0..1) for the mini view. */
    val raw = FloatArray(42)
}

class GestureEngine(private val sink: GestureSink) {
    var calibration: Calibration? = Calibration.parse(loadSetting("calibration"))
        private set

    // ---- state the HUD reads (snapshot state; changes only on gesture changes)
    var active by mutableStateOf<Set<Gesture>>(emptySet())
        private set
    var holdProgress by mutableFloatStateOf(0f)
        private set
    var pipeLatencyMs by mutableFloatStateOf(0f)
        private set
    var cameraFps by mutableFloatStateOf(0f)
        private set
    var bodyVisible by mutableStateOf(false)
        private set

    // ---- state the renderer reads every frame (plain fields)
    val hands = arrayOf(HandView(), HandView())
    var shoulderRaw: FloatArray? = null; private set
    var aspect = 16f / 9f; private set

    /** When set, pinches go to calibration instead of the UI. */
    var captureHook: ((pinchStart: Boolean, primary: HandView) -> Unit)? = null

    private var lastFrame = -1
    private var lastSeenHandsMs = 0L
    private var shoulderMidX = 0f; private var shoulderMidY = 0f; private var shoulderW = 0f
    private var shoulderSeenMs = 0L
    private val fx = OneEuro(1.4f, 0.006f); private val fy = OneEuro(1.4f, 0.006f)
    private var lastCursorT = 0L
    private var lastCx = 0f; private var lastCy = 0f
    private var vx = 0f; private var vy = 0f

    private var pinching = false
    private var pinchStartT = 0L; private var pinchStartX = 0f; private var pinchStartY = 0f
    private var dragging = false
    private var twoHandDist = 0f
    private var rawPose = HandPose.None; private var rawCount = 0; private var pose = HandPose.None
    private var holdPose = HandPose.None; private var holdStart = 0L; private var holdFired = false
    private var swipeCooldownUntil = 0L

    /** Last body-space (or camera-space) anchor of the primary hand, for calibration. */
    var lastAnchorB = FloatArray(2); private set
    var lastAnchorBodySpace = false; private set

    fun process(f: TrackFrame?, nowMs: Long, screenW: Float, screenH: Float) {
        if (f == null || screenW <= 0f) return
        if (nowMs - f.receivedMs > 400 || f.hands.isEmpty()) {
            if (lastSeenHandsMs != 0L && nowMs - lastSeenHandsMs > 250) handsLost()
            if (f.id != lastFrame) { lastFrame = f.id; updateBody(f, nowMs) }
            return
        }
        if (f.id == lastFrame) return
        lastFrame = f.id
        lastSeenHandsMs = nowMs
        aspect = f.aspect
        cameraFps = f.cameraFps
        pipeLatencyMs = pipeLatencyMs * 0.9f + (f.receivedMs - f.captureMs).toFloat().coerceIn(0f, 500f) * 0.1f
        updateBody(f, nowMs)

        // Primary hand = the user's right hand if visible, else whichever is there.
        val ordered = f.hands.sortedByDescending { it.isRight }.take(2)
        for (i in 0..1) hands[i].visible = i < ordered.size
        ordered.forEachIndexed { i, h -> analyse(h, hands[i], screenW, screenH) }

        val primary = hands[0]
        val t = nowMs
        val dt = if (lastCursorT == 0L) 0.016f else ((t - lastCursorT) / 1000f).coerceIn(0.004f, 0.1f)
        val cx = fx.filter(primary.cursorX, dt); val cy = fy.filter(primary.cursorY, dt)
        vx = vx * 0.6f + (cx - lastCx) / dt * 0.4f; vy = vy * 0.6f + (cy - lastCy) / dt * 0.4f
        val moveX = cx - lastCx; val moveY = cy - lastCy
        lastCx = cx; lastCy = cy; lastCursorT = t
        primary.cursorX = cx; primary.cursorY = cy

        // Debounced pose: 3 consistent frames, except pinch which is immediate (hysteresis).
        val p = primary.pose
        if (p == rawPose) rawCount++ else { rawPose = p; rawCount = 1 }
        if (p == HandPose.Pinch || rawCount >= 3) pose = p

        val now = mutableSetOf<Gesture>()
        val twoPinch = ordered.size == 2 && hands[0].pose == HandPose.Pinch && hands[1].pose == HandPose.Pinch

        sink.onCursor(cx, cy, t)

        val hook = captureHook
        if (hook != null) {
            val isPinch = pose == HandPose.Pinch
            if (isPinch && !pinching) hook(true, primary)
            pinching = isPinch
            sink.onPress(isPinch)
            active = setOf(if (isPinch) Gesture.Pinch else if (pose == HandPose.Open) Gesture.OpenPalm else Gesture.Point)
            return
        }

        if (twoPinch) {
            val d = hypot(hands[0].cursorX - hands[1].cursorX, hands[0].cursorY - hands[1].cursorY)
            if (twoHandDist > 0f) {
                val mid = Offset((hands[0].cursorX + hands[1].cursorX) / 2, (hands[0].cursorY + hands[1].cursorY) / 2)
                sink.onZoom((d / twoHandDist - 1f) * 3f, mid)
            }
            twoHandDist = d
            dragging = false; pinching = true
            now += Gesture.TwoHandZoom
        } else {
            twoHandDist = 0f
            when {
                pose == HandPose.Pinch && !pinching -> {
                    pinching = true; dragging = false
                    pinchStartT = t; pinchStartX = cx; pinchStartY = cy
                    sink.onPress(true)
                    now += Gesture.Pinch
                }
                pose == HandPose.Pinch -> {
                    if (!dragging && hypot(cx - pinchStartX, cy - pinchStartY) > 40f) dragging = true
                    if (dragging) { sink.onDrag(moveX, moveY); now += Gesture.PinchDrag } else now += Gesture.Pinch
                }
                pinching -> {
                    pinching = false
                    sink.onPress(false)
                    if (!dragging && t - pinchStartT < 450) sink.onTap(pinchStartX, pinchStartY)
                    dragging = false
                }
            }
        }

        if (pose == HandPose.Point) now += Gesture.Point
        if (pose == HandPose.Open) {
            now += Gesture.OpenPalm
            if (t > swipeCooldownUntil && vx < -2.0f * screenW && abs(vx) > 2f * abs(vy)) {
                swipeCooldownUntil = t + 800
                sink.onBack()
                now += Gesture.SwipeBack
            }
        }

        // Hold gestures with a visible progress ring.
        val holdTarget = when (pose) { HandPose.Fist -> HandPose.Fist; HandPose.ThumbsUp -> HandPose.ThumbsUp; else -> HandPose.None }
        if (holdTarget != holdPose) { holdPose = holdTarget; holdStart = t; holdFired = false }
        val needed = if (holdPose == HandPose.Fist) 900f else 550f
        holdProgress = if (holdPose == HandPose.None || holdFired) 0f else ((t - holdStart) / needed).coerceIn(0f, 1f)
        if (holdPose == HandPose.Fist) now += Gesture.FistHold
        if (holdPose == HandPose.ThumbsUp) now += Gesture.ThumbsUp
        if (!holdFired && holdProgress >= 1f) {
            holdFired = true; holdProgress = 0f
            if (holdPose == HandPose.Fist) sink.onHome() else sink.onAccept()
        }
        if (now != active) active = now
    }

    private fun handsLost() {
        lastSeenHandsMs = 0L
        hands.forEach { it.visible = false }
        if (pinching) { pinching = false; sink.onPress(false) }
        dragging = false; twoHandDist = 0f; pose = HandPose.None; holdPose = HandPose.None; holdProgress = 0f
        fx.reset(); fy.reset(); lastCursorT = 0L
        if (active.isNotEmpty()) active = emptySet()
        sink.onHandsLost()
    }

    private fun updateBody(f: TrackFrame, nowMs: Long) {
        val s = f.shoulders
        if (s != null) {
            val a = f.aspect
            val mx = (s[0] + s[2]) / 2 * a; val my = (s[1] + s[3]) / 2
            val w = hypot((s[0] - s[2]) * a, s[1] - s[3])
            if (w > 0.03f) {
                val k = if (shoulderW == 0f) 1f else 0.25f
                shoulderMidX += (mx - shoulderMidX) * k; shoulderMidY += (my - shoulderMidY) * k; shoulderW += (w - shoulderW) * k
                shoulderSeenMs = nowMs
            }
            shoulderRaw = floatArrayOf(s[0] * a, s[1], s[2] * a, s[3])
        }
        bodyVisible = nowMs - shoulderSeenMs < 1000 && shoulderW > 0f
        if (!bodyVisible) shoulderRaw = null
    }

    private fun analyse(h: TrackedHand, out: HandView, sw: Float, sh: Float) {
        val a = aspect
        fun px(i: Int) = h.x(i) * a
        fun py(i: Int) = h.y(i)
        fun d(i: Int, j: Int) = hypot(px(i) - px(j), py(i) - py(j))
        val palm = d(0, 9).coerceAtLeast(1e-4f)

        // Pose classification from finger geometry, normalised by palm size.
        fun ext(tip: Int, pip: Int) = d(tip, 0) > d(pip, 0) * 1.12f
        val index = ext(8, 6); val middle = ext(12, 10); val ring = ext(16, 14); val pinky = ext(20, 18)
        val thumbOut = d(4, 5) > palm * 0.62f
        val pinchRatio = d(4, 8) / palm
        val wasPinch = out.pose == HandPose.Pinch
        val pose = when {
            pinchRatio < (if (wasPinch) 0.42f else 0.28f) -> HandPose.Pinch
            thumbOut && !index && !middle && !ring && !pinky && py(4) < py(5) - palm * 0.35f -> HandPose.ThumbsUp
            !index && !middle && !ring && !pinky -> HandPose.Fist
            index && middle && ring && pinky -> HandPose.Open
            index && middle && !ring && !pinky -> HandPose.Victory
            index && !middle && !ring && !pinky -> HandPose.Point
            else -> HandPose.Open
        }
        out.pose = pose
        out.isRight = h.isRight

        // Stable anchor: between the index knuckle and the thumb-index pinch point, so
        // closing a pinch barely moves the cursor.
        val ax = (px(5) + (px(4) + px(8)) / 2) / 2
        val ay = (py(5) + (py(4) + py(8)) / 2) / 2
        val s = mapToScreen(ax, ay, out === hands[0])
        out.cursorX = s.x * sw; out.cursorY = s.y * sh

        // Screen skeleton: landmarks around the cursor at a constant on-screen size.
        val scale = min(sw, sh) * 0.09f / palm
        for (i in 0 until 21) {
            out.pts[i * 2] = (px(i) - ax) * scale
            out.pts[i * 2 + 1] = (py(i) - ay) * scale
            out.raw[i * 2] = px(i); out.raw[i * 2 + 1] = py(i)
        }
    }

    private fun mapToScreen(ax: Float, ay: Float, primary: Boolean): Offset {
        val cal = calibration
        val body = bodyVisible
        val bx: Float; val by: Float; val bodySpace: Boolean
        if (body && (cal == null || cal.bodySpace)) {
            bx = (ax - shoulderMidX) / shoulderW; by = (ay - shoulderMidY) / shoulderW; bodySpace = true
        } else {
            bx = ax; by = ay; bodySpace = false
        }
        if (primary) { lastAnchorB[0] = bx; lastAnchorB[1] = by; lastAnchorBodySpace = bodySpace }
        val p = when {
            cal != null && cal.bodySpace == bodySpace -> cal.apply(bx, by)
            bodySpace -> Offset(0.5f + (bx - 0.35f) / 2.6f, (by + 1.25f) / 1.9f)
            else -> Offset((ax / aspect - 0.15f) / 0.7f, (ay - 0.1f) / 0.7f)
        }
        return Offset(p.x.coerceIn(-0.05f, 1.05f), p.y.coerceIn(-0.05f, 1.05f))
    }

    fun applyCalibration(c: Calibration) { calibration = c; saveSetting("calibration", c.serialize()) }
    fun clearCalibration() { calibration = null; saveSetting("calibration", "") }
}

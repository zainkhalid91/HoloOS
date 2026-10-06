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

        sealed interface FitResult
        class Fitted(val calibration: Calibration, val rmsError: Float) : FitResult
        class Rejected(val reason: String) : FitResult

        /**
         * Fits each screen axis independently: sx = a·bx + c, sy = e·by + f.
         * A camera facing the user needs no rotation or shear, and allowing them lets a
         * few noisy points produce a twisted, mirrored map, so the model is deliberately
         * this small. The result is validated before it can be used.
         */
        fun fit(bodySpace: Boolean, samples: List<FloatArray>): FitResult {
            if (samples.size < 3) return Rejected("Not enough targets")
            fun line(xs: List<Float>, ys: List<Float>): Pair<Float, Float>? {
                val mx = xs.average().toFloat(); val my = ys.average().toFloat()
                var num = 0f; var den = 0f
                for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
                if (den < 1e-9f) return null
                val slope = num / den
                return slope to (my - slope * mx)
            }
            val (ax, cx) = line(samples.map { it[0] }, samples.map { it[2] }) ?: return Rejected("Your hand barely moved left to right")
            val (ey, fy) = line(samples.map { it[1] }, samples.map { it[3] }) ?: return Rejected("Your hand barely moved up and down")
            if (ax <= 0f) return Rejected("Left and right came out reversed")
            if (ey <= 0f) return Rejected("Up and down came out reversed")
            // Span of hand movement needed to cross the screen, in reference units.
            val spanX = 1f / ax; val spanY = 1f / ey
            val tooSmall = if (bodySpace) 0.35f else 0.08f
            val tooLarge = if (bodySpace) 6f else 1.4f
            if (spanX < tooSmall || spanY < tooSmall) return Rejected("Targets were too close together: reach further toward each one")
            if (spanX > tooLarge || spanY > tooLarge) return Rejected("Movement was too large or tracking jumped")
            val cal = Calibration(bodySpace, floatArrayOf(ax, 0f, cx, 0f, ey, fy))
            var err = 0f
            for (smp in samples) { val q = cal.apply(smp[0], smp[1]); err += (q.x - smp[2]) * (q.x - smp[2]) + (q.y - smp[3]) * (q.y - smp[3]) }
            val rms = kotlin.math.sqrt(err / samples.size)
            if (rms > 0.12f) return Rejected("Targets didn't line up (error ${(rms * 100).toInt()}% of screen)")
            return Fitted(cal, rms)
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

    // Diagnostics (logged every frame and shown in the debug view).
    var pinchRatio = 0f
    var anchorX = 0f; var anchorY = 0f
    var refX = 0f; var refY = 0f
    var bodySpace = false
    /** Mapped screen position before smoothing, px. */
    var rawCursorX = 0f; var rawCursorY = 0f
    var score = 0f
    var extended = ""
}

class GestureEngine(private val sink: GestureSink) {
    /** Calibration is per camera: the phone and the webcam see you from different places. */
    var sourceKey = "WEBCAM"
        private set
    var calibration: Calibration? = Calibration.parse(loadSetting("calibration-WEBCAM"))
        private set

    fun setSource(key: String) {
        if (key == sourceKey) return
        sourceKey = key
        calibration = Calibration.parse(loadSetting("calibration-$key"))
    }

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
    /** Once the shoulders have been seen, mapping stays body-relative for the session. */
    private var hasBodyReference = false
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

    private var frameNowMs = 0L
    private val lastRef = FloatArray(2); private var lastRefBody = false
    /** Increments once per processed camera frame (the logger writes one row per frame). */
    var processed = 0; private set
    val currentPose: HandPose get() = pose
    val isPinching: Boolean get() = pinching
    val isDragging: Boolean get() = dragging
    var lastFrameId = -1; private set
    var lastFrameHands = 0; private set

    fun process(f: TrackFrame?, nowMs: Long, screenW: Float, screenH: Float) {
        if (f == null || screenW <= 0f) return
        frameNowMs = nowMs
        if (nowMs - f.receivedMs > 400 || f.hands.isEmpty()) {
            if (lastSeenHandsMs != 0L && nowMs - lastSeenHandsMs > 250) handsLost()
            if (f.id != lastFrame) { lastFrame = f.id; updateBody(f, nowMs) }
            return
        }
        if (f.id == lastFrame) return
        lastFrame = f.id
        lastFrameId = f.id; lastFrameHands = f.hands.size
        processed++
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
            // The reference moves slowly and ignores implausible jumps (a misdetection),
            // so the cursor never jumps because of the body model.
            val plausible = shoulderW == 0f || abs(w - shoulderW) / shoulderW < 0.3f
            if (w > 0.03f && plausible) {
                val k = if (shoulderW == 0f) 1f else 0.08f
                shoulderMidX += (mx - shoulderMidX) * k; shoulderMidY += (my - shoulderMidY) * k; shoulderW += (w - shoulderW) * k
                shoulderSeenMs = nowMs
            }
            shoulderRaw = floatArrayOf(s[0] * a, s[1], s[2] * a, s[3])
        }
        bodyVisible = nowMs - shoulderSeenMs < 1000 && shoulderW > 0f
        if (!bodyVisible) shoulderRaw = null
        hasBodyReference = shoulderW > 0f
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
        out.pinchRatio = pinchRatio
        out.score = h.score
        out.extended = (if (thumbOut) "T" else "-") + (if (index) "I" else "-") + (if (middle) "M" else "-") + (if (ring) "R" else "-") + (if (pinky) "P" else "-")

        // Stable anchor: between the index knuckle and the thumb-index pinch point, so
        // closing a pinch barely moves the cursor.
        val ax = (px(5) + (px(4) + px(8)) / 2) / 2
        val ay = (py(5) + (py(4) + py(8)) / 2) / 2
        val s = mapToScreen(ax, ay, out === hands[0])
        out.cursorX = s.x * sw; out.cursorY = s.y * sh
        out.rawCursorX = out.cursorX; out.rawCursorY = out.cursorY
        out.anchorX = ax; out.anchorY = ay
        out.refX = lastRef[0]; out.refY = lastRef[1]; out.bodySpace = lastRefBody

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
        val bx: Float; val by: Float; val bodySpace: Boolean
        if (hasBodyReference && (cal == null || cal.bodySpace)) {
            bx = (ax - shoulderMidX) / shoulderW; by = (ay - shoulderMidY) / shoulderW; bodySpace = true
        } else {
            bx = ax; by = ay; bodySpace = false
        }
        lastRef[0] = bx; lastRef[1] = by; lastRefBody = bodySpace
        if (primary) { lastAnchorB[0] = bx; lastAnchorB[1] = by; lastAnchorBodySpace = bodySpace; remember(bx, by, frameNowMs) }
        val p = when {
            cal != null && cal.bodySpace == bodySpace -> cal.apply(bx, by)
            bodySpace -> Offset(0.5f + (bx - 0.35f) / 2.6f, (by + 1.25f) / 1.9f)
            else -> Offset((ax / aspect - 0.15f) / 0.7f, (ay - 0.1f) / 0.7f)
        }
        return Offset(p.x.coerceIn(-0.05f, 1.05f), p.y.coerceIn(-0.05f, 1.05f))
    }

    fun applyCalibration(c: Calibration) { calibration = c; saveSetting("calibration-$sourceKey", c.serialize()) }
    fun clearCalibration() { calibration = null; saveSetting("calibration-$sourceKey", "") }

    // ---- anchor history, so calibration records where the hand was *before* the pinch
    private val histX = FloatArray(12); private val histY = FloatArray(12); private val histT = LongArray(12)
    private var histN = 0

    private fun remember(bx: Float, by: Float, t: Long) {
        val i = histN % histX.size
        histX[i] = bx; histY[i] = by; histT[i] = t; histN++
    }

    /**
     * Anchor from ~[agoMs] before now plus how much it wandered in the ~200 ms before
     * that (in reference units). Null if there isn't enough history.
     */
    fun anchorBefore(nowMs: Long, agoMs: Long = 120): FloatArray? {
        val n = minOf(histN, histX.size)
        if (n < 4) return null
        var best = -1
        for (k in 0 until n) {
            val i = (histN - 1 - k).mod(histX.size)
            if (nowMs - histT[i] >= agoMs) { best = i; break }
        }
        if (best < 0) return null
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in 0 until n) {
            val i = (histN - 1 - k).mod(histX.size)
            val age = nowMs - histT[i]
            if (age < agoMs || age > agoMs + 200) continue
            minX = minOf(minX, histX[i]); maxX = maxOf(maxX, histX[i]); minY = minOf(minY, histY[i]); maxY = maxOf(maxY, histY[i])
        }
        val wander = if (minX == Float.MAX_VALUE) 0f else maxOf(maxX - minX, maxY - minY)
        return floatArrayOf(histX[best], histY[best], wander)
    }
}

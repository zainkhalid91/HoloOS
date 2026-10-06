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
    PinchDrag("PINCH + MOVE", "Drag / spin / scroll"),
    Grab("CLOSE HAND", "Grab · open to drop"),
    TwoHandZoom("TWO-HAND PINCH", "Zoom in / out"),
    SwipeBack("PALM SWIPE ←", "Back"),
    PeaceHome("PEACE ✌ · HOLD", "Home"),
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
    /** Closed hand: grab what's under the cursor (true) / open hand: drop it (false). */
    fun onGrip(closed: Boolean)
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

/**
 * How much of the camera frame maps onto the whole screen. Larger = less sensitive
 * (more hand movement per screen width). Tuned down after "too fast to control".
 */
private const val CAMERA_REGION_W = 0.62f
private const val CAMERA_REGION_H = 0.58f

class GestureEngine(private val sink: GestureSink) {
    private fun palmX(h: TrackedHand) = (h.x(0) + h.x(5) + h.x(9) + h.x(13) + h.x(17)) / 5f * aspect
    private fun palmY(h: TrackedHand) = (h.y(0) + h.y(5) + h.y(9) + h.y(13) + h.y(17)) / 5f

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
    // Session log: median jitter of a still hand was ~24 px/frame, so filter harder at rest.
    private val fx = OneEuro(0.6f, 0.004f); private val fy = OneEuro(0.6f, 0.004f)
    /** Output only moves once the filtered point leaves a small dead zone: kills residual shimmer. */
    private var outX = 0f; private var outY = 0f
    private val deadZonePx = 4f
    private var lastPrimaryAx = -1f; private var lastPrimaryAy = -1f
    private var bodyRatio = 0f; private var useBody = false
    private var outlierFrames = 0
    private var gripping = false
    private var twoPinchSince = 0L
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
        // Ignore low-confidence detections (spurious "hands" made the cursor jump).
        val good = f.hands.filter { it.score >= 0.6f }
        if (nowMs - f.receivedMs > 400 || good.isEmpty()) {
            if (lastSeenHandsMs != 0L && nowMs - lastSeenHandsMs > 250) handsLost()
            if (f.id != lastFrame) { lastFrame = f.id; lastFrameId = f.id; lastFrameHands = 0; updateBody(f, nowMs) }
            return
        }
        if (f.id == lastFrame) return
        lastFrame = f.id
        lastFrameId = f.id; lastFrameHands = good.size
        processed++
        lastSeenHandsMs = nowMs
        aspect = f.aspect
        cameraFps = f.cameraFps
        pipeLatencyMs = pipeLatencyMs * 0.9f + (f.receivedMs - f.captureMs).toFloat().coerceIn(0f, 500f) * 0.1f
        updateBody(f, nowMs)

        // Primary hand = the one closest to where the primary hand was last frame.
        // MediaPipe's left/right labels flip between frames, which teleported the cursor.
        val ordered = if (lastPrimaryAx >= 0f && good.size == 2) {
            good.sortedBy { h -> hypot(palmX(h) - lastPrimaryAx, palmY(h) - lastPrimaryAy) }
        } else good.sortedByDescending { it.isRight }.take(2)
        for (i in 0..1) hands[i].visible = i < ordered.size
        ordered.forEachIndexed { i, h -> analyse(h, hands[i], screenW, screenH) }

        val primary = hands[0]
        lastPrimaryAx = primary.anchorX; lastPrimaryAy = primary.anchorY
        val t = nowMs
        val dt = if (lastCursorT == 0L) 0.016f else ((t - lastCursorT) / 1000f).coerceIn(0.004f, 0.1f)

        // A single-frame jump across a third of the screen is a tracking glitch: hold
        // position for up to two frames before believing it.
        if (lastCursorT != 0L && hypot(primary.rawCursorX - outX, primary.rawCursorY - outY) > screenW * 0.33f && outlierFrames < 2) {
            outlierFrames++
            primary.cursorX = outX; primary.cursorY = outY
            return
        }
        outlierFrames = 0

        var cx = fx.filter(primary.cursorX, dt); var cy = fy.filter(primary.cursorY, dt)
        if (lastCursorT == 0L || hypot(cx - outX, cy - outY) > deadZonePx) {
            // Move by the excess beyond the dead zone so motion stays continuous.
            val d = hypot(cx - outX, cy - outY)
            if (lastCursorT == 0L || d <= 0f) { outX = cx; outY = cy } else {
                val k = (d - deadZonePx) / d
                outX += (cx - outX) * k; outY += (cy - outY) * k
            }
        }
        cx = outX; cy = outY
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

        if (twoPinch && twoPinchSince == 0L) twoPinchSince = t
        if (!twoPinch) twoPinchSince = 0L
        if (twoPinch && t - twoPinchSince >= 200) {
            val d = hypot(hands[0].cursorX - hands[1].cursorX, hands[0].cursorY - hands[1].cursorY)
            if (twoHandDist > 0f) {
                val mid = Offset((hands[0].cursorX + hands[1].cursorX) / 2, (hands[0].cursorY + hands[1].cursorY) / 2)
                val amount = (d / twoHandDist - 1f) * 3f
                if (abs(amount) > 0.025f) { sink.onZoom(amount, mid); twoHandDist = d }
            } else twoHandDist = d
            dragging = false; pinching = true
            now += Gesture.TwoHandZoom
        } else if (!twoPinch) {
            twoHandDist = 0f
            when {
                pose == HandPose.Pinch && !pinching -> {
                    pinching = true; dragging = false
                    pinchStartT = t; pinchStartX = cx; pinchStartY = cy
                    sink.onPress(true)
                    now += Gesture.Pinch
                }
                pose == HandPose.Pinch -> {
                    if (!dragging && t - pinchStartT > 180 && hypot(cx - pinchStartX, cy - pinchStartY) > 70f) dragging = true
                    if (dragging) { sink.onDrag(moveX, moveY); now += Gesture.PinchDrag } else now += Gesture.Pinch
                }
                pinching -> {
                    pinching = false
                    sink.onPress(false)
                    if (!dragging && t - pinchStartT < 700) sink.onTap(pinchStartX, pinchStartY)
                    dragging = false
                }
            }
        }

        // Closed hand grabs: the session log showed fists used as the natural grab gesture.
        val grip = pose == HandPose.Fist && !pinching
        when {
            grip && !gripping -> { gripping = true; sink.onGrip(true) }
            grip -> { if (moveX != 0f || moveY != 0f) sink.onDrag(moveX, moveY) }
            gripping -> { gripping = false; sink.onGrip(false) }
        }
        if (gripping) now += Gesture.Grab

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
        val holdTarget = when (pose) { HandPose.Victory -> HandPose.Victory; HandPose.ThumbsUp -> HandPose.ThumbsUp; else -> HandPose.None }
        if (holdTarget != holdPose) { holdPose = holdTarget; holdStart = t; holdFired = false }
        val needed = if (holdPose == HandPose.Victory) 1200f else 700f
        holdProgress = if (holdPose == HandPose.None || holdFired) 0f else ((t - holdStart) / needed).coerceIn(0f, 1f)
        if (holdPose == HandPose.Victory) now += Gesture.PeaceHome
        if (holdPose == HandPose.ThumbsUp) now += Gesture.ThumbsUp
        if (!holdFired && holdProgress >= 1f) {
            holdFired = true; holdProgress = 0f
            if (holdPose == HandPose.Victory) sink.onHome() else sink.onAccept()
        }
        if (now != active) active = now
    }

    private fun handsLost() {
        lastSeenHandsMs = 0L
        hands.forEach { it.visible = false }
        if (pinching) { pinching = false; sink.onPress(false) }
        if (gripping) { gripping = false; sink.onGrip(false) }
        twoPinchSince = 0L
        dragging = false; twoHandDist = 0f; pose = HandPose.None; holdPose = HandPose.None; holdProgress = 0f
        fx.reset(); fy.reset(); lastCursorT = 0L; lastPrimaryAx = -1f; outlierFrames = 0
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
        // With the phone close up, shoulders were seen in only 3% of frames, so the map
        // was anchored to a stale estimate. Use body space only while it is reliable.
        bodyRatio = bodyRatio * 0.97f + (if (s != null) 0.03f else 0f)
        if (!useBody && bodyRatio > 0.6f) useBody = true
        if (useBody && bodyRatio < 0.3f) useBody = false
        hasBodyReference = useBody && shoulderW > 0f
    }

    private fun analyse(h: TrackedHand, out: HandView, sw: Float, sh: Float) {
        val a = aspect
        fun px(i: Int) = h.x(i) * a
        fun py(i: Int) = h.y(i)
        fun d(i: Int, j: Int) = hypot(px(i) - px(j), py(i) - py(j))
        // 3D (MediaPipe z is on the same scale as x), so fingers pointing at the camera
        // aren't mistaken for curled ones.
        fun pz(i: Int) = h.z(i) * a
        fun d3(i: Int, j: Int): Float {
            val dx = px(i) - px(j); val dy = py(i) - py(j); val dz = pz(i) - pz(j)
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        val palm = d3(0, 9).coerceAtLeast(1e-4f)

        /** A finger is extended when its two segments are nearly in line (angle test). */
        fun straight(a0: Int, a1: Int, b0: Int, b1: Int, minCos: Float): Boolean {
            val ux = px(a1) - px(a0); val uy = py(a1) - py(a0); val uz = pz(a1) - pz(a0)
            val vx = px(b1) - px(b0); val vy = py(b1) - py(b0); val vz = pz(b1) - pz(b0)
            val n = kotlin.math.sqrt((ux * ux + uy * uy + uz * uz) * (vx * vx + vy * vy + vz * vz))
            return n > 1e-9f && (ux * vx + uy * vy + uz * vz) / n > minCos
        }
        fun ext(mcp: Int, pip: Int, tip: Int) = straight(mcp, pip, pip, tip, 0.55f) && d3(tip, 0) > d3(pip, 0)
        val index = ext(5, 6, 8); val middle = ext(9, 10, 12); val ring = ext(13, 14, 16); val pinky = ext(17, 18, 20)
        val thumbOut = straight(2, 3, 3, 4, 0.6f) && d3(4, 5) > palm * 0.55f
        val pinchRatio = d3(4, 8) / palm
        // In a fist the thumb lies on the curled index finger, which looked like a pinch.
        // A real pinch keeps the index fingertip away from the palm.
        val indexReach = d3(8, 0) / palm
        val wasPinch = out.pose == HandPose.Pinch
        val pose = when {
            pinchRatio < (if (wasPinch) 0.45f else 0.30f) && indexReach > 1.0f -> HandPose.Pinch
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

        // Stable anchor: the palm centre. The old anchor used the thumb and index tips,
        // which move when you pinch, so every pinch dragged the cursor (~300 px median).
        val ax = palmX(h); val ay = palmY(h)
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
            else -> Offset((ax / aspect - 0.5f) / CAMERA_REGION_W + 0.5f, (ay - 0.45f) / CAMERA_REGION_H + 0.5f)
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

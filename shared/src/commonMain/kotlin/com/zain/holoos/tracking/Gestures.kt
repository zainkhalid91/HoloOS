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
    IndexTap("POINT + DIP INDEX", "Tap / select"),
    Pinch("PINCH", "Select / open"),
    PinchDrag("PINCH + MOVE", "Drag / spin / scroll"),
    Grab("CLOSE HAND", "Grab & move"),
    Stop("FULL PALM · HOLD STILL", "Stop"),
    TwoHandZoom("TWO HANDS APART / TOGETHER", "Zoom in / out"),
    Swipe("OPEN HAND SWIPE", "← back · → next"),
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
    /** Fast open-hand motion, px/s. Only sent while a screen claims swipes (the globe). */
    fun onFling(vx: Float, vy: Float)
    /** The other hand closed into a fist: stop whatever is moving. */
    fun onHold()
    /** Two open hands moving apart (+) / together (−). Screen-only zoom, never navigates. */
    fun onSpread(amount: Float, at: Offset)
    /** A movement recognised as the hand travelling back after a stroke (ignored). */
    fun onReturnIgnored(kind: String)
    /** Human-readable decision from the gesture translator. */
    fun onInterpretation(text: String) {}
    fun onZoom(amount: Float, at: Offset)
    fun onBack()
    /** One deliberate open-hand swipe (unit direction, peak px/s), once per stroke. */
    fun onSwipe(dirX: Float, dirY: Float, speed: Float) {}
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
    /** Angle (degrees) between the index finger (knuckle→tip) and the palm (wrist→middle knuckle). */
    var indexBend = 0f
    var anchorX = 0f; var anchorY = 0f
    var refX = 0f; var refY = 0f
    var bodySpace = false
    /** Mapped screen position before smoothing, px. */
    var rawCursorX = 0f; var rawCursorY = 0f
    var score = 0f
    var extended = ""
    /** Part of the hand is at the camera frame's border: landmarks there jitter wildly. */
    var atEdge = false
    /** Unit vector out of the back of the hand (screen x right, y down, z away from camera). */
    var backX = 0f; var backY = 0f; var backZ = 0f
    /** Mapped position before the screen-edge clamp: velocities use this. */
    var freeX = 0f; var freeY = 0f
}

/**
 * How much of the camera frame maps onto the whole screen. Larger = less sensitive
 * (more hand movement per screen width). Tuned down after "too fast to control".
 */
private const val CAMERA_REGION_W = 0.62f
/** A new hand must move this far (palm, frame-height units) before it controls anything. */
private const val PHANTOM_PROVE = 0.05f
/** A hand frozen this long (ms) must move again: real hands always drift a little. */
private const val PHANTOM_FREEZE_MS = 3000L
/** Index finger dip (degrees beyond its resting angle) that starts an index tap. */
private const val TAP_DIP_DEG = 20f
/** A full palm held below this speed (px/s) for [PALM_STOP_MS] means STOP. */
private const val PALM_STILL_SPEED = 300f
private const val PALM_STOP_MS = 300L
/** Landmarks within this fraction of the camera frame's border are unreliable. */
private const val EDGE = 0.04f
/** Peak open-hand speed (screen px/s) for a straight stroke to count as a swipe. */
private const val SWIPE_SPEED = 1400f
/** A stroke starts above this speed and ends after two frames below [STROKE_END]. */
private const val STROKE_START = 900f
private const val STROKE_END = 450f
private const val CAMERA_REGION_H = 0.58f

class GestureEngine(private val sink: GestureSink) {
    // ---------------------------------------------------------------- phantom hands
    /**
     * The log showed a "hand" at a fixed spot in the room (bottom right of the image) for
     * seconds at a time: its palm moved < 0.004 per second (a real hand held as still as
     * possible moved more) and its pose flipped Open / Pinch / Victory. So a newly detected
     * hand only counts once it has actually moved; a hand that then stays frozen for
     * [PHANTOM_FREEZE_MS] has to move again before it counts.
     */
    var phantomFilter = true
    private class HandTrack(var x: Float, var y: Float, var ox: Float, var oy: Float, var seen: Long) {
        var proven = false; var refX = x; var refY = y; var stillSince = seen
    }
    private val tracks = ArrayList<HandTrack>()
    private var phantomFrame = -1; private var phantomGood: List<TrackedHand> = emptyList()
    /** Hands ignored as phantoms in the latest frame (for the log). */
    var phantoms = 0; private set

    private fun filterPhantoms(hands: List<TrackedHand>, now: Long): List<TrackedHand> {
        tracks.removeAll { now - it.seen > 1500 }
        if (!phantomFilter) { phantoms = 0; return hands }
        val used = HashSet<HandTrack>()
        val out = ArrayList<TrackedHand>(2)
        for (h in hands) {
            val x = palmX(h); val y = palmY(h)
            var tr = tracks.filter { it !in used }.minByOrNull { hypot(it.x - x, it.y - y) }
            if (tr == null || hypot(tr.x - x, tr.y - y) > 0.25f) { tr = HandTrack(x, y, x, y, now); tracks += tr }
            used += tr
            tr.x = x; tr.y = y; tr.seen = now
            if (hypot(x - tr.refX, y - tr.refY) > 0.012f) { tr.refX = x; tr.refY = y; tr.stillSince = now }
            if (!tr.proven && hypot(x - tr.ox, y - tr.oy) > PHANTOM_PROVE) tr.proven = true
            if (tr.proven && now - tr.stillSince > PHANTOM_FREEZE_MS) { tr.proven = false; tr.ox = x; tr.oy = y }
            if (tr.proven) out += h
        }
        phantoms = hands.size - out.size
        return out
    }

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
    private var fistFrames = 0

    /** Set each frame by the app: true while the current screen consumes swipes (globe). */
    var flingClaimed = false
    private val prevHX = FloatArray(2); private val prevHY = FloatArray(2)
    private val handVx = FloatArray(2); private val handVy = FloatArray(2)
    private val velValid = BooleanArray(2)
    private var lastFlingT = 0L
    private var openDist = 0f
    private var openDistSmooth = 0f
    private var spreadSign = 0
    // Swipe bursts: one fling per swipe, upgraded only if the swipe gets faster.
    private var fastFrames = 0
    private var burstPeak = 0f
    // Return-stroke filter ("clutch"): after a stroke, the hand travels back to get ready
    // for the next one. Opposite-direction motion shortly after a stroke is that reset.
    private var burstDirX = 0f; private var burstDirY = 0f; private var burstIgnored = false
    private var lastStrokeDirX = 0f; private var lastStrokeDirY = 0f
    private var lastStrokePeak = 0f; private var lastStrokeEnd = 0L
    private var spreadEpisode = 0          // sign of the zoom stroke in progress (0 = none)
    private var spreadEpisodeIgnored = false
    private var spreadQuietFrames = 0
    private var lastSpreadSign = 0; private var lastSpreadEnd = 0L
    private var spreadDecided = false; private var spreadFrames = 0; private var spreadPeak = 0f
    private var lastSpreadPeak = 0f; private var spreadIgnoredRun = 0; private var spreadDecisionPeak = 0f
    private var lastStrokeEndT = 0L
    /** Open palm held still: ms so far, and whether this hold already fired. */
    private var palmStillMs = 0L; private var palmFired = false
    private var twoPinchSince = 0L
    private var lastCursorT = 0L
    private var lastCx = 0f; private var lastCy = 0f
    private var vx = 0f; private var vy = 0f

    private var pinching = false
    private var pinchStartT = 0L; private var pinchStartX = 0f; private var pinchStartY = 0f
    private var pendingTapT = 0L; private var pendingTapX = 0f; private var pendingTapY = 0f
    private var lastFistMs = 0L
    private var dragging = false
    private var twoHandDist = 0f
    private var rawPose = HandPose.None; private var rawCount = 0; private var pose = HandPose.None
    private var holdPose = HandPose.None; private var holdStart = 0L; private var holdFired = false

    /** Last body-space (or camera-space) anchor of the primary hand, for calibration. */
    var lastAnchorB = FloatArray(2); private set
    var lastAnchorBodySpace = false; private set

    private var frameNowMs = 0L
    private val lastRef = FloatArray(2); private var lastRefBody = false
    private val lastFree = FloatArray(2)
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
        val good = if (f.id == phantomFrame) phantomGood else {
            phantomFrame = f.id
            filterPhantoms(f.hands.filter { it.score >= 0.6f }, nowMs).also { phantomGood = it }
        }
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
        // At the camera border the landmarks shake: widen the dead zone there.
        val dz = if (primary.atEdge) deadZonePx * 4f else deadZonePx
        if (lastCursorT == 0L || hypot(cx - outX, cy - outY) > dz) {
            // Move by the excess beyond the dead zone so motion stays continuous.
            val d = hypot(cx - outX, cy - outY)
            if (lastCursorT == 0L || d <= 0f) { outX = cx; outY = cy } else {
                val k = (d - dz) / d
                outX += (cx - outX) * k; outY += (cy - outY) * k
            }
        }
        cx = outX; cy = outY
        vx = vx * 0.6f + (cx - lastCx) / dt * 0.4f; vy = vy * 0.6f + (cy - lastCy) / dt * 0.4f
        val moveX = cx - lastCx; val moveY = cy - lastCy
        lastCx = cx; lastCy = cy; lastCursorT = t
        primary.cursorX = cx; primary.cursorY = cy

        // Per-hand velocity in screen px/s (from the unsmoothed mapping, lightly averaged).
        for (i in 0..1) {
            val hv = hands[i]
            if (!hv.visible) { velValid[i] = false; continue }
            // Unclamped: at the screen edge the clamped cursor stops, which cut swipes short.
            if (velValid[i]) {
                handVx[i] = handVx[i] * 0.5f + (hv.freeX - prevHX[i]) / dt * 0.5f
                handVy[i] = handVy[i] * 0.5f + (hv.freeY - prevHY[i]) / dt * 0.5f
            } else { handVx[i] = 0f; handVy[i] = 0f }
            prevHX[i] = hv.freeX; prevHY[i] = hv.freeY; velValid[i] = true
        }

        // Debounced pose: 3 consistent frames; pinch needs 2. The log showed false clicks from
        //  - the hand at the camera border (finger landmarks shake): the pose is frozen there;
        //  - closing into / opening out of a fist, which passes through a pinch shape;
        //  - fast swipes, where motion blur briefly brings thumb and index together.
        val p = primary.pose
        if (p == HandPose.Fist) lastFistMs = t
        if (p == rawPose) rawCount++ else { rawPose = p; rawCount = 1 }
        val fast = hypot(handVx[0], handVy[0]) > STROKE_START
        val pinchOk = !primary.atEdge && !fast && t - lastFistMs > 300
        when {
            primary.atEdge && pose != HandPose.None -> {} // keep the last trusted pose
            p == HandPose.Pinch -> if (pose == HandPose.Pinch || (rawCount >= 2 && pinchOk)) pose = p
            rawCount >= 3 -> pose = p
        }
        if (pendingTapT != 0L) {
            // A pinch that opens into a fist or thumbs-up was a hand-shape change, not a tap.
            if (p == HandPose.Fist || p == HandPose.ThumbsUp || primary.atEdge) pendingTapT = 0L
            else if (t - pendingTapT >= 90) { pendingTapT = 0L; sink.onTap(pendingTapX, pendingTapY) }
        }

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
                    if (!dragging && t - pinchStartT < 700) { pendingTapT = t; pendingTapX = pinchStartX; pendingTapY = pinchStartY }
                    dragging = false
                }
            }
        }

        // Closed hand grabs: the session log showed fists used as the natural grab gesture.
        fistFrames = if (pose == HandPose.Fist) fistFrames + 1 else 0
        val grip = fistFrames >= 3 && !pinching
        when {
            grip && !gripping -> { gripping = true; sink.onGrip(true) }
            grip -> { if (moveX != 0f || moveY != 0f) sink.onDrag(moveX, moveY) }
            gripping -> { gripping = false; sink.onGrip(false) }
        }
        if (gripping) now += Gesture.Grab

        if (pose == HandPose.Point) now += Gesture.Point
        if (indexTap(t, primary, cx, cy)) now += Gesture.IndexTap
        val bothVisible = hands[0].visible && hands[1].visible
        fun openish(h: HandView) = h.pose != HandPose.Fist && h.pose != HandPose.Pinch

        // Two hands, palms open: apart = zoom in, together = zoom out (clap / spread).
        // Log showed single-frame jumps of +121% / −205% from hand jitter: the distance is
        // smoothed, the direction must hold for 2 frames, and each step is capped.
        var twoHandZooming = false
        if (bothVisible && openish(hands[0]) && openish(hands[1])) {
            val d = hypot(hands[0].rawCursorX - hands[1].rawCursorX, hands[0].rawCursorY - hands[1].rawCursorY)
            openDistSmooth = if (openDistSmooth == 0f) d else openDistSmooth + (d - openDistSmooth) * 0.45f
            if (openDist > 0f) {
                val rate = (openDistSmooth - openDist) / dt
                val sign = if (rate > 600f) 1 else if (rate < -600f) -1 else 0
                if (sign != 0 && sign == spreadSign) {
                    if (spreadEpisode != sign) {
                        if (spreadEpisode != 0) endSpread(t)
                        spreadEpisode = sign; spreadDecided = false; spreadFrames = 0; spreadPeak = 0f; spreadEpisodeIgnored = false
                    }
                    spreadQuietFrames = 0
                    spreadFrames++
                    spreadPeak = maxOf(spreadPeak, abs(rate))
                    if (!spreadDecided && spreadFrames >= 2) {
                        // Same rule as swipes: opposite to the last zoom stroke, soon after it,
                        // and slower → the hands are travelling back. At most two in a row.
                        spreadDecided = true
                        spreadDecisionPeak = spreadPeak
                        val opposite = lastSpreadSign == -sign && t - lastSpreadEnd < 800
                        spreadEpisodeIgnored = opposite && spreadPeak < lastSpreadPeak && spreadIgnoredRun < 2
                        if (spreadEpisodeIgnored) {
                            sink.onReturnIgnored(if (sign > 0) "spread-apart" else "spread-together")
                            interpret(t, "HANDS ${if (sign > 0) "APART" else "TOGETHER"} · IGNORED (SLOWER RETURN · MOVE FASTER TO REVERSE)")
                        } else interpret(t, "HANDS ${if (sign > 0) "APART → ZOOM IN" else "TOGETHER → ZOOM OUT"}")
                    }
                    if (spreadDecided && !spreadEpisodeIgnored) {
                        val mid = Offset((hands[0].rawCursorX + hands[1].rawCursorX) / 2, (hands[0].rawCursorY + hands[1].rawCursorY) / 2)
                        sink.onSpread(((openDistSmooth / openDist - 1f) * 1.2f).coerceIn(-0.15f, 0.15f), mid)
                        now += Gesture.TwoHandZoom
                    }
                    twoHandZooming = true
                } else if (spreadEpisode != 0 && ++spreadQuietFrames >= 2) endSpread(t)
                spreadSign = sign
            }
            openDist = openDistSmooth
        } else {
            if (spreadEpisode != 0) endSpread(t)
            openDist = 0f; openDistSmooth = 0f; spreadSign = 0
        }

        if (pose == HandPose.Open) now += Gesture.OpenPalm
        // Every screen reads swipes through the stroke translator (returns are ignored);
        // what a swipe does is up to the screen: spin, scroll, or back / next.
        translateStrokes(t, dt, twoHandZooming)
        if (t - lastFlingT < 400) now += Gesture.Swipe

        // STOP: a full open palm (all five fingers) held still, like a "stop" sign. Not
        // right after a stroke, so the hand settling after a swipe doesn't undo the spin.
        val palmUp = primary.visible && primary.pose == HandPose.Open && primary.extended.endsWith("IMRP") &&
            hypot(handVx[0], handVy[0]) < PALM_STILL_SPEED && !strokeActive
        if (palmUp && t - lastStrokeEndT > 600) {
            palmStillMs += (dt * 1000).toLong()
            if (!palmFired && palmStillMs >= PALM_STOP_MS) { palmFired = true; sink.onHold() }
        } else if (!palmUp) { palmStillMs = 0L; palmFired = false }
        if (palmUp && palmStillMs > 0L) now += Gesture.Stop

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
        twoPinchSince = 0L; openDist = 0f; openDistSmooth = 0f; spreadSign = 0; fastFrames = 0; burstPeak = 0f; strokeActive = false; velValid[0] = false; velValid[1] = false; palmStillMs = 0L; palmFired = false
        dragging = false; twoHandDist = 0f; pose = HandPose.None; pendingTapT = 0L; holdPose = HandPose.None; holdProgress = 0f
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
        run {
            val fx = px(8) - px(5); val fy = py(8) - py(5); val fz = pz(8) - pz(5)
            val qx = px(9) - px(0); val qy = py(9) - py(0); val qz = pz(9) - pz(0)
            val n = kotlin.math.sqrt((fx * fx + fy * fy + fz * fz) * (qx * qx + qy * qy + qz * qz)).coerceAtLeast(1e-9f)
            out.indexBend = kotlin.math.acos(((fx * qx + fy * qy + fz * qz) / n).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
        }
        // Back-of-hand normal: (index knuckle − wrist) × (pinky knuckle − wrist). In the
        // mirrored image this points out of the back of a right hand; flip for the left.
        run {
            val ux = px(5) - px(0); val uy = py(5) - py(0); val uz = pz(5) - pz(0)
            val vx = px(17) - px(0); val vy = py(17) - py(0); val vz = pz(17) - pz(0)
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            val sgn = if (h.isRight) 1f else -1f
            nx = nx / nl * sgn; ny = ny / nl * sgn; nz = nz / nl * sgn
            out.backX = nx; out.backY = ny; out.backZ = nz
        }
        out.score = h.score
        run {
            var edge = false
            for (i in 0..20) {
                val x = h.x(i); val y = h.y(i)
                if (x < EDGE || x > 1f - EDGE || y < EDGE || y > 1f - EDGE) { edge = true; break }
            }
            out.atEdge = edge
        }
        out.extended = (if (thumbOut) "T" else "-") + (if (index) "I" else "-") + (if (middle) "M" else "-") + (if (ring) "R" else "-") + (if (pinky) "P" else "-")

        // Stable anchor: the palm centre. The old anchor used the thumb and index tips,
        // which move when you pinch, so every pinch dragged the cursor (~300 px median).
        val ax = palmX(h); val ay = palmY(h)
        val s = mapToScreen(ax, ay, out === hands[0])
        out.cursorX = s.x * sw; out.cursorY = s.y * sh
        out.freeX = lastFree[0] * sw; out.freeY = lastFree[1] * sh
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
        lastFree[0] = p.x; lastFree[1] = p.y
        return Offset(p.x.coerceIn(-0.05f, 1.05f), p.y.coerceIn(-0.05f, 1.05f))
    }

    // ---------------------------------------------------------------- gesture translator
    /**
     * Reads hand motion as whole strokes instead of single fast frames. A stroke starts
     * when a hand speeds up and ends when it settles; while it runs we collect its
     * direction, straightness, peak speed and hand pose, then decide what it *means*:
     *
     *  - SWIPE   straight, open hand, fast enough → spin, at the stroke's own angle.
     *  - RETURN  the first opposite stroke right after a swipe → the hand getting ready
     *            again, ignored. Only one per swipe, so you can never get stuck.
     *  - MOVE    slow or curved → just repositioning, no action.
     *
     * Every decision is published in [interpretation] so the HUD can show what was read.
     */
    private var strokeActive = false
    private var strokeHand = -1
    private var strokeStart = 0L
    private var strokeDx = 0f; private var strokeDy = 0f; private var strokePath = 0f
    private var strokePeak = 0f; private var strokeFrames = 0; private var strokeOpen = 0
    private var strokeDecided = false; private var strokeKind = ""
    private var strokeSlowFrames = 0
    private var flungPeak = 0f
    private var strokeDecisionPeak = 0f
    private var strokeMinSincePeak = Float.MAX_VALUE
    // The previous meaningful stroke (swipe or ignored return) for return detection.
    private var prevX = 0f; private var prevY = 0f; private var prevPeak = 0f
    private var prevEnd = 0L; private var ignoredRun = 0
    private var flingDirX = 0f; private var flingDirY = 0f

    /** Latest translator decision, e.g. "SWIPE ↗ 2.4K PX/S → SPIN". */
    var interpretation by mutableStateOf(""); private set
    var interpretationAtMs = 0L; private set

    private fun interpret(t: Long, text: String) { interpretation = text; interpretationAtMs = t; sink.onInterpretation(text) }

    private fun arrow(x: Float, y: Float): String {
        val a = (kotlin.math.atan2(y, x) * 180f / PI.toFloat() + 360f + 22.5f) % 360f
        return arrayOf("→", "↘", "↓", "↙", "←", "↖", "↑", "↗")[(a / 45f).toInt().coerceIn(0, 7)]
    }

    private fun translateStrokes(t: Long, dt: Float, twoHandZooming: Boolean) {
        val bothMoving = hands[0].visible && hands[1].visible &&
            hypot(handVx[0], handVy[0]) > STROKE_START && hypot(handVx[1], handVy[1]) > STROKE_START
        val blocked = twoHandZooming || pinching || gripping || bothMoving
        // Which hand is moving? Keep following the stroke's hand while it lasts.
        var hand = -1; var speed = 0f
        if (strokeActive && strokeHand >= 0 && hands[strokeHand].visible) {
            hand = strokeHand; speed = hypot(handVx[hand], handVy[hand])
        } else {
            for (i in 0..1) {
                val hv = hands[i]
                if (!hv.visible || hv.pose == HandPose.Fist || hv.pose == HandPose.Pinch) continue
                val sp = hypot(handVx[i], handVy[i])
                if (sp > speed) { speed = sp; hand = i }
            }
        }

        if (!strokeActive) {
            if (!blocked && hand >= 0 && speed > STROKE_START) {
                strokeActive = true; strokeHand = hand; strokeStart = t
                strokeDx = 0f; strokeDy = 0f; strokePath = 0f; strokePeak = 0f
                strokeFrames = 0; strokeOpen = 0; strokeDecided = false; strokeKind = ""; strokeSlowFrames = 0; flungPeak = 0f
                strokeMinSincePeak = Float.MAX_VALUE
            } else return
        }
        if (hand < 0 || blocked) { endStroke(t); return }

        val vxh = handVx[hand]; val vyh = handVy[hand]
        // The hand reversed without pausing: that's the end of this stroke and the start of
        // a new one (otherwise a swipe and its return merge into one stroke).
        // Likewise when the hand slowed right down and then speeds up again (a short pause
        // between two strokes): two strokes, not one long one.
        val reaccelerated = strokeFrames >= 3 && strokeMinSincePeak < strokePeak * 0.5f && speed > maxOf(STROKE_START, strokeMinSincePeak * 2.5f)
        if ((strokeFrames >= 2 && vxh * strokeDx + vyh * strokeDy < 0f && speed > STROKE_START) || reaccelerated) {
            endStroke(t)
            strokeActive = true; strokeHand = hand; strokeStart = t
            strokeDx = 0f; strokeDy = 0f; strokePath = 0f; strokePeak = 0f
            strokeFrames = 0; strokeOpen = 0; strokeDecided = false; strokeKind = ""; strokeSlowFrames = 0; flungPeak = 0f
            strokeMinSincePeak = Float.MAX_VALUE
        }
        strokeDx += vxh * dt; strokeDy += vyh * dt; strokePath += speed * dt
        strokeFrames++
        if (hands[hand].pose != HandPose.Fist && hands[hand].pose != HandPose.Pinch) strokeOpen++
        if (speed > strokePeak) { strokePeak = speed; strokeMinSincePeak = speed } else strokeMinSincePeak = minOf(strokeMinSincePeak, speed)

        // Decide once there's enough of the stroke to judge (3 frames ≈ 100 ms), or as
        // soon as it starts to slow down.
        if (!strokeDecided && (strokeFrames >= 3 || (strokeFrames >= 2 && speed < strokePeak * 0.6f))) {
            strokeDecided = true
            val len = hypot(strokeDx, strokeDy).coerceAtLeast(1e-3f)
            val dirX = strokeDx / len; val dirY = strokeDy / len
            val straight = len / strokePath.coerceAtLeast(1e-3f)
            val open = strokeOpen.toFloat() / strokeFrames
            // Direction is the hand's actual motion. The back-of-hand vector was tried and the
            // log showed it is just how the hand is tilted (it leaned left whichever way the
            // user swiped), so it bent right/vertical swipes into ↙ and hid them as returns.
            // Return or reversal? Both look the same; the one you mean is the more forceful
            // one. An opposite stroke soon after the last one counts only if it's at least as
            // fast (compared at the same point in the stroke). Never more than two in a row
            // are ignored, so a direction change can't get stuck.
            strokeDecisionPeak = strokePeak
            val opposite = dirX * prevX + dirY * prevY < -0.5f
            val speedSaysReturn = opposite && t - prevEnd < 900 && strokePeak < prevPeak
            val isReturn = ignoredRun < 2 && speedSaysReturn
            // "Open" here means not a fist or pinch: an edge-on hand often reads as Point.
            val deliberate = strokePeak > SWIPE_SPEED && straight > 0.65f && open >= 0.5f
            val dir = arrow(dirX, dirY)
            val k = "${(strokePeak / 100).toInt() / 10f}K PX/S"
            when {
                isReturn -> {
                    strokeKind = "return"
                    sink.onReturnIgnored("swipe")
                    interpret(t, "RETURN $dir · IGNORED (SLOWER THAN ${arrow(prevX, prevY)} · SWIPE HARDER TO REVERSE)")
                }
                deliberate -> {
                    strokeKind = "swipe"
                    flingDirX = dirX; flingDirY = dirY
                    flingFromStroke(t, dirX, dirY)
                    sink.onSwipe(dirX, dirY, strokePeak)
                    interpret(t, "SWIPE $dir $k")
                }
                else -> {
                    strokeKind = "move"
                    val why = when {
                        straight <= 0.65f -> "CURVED"
                        open < 0.5f -> "HAND CLOSED"
                        else -> "TOO SLOW"
                    }
                    interpret(t, "MOVE $dir · NO ACTION ($why)")
                }
            }
        } else if (strokeDecided && strokeKind == "swipe" && strokePeak > flungPeak * 1.15f) {
            // The swipe kept accelerating: upgrade the spin along the decided direction.
            flingFromStroke(t, flingDirX, flingDirY)
        }

        strokeSlowFrames = if (speed < maxOf(STROKE_END, strokePeak * 0.25f)) strokeSlowFrames + 1 else 0
        if (strokeSlowFrames >= 2) endStroke(t)
    }

    /** Spin velocity follows the stroke's overall direction (any angle) and its peak speed. */
    private fun flingFromStroke(t: Long, dirX: Float, dirY: Float) {
        flungPeak = strokePeak
        lastFlingT = t
        sink.onFling(dirX * strokePeak, dirY * strokePeak)
    }

    private fun endStroke(t: Long) {
        if (!strokeActive) return
        if (strokeKind == "swipe") {
            val len = hypot(strokeDx, strokeDy).coerceAtLeast(1e-3f)
            prevX = strokeDx / len; prevY = strokeDy / len
            prevPeak = strokeDecisionPeak; prevEnd = t
            ignoredRun = 0
        } else if (strokeKind == "return") {
            // Keep comparing against the swipe itself: the log showed the next swipe in the
            // original direction being ignored as the "return of the return".
            prevEnd = t
            ignoredRun++
        }
        strokeActive = false; strokeHand = -1; lastStrokeEndT = t
    }

    // ---------------------------------------------------------------- index tap
    /**
     * Point, then dip the index finger and lift it back, like tapping a button: a click at
     * where you pointed. The log showed the finger stays straight during a tap (it bends at
     * the knuckle), so the pose never changed; we watch the finger's angle to the palm
     * against its own resting angle instead.
     */
    private var bendRest = -1f
    // Resting angle = the lowest bend over the last ~0.6 s of pointing. (An average chased
    // the finger as it went down, and the log's taps went down over 3-4 frames.)
    private val bendHist = FloatArray(18); private val bendHistT = LongArray(18); private var bendHistI = 0
    private var tapFistFrames = 0
    private var tapPhase = 0; private var tapDownT = 0L; private var tapCoolUntil = 0L
    private var tapX = 0f; private var tapY = 0f; private var tapPeak = 0f
    private val posHistX = FloatArray(4); private val posHistY = FloatArray(4); private var posHistI = 0

    private fun indexTap(t: Long, h: HandView, cx: Float, cy: Float): Boolean {
        // Where the cursor was ~3 frames ago: the dip itself nudges the hand.
        val oldX = posHistX[posHistI]; val oldY = posHistY[posHistI]
        posHistX[posHistI] = cx; posHistY[posHistI] = cy; posHistI = (posHistI + 1) % posHistX.size
        val b = h.indexBend
        val usable = h.visible && !h.atEdge && !pinching && !gripping && hypot(handVx[0], handVy[0]) < STROKE_START
        if (!usable) { tapPhase = 0; return false }
        when (tapPhase) {
            0 -> {
                if (h.pose != HandPose.Point) { bendRest = -1f; bendHistT.fill(0L); return false }
                var rest = Float.MAX_VALUE
                for (k in bendHist.indices) if (bendHistT[k] != 0L && t - bendHistT[k] <= 600) rest = minOf(rest, bendHist[k])
                bendHist[bendHistI] = b; bendHistT[bendHistI] = t; bendHistI = (bendHistI + 1) % bendHist.size
                if (rest == Float.MAX_VALUE) return false
                bendRest = rest
                if (b - rest > TAP_DIP_DEG && t >= tapCoolUntil) {
                    tapPhase = 1; tapDownT = t; tapPeak = b; tapFistFrames = 0
                    tapX = if (oldX != 0f) oldX else cx; tapY = if (oldY != 0f) oldY else cy
                    return true
                }
            }
            1 -> {
                tapPeak = maxOf(tapPeak, b)
                when {
                    // Held this long: the log's taps were held ~0.5 s; longer is curling into a fist.
                    t - tapDownT > 1000 -> tapPhase = 0
                    // Opening into a palm or pinching is a shape change, not a tap; a fist for
                    // more than a few frames is the hand closing.
                    h.pose != HandPose.Point && h.pose != HandPose.Fist -> tapPhase = 0
                    h.pose == HandPose.Fist && ++tapFistFrames > 4 -> tapPhase = 0
                    h.pose == HandPose.Point && b - bendRest < TAP_DIP_DEG * 0.45f && t - tapDownT >= 50 -> {
                        tapPhase = 0; tapCoolUntil = t + 250; bendHistT.fill(0L)
                        interpret(t, "INDEX TAP · DIP ${(tapPeak - bendRest).toInt()}°")
                        sink.onTap(tapX, tapY)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun endSpread(t: Long) {
        // One ignored return per zoom stroke: after it, the opposite direction is
        // deliberate (previously each ignore extended the block and zoom got stuck).
        if (spreadDecided && !spreadEpisodeIgnored) {
            lastSpreadSign = spreadEpisode; lastSpreadPeak = spreadDecisionPeak
            spreadIgnoredRun = 0
        } else if (spreadDecided) {
            // Keep comparing with the real zoom (log: the next zoom-in after an ignored
            // "together" was itself ignored as the return of the return).
            spreadIgnoredRun++
        }
        lastSpreadEnd = t
        spreadDecided = false
        spreadEpisode = 0; spreadEpisodeIgnored = false; spreadQuietFrames = 0
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

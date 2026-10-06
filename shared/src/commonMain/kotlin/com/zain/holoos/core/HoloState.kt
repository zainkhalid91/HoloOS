package com.zain.holoos.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.zain.holoos.apps.CalculatorEngine
import com.zain.holoos.fs.FsNode
import com.zain.holoos.input.HandState
import com.zain.holoos.input.IntentModel
import com.zain.holoos.input.MotionPredictor
import com.zain.holoos.input.TargetRegistry
import com.zain.holoos.audio.Mixer
import com.zain.holoos.audio.Sfx
import com.zain.holoos.audio.SoundBank
import com.zain.holoos.audio.startAudio
import com.zain.holoos.input.IntentScore
import com.zain.holoos.input.UsagePrior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

sealed interface Screen { val title: String }
data object HomeScreen : Screen { override val title = "Core" }
data class FilesScreen(val node: FsNode) : Screen { override val title = node.name }
data object CalculatorScreen : Screen { override val title = "Calculator" }
data object GlobeScreen : Screen { override val title = "Globe" }
data object HandsScreen : Screen { override val title = "Hands" }
data class CityScreen(val city: com.zain.holoos.geo.WorldCity) : Screen { override val title = city.name }

class NavEntry(val id: Int, val screen: Screen, val origin: Rect?)

class Transition(val parent: NavEntry, val child: NavEntry, val forward: Boolean)

/**
 * Semantic-zoom navigator. Every screen is a hologram that lives *inside* the element
 * it was opened from: pushing scales the parent up around that element while the
 * child grows out of it, popping reverses it. Nesting is unlimited, so folders inside
 * folders are holograms inside holograms.
 */
class Navigator(private val scope: CoroutineScope) {
    private var nextId = 1
    val stack = mutableStateListOf(NavEntry(0, HomeScreen, null))
    var transition by mutableStateOf<Transition?>(null)
        private set
    /** 0 = parent fully shown, 1 = child fully shown. */
    val dive = Animatable(1f)
    /** 0..1 accumulated "zoom out" gesture on the current screen. */
    var backZoom by mutableFloatStateOf(0f)

    val top: NavEntry get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    private val easing = CubicBezierEasing(0.6f, 0f, 0.2f, 1f)
    var onDiveIn: (() -> Unit)? = null
    var onDiveOut: (() -> Unit)? = null

    fun push(screen: Screen, origin: Rect) {
        if (transition != null) return
        val from = top
        val to = NavEntry(nextId++, screen, origin)
        stack.add(to)
        transition = Transition(from, to, true)
        onDiveIn?.invoke()
        scope.launch {
            dive.snapTo(0f)
            dive.animateTo(1f, tween(620, easing = easing))
            transition = null
        }
    }

    fun back() {
        if (transition != null || !canGoBack) return
        val child = top
        val parent = stack[stack.size - 2]
        transition = Transition(parent, child, false)
        backZoom = 0f
        onDiveOut?.invoke()
        scope.launch {
            dive.snapTo(1f)
            dive.animateTo(0f, tween(520, easing = easing))
            stack.removeAt(stack.lastIndex)
            transition = null
            dive.snapTo(1f)
        }
    }

    fun home() {
        if (transition != null) return
        while (stack.size > 2) stack.removeAt(stack.lastIndex)
        back()
    }
}

class Particles {
    private val max = 600
    val x = FloatArray(max); val y = FloatArray(max)
    val vx = FloatArray(max); val vy = FloatArray(max)
    val life = FloatArray(max); val amber = BooleanArray(max)
    private var cursor = 0

    fun burst(at: Offset, count: Int = 36, warm: Boolean = false) {
        repeat(count) {
            val a = Random.nextFloat() * 6.2832f
            val s = 60f + Random.nextFloat() * 360f
            val i = cursor; cursor = (cursor + 1) % max
            x[i] = at.x; y[i] = at.y; vx[i] = cos(a) * s; vy[i] = sin(a) * s
            life[i] = 0.6f + Random.nextFloat() * 0.5f; amber[i] = warm
        }
    }

    fun tick(dt: Float) {
        val drag = kotlin.math.exp(-dt * 3.2f)
        for (i in 0 until max) {
            if (life[i] <= 0f) continue
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
            vx[i] *= drag; vy[i] *= drag
            life[i] -= dt * 1.3f
        }
    }

    val capacity get() = max
}

/** Directory listings shared by previews, dives and speculative prefetch. */
class ListingCache(private val scope: CoroutineScope, private val source: com.zain.holoos.fs.FileSource) {
    private val done = HashMap<String, List<FsNode>>()
    private val pending = HashMap<String, kotlinx.coroutines.Deferred<List<FsNode>>>()
    private val prefetched = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /** Called when a speculative prefetch finishes (plays the pop-up chime). */
    var onPrefetched: (() -> Unit)? = null

    fun cached(node: FsNode): List<FsNode>? = done[node.path]

    suspend fun list(node: FsNode): List<FsNode> {
        done[node.path]?.let { return it }
        val job = pending.getOrPut(node.path) {
            scope.async(Dispatchers.Default) { runCatching { source.list(node) }.getOrDefault(emptyList()) }
        }
        return job.await().also { done[node.path] = it; pending.remove(node.path) }
    }

    /** Speculative: warm a folder and every sub-folder preview before the user arrives. */
    fun prefetch(node: FsNode) {
        if (prefetched.put(node.path, true) != null) return
        scope.launch {
            val kids = list(node)
            kids.filter { it.isDirectory }.take(48).forEach { list(it) }
            onPrefetched?.invoke()
        }
    }

    fun isPrefetched(node: FsNode) = prefetched.containsKey(node.path)
}

enum class EarthMode { Holo, Real }

private const val DWELL_SECONDS = 0.5f

private const val LOG_HEADER = "t_ms,frame,src,cam_fps,pipe_ms,hands,body,pose,fingers,pinch,score,anchor_x,anchor_y,ref_x,ref_y,space," +
    "raw_cx,raw_cy,cx,cy,pred_x,pred_y,pinching,dragging,gestures,hover,top,top_p,grabbed,screen,transition,ui_fps,back,screen_px,event,detail"

/** Everything the UI tree needs, provided once through [LocalHolo]. */
class HoloOsState(private val scope: CoroutineScope) {
    val hand = HandState()
    val predictor = MotionPredictor(hand)
    val targets = TargetRegistry()
    val navigator = Navigator(scope)
    val particles = Particles()
    val calculator = CalculatorEngine()
    val files = com.zain.holoos.fs.platformFileSource()
    val listings = ListingCache(scope, files)
    val mixer = Mixer()
    val prior = UsagePrior()
    val intentScore = IntentScore()

    /** Camera hand tracking (desktop: MediaPipe sidecar over UDP). Null when unavailable. */
    val tracker: com.zain.holoos.tracking.TrackerLink? =
        if (com.zain.holoos.core.trackingEnabled()) com.zain.holoos.tracking.startTracker() else null
    val gestures: com.zain.holoos.tracking.GestureEngine? = tracker?.let { com.zain.holoos.tracking.GestureEngine(HandSink()) }
    val calibration: com.zain.holoos.tracking.CalibrationSession? = gestures?.let {
        com.zain.holoos.tracking.CalibrationSession(it, onSample = { sfx(Sfx.Lock) }, onDone = { sfx(Sfx.Notify) }, onReject = { sfx(Sfx.Denied) })
    }
    var trackerStatus by mutableStateOf(com.zain.holoos.tracking.TrackerStatus.Off)
        private set

    /** Set by the platform entry point (desktop: exitApplication, Android: finish). */
    var onExit: (() -> Unit)? = null
    /** First exit request arms it; a second one within 2.5 s exits. Prevents stray pinches closing the app. */
    var exitArmed by mutableStateOf(false)
        private set

    fun requestExit(immediate: Boolean = false) {
        if (immediate || exitArmed) {
            if (log != null) event("exit", "")
            log?.close()
            sfx(Sfx.DiveOut)
            scope.launch { kotlinx.coroutines.delay(250); onExit?.invoke() }
            return
        }
        exitArmed = true
        sfx(Sfx.Notify, gain = 0.6f)
        scope.launch { kotlinx.coroutines.delay(2500); exitArmed = false }
    }

    /** Screens can take hand drags (globe spin) and free taps (city markers). */
    var screenDrag: ((dx: Float, dy: Float) -> Unit)? = null
    /** Screens that consume open-hand swipes (fling, px/s) and "stop" fists. */
    var screenFling: ((vx: Float, vy: Float) -> Unit)? = null
    /** Reverse the vertical spin direction for swipes (V key). Remembered across runs. */
    var invertVertical by mutableStateOf(com.zain.holoos.tracking.loadSetting("invert-vertical")?.trim() != "false")
        private set
    fun toggleInvertVertical() {
        invertVertical = !invertVertical
        com.zain.holoos.tracking.saveSetting("invert-vertical", invertVertical.toString())
        event("invert-vertical", invertVertical.toString())
    }
    var screenHold: (() -> Unit)? = null
    var screenTap: ((at: Offset) -> Boolean)? = null

    /** Translates gesture events into the same paths mouse and touch use. */
    private inner class HandSink : com.zain.holoos.tracking.GestureSink {
        override fun onCursor(x: Float, y: Float, timeMs: Long) = predictor.onSample(x, y, this@HoloOsState.timeMs)
        override fun onPress(pressed: Boolean) {
            hand.pressed = pressed
            if (pressed) {
                pressTarget = targets.at(hand.x, hand.y)?.takeIf { it.grab != com.zain.holoos.input.Grab.None }
                event("pinch-down", pressTarget?.id ?: hoverId())
            } else {
                event("pinch-up", "")
                endGrab(); pressTarget = null
            }
        }
        override fun onFling(vx: Float, vy: Float) {
            event("fling", "${vx.toInt()},${vy.toInt()}")
            screenFling?.invoke(vx, vy)
        }
        override fun onHold() { event("hold", ""); screenHold?.invoke() }
        override fun onReturnIgnored(kind: String) { event("return-ignored", kind) }
        override fun onInterpretation(text: String) { event("read", text) }
        override fun onSpread(amount: Float, at: Offset) {
            event("spread", ((amount * 100).toInt() / 100f).toString())
            screenZoom?.invoke(amount, at)
        }
        override fun onGrip(closed: Boolean) {
            if (closed) {
                pressTarget = targets.at(hand.x, hand.y)?.takeIf { it.grab != com.zain.holoos.input.Grab.None }
                event("grip", pressTarget?.id ?: hoverId())
                pressTarget?.let { beginGrab(it) } ?: screenHold?.invoke()
            } else {
                event("release", grabbed?.id ?: "")
                endGrab(); pressTarget = null
            }
        }
        override fun onTap(x: Float, y: Float) { event("tap", targets.at(x, y)?.id ?: "-"); activateAt(Offset(x, y)) }
        override fun onDrag(dx: Float, dy: Float) {
            val p = pressTarget
            if (grabbed == null && p != null) beginGrab(p)
            if (grabbed != null) moveGrab(dx, dy) else screenDrag?.invoke(dx, dy)
        }
        override fun onZoom(amount: Float, at: Offset) { event("zoom", ((amount * 100).toInt() / 100f).toString()); zoomGesture(amount, at) }
        override fun onBack() { event("swipe-back", ""); navigator.back() }
        override fun onHome() { event("peace-home", ""); if (navigator.canGoBack) { sfx(Sfx.KeyOp); navigator.home() } }
        override fun onAccept() {
            val top = targets.top()?.takeIf { it.probability > 0.3f }
            event("thumbs-accept", top?.id ?: "none")
            top ?: return
            sfx(Sfx.Notify, gain = 0.6f)
            activateAt(top.bounds.center)
        }
        override fun onHandsLost() { event("hands-lost", ""); predictor.reset(); endGrab() }
    }

    /** A hand "tap": the target under the point gets the same treatment as a click. */
    fun activateAt(at: Offset) {
        if (navigator.transition != null) return
        val t = targets.at(at.x, at.y)
        when {
            t != null && (t.onTap != null || t.onDive != null) -> { if (t.onDive != null) burst(at, warm = true); t.activate() }
            screenTap?.invoke(at) == true -> {}
            else -> burst(at)
        }
    }

    // ---------------------------------------------------------------- grab
    private var pressTarget: com.zain.holoos.input.HoloTargetEntry? = null
    private var grabbed: com.zain.holoos.input.HoloTargetEntry? = null

    fun beginGrab(e: com.zain.holoos.input.HoloTargetEntry) {
        if (grabbed != null) return
        grabbed = e; e.grabbed = true; e.dwell = 0f
        sfx(Sfx.KeyOp, pitch = 0.8f)
        event("grab", e.id)
    }

    fun moveGrab(dx: Float, dy: Float) { grabbed?.let { it.offsetX += dx; it.offsetY += dy } }

    fun endGrab() {
        val g = grabbed ?: return
        g.grabbed = false; grabbed = null
        sfx(Sfx.Tap, pitch = 0.75f)
        event("drop", "${g.id} @ ${g.offsetX.toInt()},${g.offsetY.toInt()}")
    }

    // ---------------------------------------------------------------- diagnostics
    /** Live debug view (D key). */
    var debugOn by mutableStateOf(false)
    /** Most recent gesture events, newest first, for the debug view. */
    val recentEvents = androidx.compose.runtime.mutableStateListOf<String>()
    val log: com.zain.holoos.tracking.SessionLog? = if (tracker != null) com.zain.holoos.tracking.openSessionLog(LOG_HEADER) else null
    private var loggedFrame = -1

    private fun hoverId(): String = if (hand.visible) targets.at(hand.x, hand.y)?.id ?: "-" else "-"

    fun event(name: String, detail: String) {
        val line = "$name ${detail.take(40)}".trim()
        recentEvents.add(0, line)
        while (recentEvents.size > 8) recentEvents.removeAt(recentEvents.lastIndex)
        logRow(name, detail)
    }

    private fun csv(v: String) = if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "'") + "\"" else v
    private fun f1(v: Float) = ((v * 10).toInt() / 10f).toString()
    private fun f3(v: Float) = ((v * 1000).toInt() / 1000f).toString()

    private fun logRow(eventName: String = "", detail: String = "") {
        val l = log ?: return
        val g = gestures ?: return
        val tr = tracker
        val h = g.hands[0]
        val top = targets.top()
        val row = listOf(
            com.zain.holoos.geo.epochMillis().toString(), g.lastFrameId.toString(), tr?.source ?: "-",
            g.cameraFps.toInt().toString(), g.pipeLatencyMs.toInt().toString(), g.lastFrameHands.toString(),
            if (g.bodyVisible) "1" else "0", if (h.visible) h.pose.name else "-", if (h.visible) h.extended else "-",
            f3(h.pinchRatio), f3(h.score), f3(h.anchorX), f3(h.anchorY), f3(h.refX), f3(h.refY), if (h.bodySpace) "body" else "cam",
            f1(h.rawCursorX), f1(h.rawCursorY), f1(h.cursorX), f1(h.cursorY), f1(hand.predictedX), f1(hand.predictedY),
            if (g.isPinching) "1" else "0", if (g.isDragging) "1" else "0", g.active.joinToString("+") { it.name },
            hoverId(), top?.id ?: "-", f3(top?.probability ?: 0f), grabbed?.id ?: "-",
            navigator.top.screen.title, if (navigator.transition != null) "1" else "0", fps.toString(),
            "${f3(h.backX)} ${f3(h.backY)} ${f3(h.backZ)}",
            size.width.toInt().toString() + "x" + size.height.toInt().toString(), eventName, detail,
        )
        l.write(row.joinToString(",") { csv(it) })
    }

    private var dwellBlockedId: String? = null

    /** Hover still on something for ~1 s to select it (camera hands only). */
    private fun dwellTick(dt: Float) {
        val g = gestures ?: return
        if (!g.hands[0].visible || g.isPinching || grabbed != null || navigator.transition != null) return
        val t = targets.at(hand.x, hand.y)
        if (t == null || (t.onTap == null && t.onDive == null)) { dwellBlockedId = null; return }
        if (t.id == dwellBlockedId) return
        t.dwell += dt / DWELL_SECONDS
        if (t.dwell >= 1f) {
            t.dwell = 0f; dwellBlockedId = t.id
            event("dwell-select", t.id)
            activateAt(t.bounds.center)
        }
    }

    var size by mutableStateOf(Size.Zero)
    /** Monotonic animation clock in ms; read it only in draw blocks. */
    var timeMs by mutableLongStateOf(0L)
    var fps by mutableIntStateOf(0)
    var frameMs by mutableFloatStateOf(0f)
    var predictionOn by mutableStateOf(true)
    var muted by mutableStateOf(false)
    var earthMode by mutableStateOf(EarthMode.Real)
    var earth: com.zain.holoos.geo.EarthRenderer? = null
    var earthLoaded by mutableStateOf(false)

    /** A screen may claim scroll / pinch zoom (the globe does). Return true if consumed. */
    var screenZoom: ((amount: Float, at: Offset) -> Boolean)? = null

    private var lastUsed = "-"
    private val context: String get() = navigator.top.screen.title + "|" + lastUsed

    val seconds: Float get() = timeMs / 1000f

    init {
        navigator.onDiveIn = { sfx(Sfx.DiveIn); if (log != null) event("dive-in", navigator.top.screen.title) }
        navigator.onDiveOut = { sfx(Sfx.DiveOut); if (log != null) event("dive-out", navigator.top.screen.title) }
        listings.onPrefetched = { sfx(Sfx.Notify, gain = 0.45f) }
        targets.onHoverEnter = { sfx(Sfx.Hover, pitch = 0.95f + Random.nextFloat() * 0.1f) }
        scope.launch(Dispatchers.Default) {
            SoundBank.warmUp { path -> com.zain.holoos.res.Res.readBytes(path) }
            startAudio(mixer)
            sfx(Sfx.Boot)
        }
    }

    fun sfx(s: Sfx, pitch: Float = 1f, gain: Float = 1f) { mixer.muted = muted; mixer.play(s, pitch, gain) }

    fun toggleMute() { muted = !muted; mixer.muted = muted }

    fun burst(at: Offset, warm: Boolean = false) = particles.burst(at, warm = warm)

    /** Every tap / dive: score the intent model, then teach the usage prior. */
    fun onTargetUsed(e: com.zain.holoos.input.HoloTargetEntry) {
        intentScore.onTap(timeMs, e.id)
        prior.record(context, e.id)
        lastUsed = e.id
        if (!e.id.startsWith("calc:") && e.onDive == null) sfx(Sfx.Tap)
    }

    private var lastNanos = 0L
    private var fpsFrames = 0
    private var fpsAcc = 0f

    fun frame(nanos: Long) {
        val dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = nanos
        timeMs = nanos / 1_000_000L
        predictor.enabled = predictionOn
        tracker?.let { tr ->
            val wall = com.zain.holoos.geo.epochMillis()
            gestures?.setSource(tr.source)
            gestures?.flingClaimed = screenFling != null
            gestures?.process(tr.latest, wall, size.width, size.height)
            val fid = tr.latest?.id ?: -1
            if (fid != loggedFrame) { loggedFrame = fid; logRow() }
            calibration?.tick(wall, size.width, size.height)
            if (trackerStatus != tr.status) trackerStatus = tr.status
        }
        predictor.tick(timeMs, size.width, size.height)
        targets.tick(hand, dt)
        dwellTick(dt)
        val ctx = context
        val vocab = targets.all.size
        IntentModel.update(targets.all, hand, predictor.direction(), dt) { prior.logProb(ctx, it.id, vocab) }
        val top = targets.top()?.takeIf { it.probability > 0.35f }
        intentScore.sample(timeMs, top?.id)
        // Speculative execution: warm the folder the hand is heading for.
        if (top != null && top.probability > 0.55f) (top.payload as? FsNode)?.let { listings.prefetch(it) }
        particles.tick(dt)
        if (navigator.backZoom > 0f) navigator.backZoom = (navigator.backZoom - dt * 0.9f).coerceAtLeast(0f)
        fpsFrames++; fpsAcc += dt
        if (fpsAcc >= 0.5f) { fps = (fpsFrames / fpsAcc).toInt(); frameMs = fpsAcc * 1000f / fpsFrames; fpsFrames = 0; fpsAcc = 0f }
    }

    /** Positive = zoom in (dive into what the hand is over), negative = zoom out. */
    fun zoomGesture(amount: Float, at: Offset = hand.position) {
        if (navigator.transition != null) return
        if (screenZoom?.invoke(amount, at) == true) return
        val target = targets.at(at.x, at.y)?.takeIf { it.onDive != null }
        if (amount > 0f && target != null) {
            target.zoom += amount
            if (target.zoom >= 1f) { target.zoom = 0f; burst(at, warm = true); target.onDive?.invoke(target.bounds) }
        } else if (amount < 0f) {
            if (target != null && target.zoom > 0f) { target.zoom = (target.zoom + amount).coerceAtLeast(0f); return }
            if (!navigator.canGoBack) return
            navigator.backZoom += -amount
            if (navigator.backZoom >= 1f) navigator.back()
        }
    }
}

val LocalHolo = staticCompositionLocalOf<HoloOsState> { error("HoloOsState not provided") }

@Composable
fun rememberHoloOsState(): HoloOsState {
    val scope = rememberCoroutineScope()
    return remember { HoloOsState(scope) }
}

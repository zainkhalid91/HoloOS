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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

sealed interface Screen { val title: String }
data object HomeScreen : Screen { override val title = "Core" }
data class FilesScreen(val node: FsNode) : Screen { override val title = node.name }
data object CalculatorScreen : Screen { override val title = "Calculator" }
data object GlobeScreen : Screen { override val title = "Globe" }

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

    fun push(screen: Screen, origin: Rect) {
        if (transition != null) return
        val from = top
        val to = NavEntry(nextId++, screen, origin)
        stack.add(to)
        transition = Transition(from, to, true)
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

/** Everything the UI tree needs, provided once through [LocalHolo]. */
class HoloOsState(scope: CoroutineScope) {
    val hand = HandState()
    val predictor = MotionPredictor(hand)
    val targets = TargetRegistry()
    val navigator = Navigator(scope)
    val particles = Particles()
    val calculator = CalculatorEngine()
    val files = com.zain.holoos.fs.platformFileSource()

    var size by mutableStateOf(Size.Zero)
    /** Monotonic animation clock in ms; read it only in draw blocks. */
    var timeMs by mutableLongStateOf(0L)
    var fps by mutableIntStateOf(0)
    var frameMs by mutableFloatStateOf(0f)
    var predictionOn by mutableStateOf(true)

    val seconds: Float get() = timeMs / 1000f

    fun burst(at: Offset, warm: Boolean = false) = particles.burst(at, warm = warm)

    private var lastNanos = 0L
    private var fpsFrames = 0
    private var fpsAcc = 0f

    fun frame(nanos: Long) {
        val dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = nanos
        timeMs = nanos / 1_000_000L
        predictor.enabled = predictionOn
        predictor.tick(timeMs, size.width, size.height)
        targets.tick(hand, dt)
        IntentModel.update(targets.all, hand, predictor.direction())
        particles.tick(dt)
        if (navigator.backZoom > 0f) navigator.backZoom = (navigator.backZoom - dt * 0.9f).coerceAtLeast(0f)
        fpsFrames++; fpsAcc += dt
        if (fpsAcc >= 0.5f) { fps = (fpsFrames / fpsAcc).toInt(); frameMs = fpsAcc * 1000f / fpsFrames; fpsFrames = 0; fpsAcc = 0f }
    }

    /** Positive = zoom in (dive into what the hand is over), negative = zoom out. */
    fun zoomGesture(amount: Float, at: Offset = hand.position) {
        if (navigator.transition != null) return
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

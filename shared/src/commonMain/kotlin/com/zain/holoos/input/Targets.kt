package com.zain.holoos.input

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.zain.holoos.core.LocalHolo

/**
 * Anything the hand can interact with. Its animated values are plain float state
 * read during drawing, so hover / prediction / zoom feedback runs at full frame rate
 * without recomposition.
 */
class HoloTargetEntry(val id: String) {
    var bounds: Rect = Rect.Zero
    var onTap: (() -> Unit)? = null
    /** Non-null when the target can be zoomed into ("dived"). */
    var onDive: ((Rect) -> Unit)? = null

    /** 0..1 smoothed hover amount. */
    var hover by mutableFloatStateOf(0f)
    /** Intent probability from [IntentModel]. */
    var probability by mutableFloatStateOf(0f)
    /** Accumulated log-evidence and scratch score used by [IntentModel]. */
    var logit = 0f
    var score = 0f
    var wasInside = false
    /** Optional domain object (e.g. the folder) so speculative work knows what to warm. */
    var payload: Any? = null
    /** 0..1 accumulated zoom gesture (scroll wheel / pinch) toward a dive. */
    var zoom by mutableFloatStateOf(0f)
    /** 1 right after a tap, decays to 0: drives press pulses. */
    var pulse by mutableFloatStateOf(0f)

    /** Pre-charge shown when the model is confident the hand is heading here. */
    val charge: Float get() = if (hover > 0.5f) 0f else ((probability - 0.4f) / 0.6f).coerceIn(0f, 1f)
}

class TargetRegistry {
    private val entries = LinkedHashMap<String, HoloTargetEntry>()
    val all: Collection<HoloTargetEntry> get() = entries.values

    fun register(e: HoloTargetEntry) { entries[e.id] = e }
    fun unregister(e: HoloTargetEntry) { if (entries[e.id] === e) entries.remove(e.id) }

    fun at(x: Float, y: Float): HoloTargetEntry? =
        entries.values.lastOrNull { it.bounds.contains(androidx.compose.ui.geometry.Offset(x, y)) }

    /** Called when the hand enters a target (used for hover sounds). */
    var onHoverEnter: ((HoloTargetEntry) -> Unit)? = null

    fun top(): HoloTargetEntry? = entries.values.maxByOrNull { it.probability }

    fun tick(hand: HandState, dt: Float) {
        val decay = kotlin.math.exp(-dt * 6f)
        for (e in entries.values) {
            val inside = hand.visible && e.bounds.contains(hand.position)
            val target = if (inside) 1f else 0f
            if (inside && !e.wasInside) onHoverEnter?.invoke(e)
            e.wasInside = inside
            e.hover = target + (e.hover - target) * kotlin.math.exp(-dt * 14f)
            if (e.pulse > 0f) e.pulse = (e.pulse * decay).let { if (it < 0.01f) 0f else it }
            if (e.zoom > 0f) e.zoom = (e.zoom - dt * 0.9f).coerceAtLeast(0f)
        }
    }
}

/**
 * Wraps content as an interactive hologram target. [content] receives the entry so it
 * can draw hover glow, prediction pre-charge and press pulses.
 */
@Composable
fun HoloTarget(
    id: String,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onDive: ((Rect) -> Unit)? = null,
    zoomScale: Float = 0.22f,
    payload: Any? = null,
    content: @Composable (HoloTargetEntry) -> Unit,
) {
    val holo = LocalHolo.current
    val entry = remember(id) { HoloTargetEntry(id) }
    val tap by rememberUpdatedState(onTap)
    val dive by rememberUpdatedState(onDive)
    entry.onTap = { entry.pulse = 1f; holo.burst(holo.hand.position); holo.onTargetUsed(entry); tap?.invoke() }
    entry.payload = payload
    entry.onDive = if (onDive != null) { r -> dive?.invoke(r) } else null

    DisposableEffect(entry) {
        holo.targets.register(entry)
        onDispose { holo.targets.unregister(entry) }
    }

    Box(
        modifier
            .onGloballyPositioned { entry.bounds = it.boundsInRoot() }
            .graphicsLayer {
                val s = 1f + entry.zoom * zoomScale + entry.hover * 0.03f
                scaleX = s; scaleY = s
            }
            .pointerInput(entry) {
                detectTapGestures(onTap = {
                    val d = entry.onDive
                    if (d != null && tap == null) { entry.pulse = 1f; holo.onTargetUsed(entry); d(entry.bounds) } else entry.onTap?.invoke()
                })
            }
    ) { content(entry) }
}

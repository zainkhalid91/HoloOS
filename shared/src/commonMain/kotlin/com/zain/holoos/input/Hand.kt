package com.zain.holoos.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * The single source of "where is the user's hand". Today it is fed by mouse / touch;
 * the camera hand-tracker will feed the exact same fields, so nothing above this
 * layer has to change when we switch input.
 *
 * Fields are snapshot state but are only read in draw / layer blocks, so updating
 * them every frame causes redraws, never recompositions.
 */
class HandState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var predictedX by mutableFloatStateOf(0f)
    var predictedY by mutableFloatStateOf(0f)
    var visible by mutableStateOf(false)
    var pressed by mutableStateOf(false)
    /** px/s, smoothed. */
    var speed by mutableFloatStateOf(0f)

    val position get() = Offset(x, y)
    val predicted get() = Offset(predictedX, predictedY)
}

/**
 * Motion predictor: an exponentially smoothed velocity/acceleration model that
 * extrapolates the hand [leadMs] into the future. This is what cancels camera and
 * render latency — the UI reacts to where the hand is going, not where it was.
 */
class MotionPredictor(private val hand: HandState) {
    var leadMs = 110f
    var enabled = true

    private var lastT = 0L
    private var vx = 0f; private var vy = 0f
    private var ax = 0f; private var ay = 0f

    fun onSample(x: Float, y: Float, uptimeMs: Long) {
        if (!hand.visible || lastT == 0L) {
            hand.x = x; hand.y = y; lastT = uptimeMs; hand.visible = true
            vx = 0f; vy = 0f; ax = 0f; ay = 0f
            return
        }
        val dt = ((uptimeMs - lastT).coerceAtLeast(1L)) / 1000f
        val nvx = (x - hand.x) / dt
        val nvy = (y - hand.y) / dt
        val k = 0.35f
        ax = ax * (1 - k) + ((nvx - vx) / dt) * k
        ay = ay * (1 - k) + ((nvy - vy) / dt) * k
        vx = vx * (1 - k) + nvx * k
        vy = vy * (1 - k) + nvy * k
        hand.x = x; hand.y = y; lastT = uptimeMs
    }

    /** Called once per frame. */
    fun tick(nowMs: Long, width: Float, height: Float) {
        if (nowMs - lastT > 60) { vx *= 0.82f; vy *= 0.82f; ax *= 0.7f; ay *= 0.7f }
        hand.speed = hypot(vx, vy)
        val l = if (enabled) leadMs / 1000f else 0f
        val accTerm = 0.5f * l * l * 0.15f
        hand.predictedX = (hand.x + vx * l + ax * accTerm).coerceIn(0f, width)
        hand.predictedY = (hand.y + vy * l + ay * accTerm).coerceIn(0f, height)
    }

    fun direction(): Float = kotlin.math.atan2(vy, vx)

    fun reset() { lastT = 0L; hand.visible = false }
}

/**
 * Intent model v0: scores every on-screen target by distance to the predicted hand
 * position and by how well it lines up with the direction of travel, then turns the
 * scores into probabilities with a softmax. The smoothed probability drives the
 * amber "pre-charge" on targets — speculative UI before the user arrives.
 *
 * v1 will replace [score] with a small sequence model trained on recorded gestures.
 */
object IntentModel {
    fun update(targets: Collection<HoloTargetEntry>, hand: HandState, direction: Float) {
        if (targets.isEmpty()) return
        if (!hand.visible) { targets.forEach { it.probability *= 0.9f }; return }
        val motion = min(1f, hand.speed / 700f)
        var maxScore = Float.NEGATIVE_INFINITY
        val scores = FloatArray(targets.size)
        targets.forEachIndexed { i, t ->
            val c = t.bounds.center
            val d = hypot(c.x - hand.predictedX, c.y - hand.predictedY)
            val toTarget = kotlin.math.atan2(c.y - hand.y, c.x - hand.x)
            var diff = kotlin.math.abs(toTarget - direction)
            if (diff > kotlin.math.PI) diff = (2 * kotlin.math.PI - diff).toFloat()
            val s = -d / 160f - diff * 2.2f * motion
            scores[i] = s
            if (s > maxScore) maxScore = s
        }
        var sum = 0f
        for (i in scores.indices) { scores[i] = exp((scores[i] - maxScore) * 1.6f); sum += scores[i] }
        targets.forEachIndexed { i, t ->
            t.probability = t.probability * 0.8f + (scores[i] / sum) * 0.2f
        }
    }
}

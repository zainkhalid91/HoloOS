package com.zain.holoos.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
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
    /** Where the hand will be [MotionPredictor.leadMs] from now. */
    var predictedX by mutableFloatStateOf(0f)
    var predictedY by mutableFloatStateOf(0f)
    /** Where the current movement will come to rest (kinematic endpoint prediction). */
    var endpointX by mutableFloatStateOf(0f)
    var endpointY by mutableFloatStateOf(0f)
    var decelerating by mutableStateOf(false)
    var visible by mutableStateOf(false)
    var pressed by mutableStateOf(false)
    /** px/s, filtered. */
    var speed by mutableFloatStateOf(0f)

    val position get() = Offset(x, y)
    val predicted get() = Offset(predictedX, predictedY)
    val endpoint get() = Offset(endpointX, endpointY)
}

/**
 * Constant-acceleration Kalman filter for one axis: state [position, velocity, accel].
 * Optimal smoothing *and* prediction under Gaussian noise — it is what trackers in
 * VR headsets and missiles use, and it gives us velocity/acceleration without the lag
 * of naive averaging.
 */
private class Kalman1D(private val q: Float, private val r: Float) {
    var p = 0f; var v = 0f; var a = 0f
    // Covariance (symmetric 3x3, upper triangle).
    private var p00 = 1e4f; private var p01 = 0f; private var p02 = 0f
    private var p11 = 1e4f; private var p12 = 0f; private var p22 = 1e4f

    fun reset(pos: Float) { p = pos; v = 0f; a = 0f; p00 = 1f; p01 = 0f; p02 = 0f; p11 = 1e4f; p12 = 0f; p22 = 1e4f }

    fun predict(dt: Float) {
        val dt2 = 0.5f * dt * dt
        p += v * dt + a * dt2; v += a * dt
        // P = F P F^T + Q  with F = [[1,dt,dt2],[0,1,dt],[0,0,1]]
        val n00 = p00 + 2 * dt * p01 + 2 * dt2 * p02 + dt * dt * p11 + 2 * dt * dt2 * p12 + dt2 * dt2 * p22
        val n01 = p01 + dt * p02 + dt * p11 + (dt * dt + dt2) * p12 + dt * dt2 * p22
        val n02 = p02 + dt * p12 + dt2 * p22
        val n11 = p11 + 2 * dt * p12 + dt * dt * p22
        val n12 = p12 + dt * p22
        val qd = q * dt
        p00 = n00 + qd * dt2 * dt2; p01 = n01 + qd * dt2 * dt; p02 = n02 + qd * dt2
        p11 = n11 + qd * dt * dt; p12 = n12 + qd * dt; p22 = p22 + qd
    }

    fun update(z: Float) {
        val s = p00 + r
        val k0 = p00 / s; val k1 = p01 / s; val k2 = p02 / s
        val y = z - p
        p += k0 * y; v += k1 * y; a += k2 * y
        val a00 = p00; val a01 = p01; val a02 = p02
        p00 -= k0 * a00; p01 -= k0 * a01; p02 -= k0 * a02
        p11 -= k1 * a01; p12 -= k1 * a02; p22 -= k2 * a02
    }
}

/**
 * Motion layer of the prediction stack.
 *  1. Kalman filter → smoothed position, velocity, acceleration.
 *  2. Lead extrapolation → where the hand will be in [leadMs] (cancels sensor + render lag).
 *  3. Kinematic endpoint prediction (Lank / Ruiz): while the hand decelerates toward a
 *     target, the stopping point is p + v̂·|v|²/(2|a∥|) — the hand tells us where it will
 *     land before it gets there.
 */
class MotionPredictor(private val hand: HandState) {
    var leadMs = 110f
    var enabled = true

    private val kx = Kalman1D(q = 4e7f, r = 2f)
    private val ky = Kalman1D(q = 4e7f, r = 2f)
    private var lastT = 0L
    private var lastSampleT = 0L

    fun onSample(x: Float, y: Float, uptimeMs: Long) {
        if (!hand.visible || lastT == 0L) {
            kx.reset(x); ky.reset(y); lastT = uptimeMs; lastSampleT = uptimeMs
            hand.x = x; hand.y = y; hand.visible = true
            return
        }
        val dt = ((uptimeMs - lastT).coerceAtLeast(1L)) / 1000f
        kx.predict(dt); ky.predict(dt)
        kx.update(x); ky.update(y)
        lastT = uptimeMs; lastSampleT = uptimeMs
        // Raw position for the reticle: zero added latency. The filter feeds prediction.
        hand.x = x; hand.y = y
    }

    /** Called once per frame. */
    fun tick(nowMs: Long, width: Float, height: Float) {
        if (!hand.visible) return
        val idle = nowMs - lastSampleT > 50
        val vx = if (idle) kx.v * 0.6f else kx.v
        val vy = if (idle) ky.v * 0.6f else ky.v
        if (idle) { kx.v *= 0.8f; ky.v *= 0.8f; kx.a *= 0.6f; ky.a *= 0.6f }
        val speed = hypot(vx, vy)
        hand.speed = speed

        val l = if (enabled) leadMs / 1000f else 0f
        val ax = kx.a.coerceIn(-2e4f, 2e4f); val ay = ky.a.coerceIn(-2e4f, 2e4f)
        hand.predictedX = (hand.x + vx * l + 0.5f * ax * l * l * 0.3f).coerceIn(0f, width)
        hand.predictedY = (hand.y + vy * l + 0.5f * ay * l * l * 0.3f).coerceIn(0f, height)

        // Endpoint: deceleration along the direction of travel.
        if (speed > 60f) {
            val ux = vx / speed; val uy = vy / speed
            val aAlong = ax * ux + ay * uy
            if (aAlong < -200f) {
                val d = min(speed * speed / (2f * -aAlong), 900f)
                hand.endpointX = (hand.x + ux * d).coerceIn(0f, width)
                hand.endpointY = (hand.y + uy * d).coerceIn(0f, height)
                hand.decelerating = true
                return
            }
        }
        hand.endpointX = hand.predictedX; hand.endpointY = hand.predictedY
        hand.decelerating = false
    }

    fun direction(): Float = kotlin.math.atan2(ky.v, kx.v)

    fun reset() { lastT = 0L; hand.visible = false }
}

/**
 * Usage prior — the "language model" part. Like an n-gram LM predicts the next token
 * from the previous one, this predicts the next target from the current context (the
 * screen and the last target used), with Laplace smoothing so unseen pairs still get
 * probability mass.
 */
class UsagePrior {
    private val counts = HashMap<String, HashMap<String, Int>>()
    private val totals = HashMap<String, Int>()

    fun record(context: String, target: String) {
        val m = counts.getOrPut(context) { HashMap() }
        m[target] = (m[target] ?: 0) + 1
        totals[context] = (totals[context] ?: 0) + 1
    }

    fun logProb(context: String, target: String, vocab: Int): Float {
        val c = counts[context]?.get(target) ?: 0
        val t = totals[context] ?: 0
        return ln((c + 1f) / (t + max(vocab, 1).toFloat()))
    }
}

/**
 * Intent layer: a recursive Bayesian filter over "which target is the hand going to".
 * Each frame adds a log-likelihood of the observed motion under each target hypothesis
 * (distance from the predicted endpoint, Fitts-scaled by target size, plus heading
 * agreement). Evidence decays so the belief can change its mind, the usage prior is
 * added, and a softmax gives the distribution — the same final step an LLM uses to turn
 * logits into next-token probabilities.
 */
object IntentModel {
    private const val EVIDENCE_HALF_LIFE = 0.12f

    fun update(targets: Collection<HoloTargetEntry>, hand: HandState, direction: Float, dt: Float, prior: (HoloTargetEntry) -> Float) {
        if (targets.isEmpty()) return
        if (!hand.visible) { targets.forEach { it.probability *= 0.9f; it.logit *= 0.9f }; return }
        val keep = exp(-dt * ln(2f) / EVIDENCE_HALF_LIFE)
        val motion = min(1f, hand.speed / 600f)
        val ex = hand.endpointX; val ey = hand.endpointY
        var maxLogit = Float.NEGATIVE_INFINITY
        for (t in targets) {
            val c = t.bounds.center
            // Fitts: tolerance grows with target size.
            val sigma = 70f + 0.35f * min(t.bounds.width, t.bounds.height)
            val d = hypot(c.x - ex, c.y - ey)
            var ll = -(d * d) / (2f * sigma * sigma)
            if (motion > 0.05f) {
                val toTarget = kotlin.math.atan2(c.y - hand.y, c.x - hand.x)
                var diff = abs(toTarget - direction)
                if (diff > kotlin.math.PI) diff = (2 * kotlin.math.PI - diff).toFloat()
                ll -= diff * diff * 1.5f * motion
            }
            t.logit = t.logit * keep + ll * (1f - keep) * 8f
            val total = t.logit + prior(t) * 0.6f
            t.score = total
            if (total > maxLogit) maxLogit = total
        }
        var sum = 0f
        for (t in targets) { t.score = exp(t.score - maxLogit); sum += t.score }
        for (t in targets) t.probability = t.score / sum
    }
}

/**
 * Online evaluation, the way ML models are judged: at every tap we check which target
 * the model ranked first [horizonMs] earlier. Reported live in the HUD.
 */
class IntentScore(private val horizonMs: Long = 150) {
    private val times = LongArray(64)
    private val tops = arrayOfNulls<String>(64)
    private var head = 0
    var hits by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)

    fun sample(nowMs: Long, top: String?) { times[head] = nowMs; tops[head] = top; head = (head + 1) % times.size }

    fun onTap(nowMs: Long, id: String) {
        val want = nowMs - horizonMs
        var best = -1; var bestDiff = Long.MAX_VALUE
        for (i in times.indices) {
            val d = abs(times[i] - want)
            if (times[i] != 0L && d < bestDiff) { bestDiff = d; best = i }
        }
        if (best < 0 || bestDiff > 80) return
        total++
        if (tops[best] == id) hits++
    }

    val accuracy: Float get() = if (total == 0) 0f else hits.toFloat() / total
}

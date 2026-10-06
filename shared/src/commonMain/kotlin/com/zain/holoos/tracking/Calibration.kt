package com.zain.holoos.tracking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Guided calibration:
 *  1. Find the user (shoulders seen by the pose model — the face is never used or shown).
 *  2. Raise an open hand and hold it (confirms hand tracking and lighting).
 *  3. Reach comfortably toward five targets and pinch at each one.
 * The five body-relative hand positions are fitted to the five screen points, so the
 * whole screen is reachable from the user's natural, relaxed arm range.
 */
class CalibrationSession(
    private val engine: GestureEngine,
    private val onSample: () -> Unit,
    private val onDone: () -> Unit,
) {
    enum class Step { NoTracker, FindUser, RaiseHand, Targets, Done }

    val targets = listOf(0.10f to 0.14f, 0.90f to 0.14f, 0.90f to 0.86f, 0.10f to 0.86f, 0.50f to 0.50f)

    var step by mutableStateOf(Step.FindUser); private set
    var index by mutableIntStateOf(0); private set
    var progress by mutableFloatStateOf(0f); private set
    var errorPx by mutableFloatStateOf(0f); private set
    var bodyMode by mutableStateOf(true); private set

    private val samples = ArrayList<FloatArray>()
    private var stepStart = 0L
    private var lastTick = 0L

    fun start(trackerAvailable: Boolean, nowMs: Long) {
        samples.clear(); index = 0; progress = 0f; stepStart = nowMs; lastTick = nowMs
        step = if (trackerAvailable) Step.FindUser else Step.NoTracker
        engine.captureHook = { start, _ -> if (start) capture() }
    }

    fun stop() { engine.captureHook = null }

    fun tick(nowMs: Long, screenW: Float, screenH: Float) {
        val dt = ((nowMs - lastTick) / 1000f).coerceIn(0f, 0.1f); lastTick = nowMs
        when (step) {
            Step.FindUser -> {
                val handsOnly = engine.hands[0].visible && nowMs - stepStart > 5000
                if (engine.bodyVisible || handsOnly) progress += dt / 1.0f else progress = (progress - dt).coerceAtLeast(0f)
                if (progress >= 1f) { bodyMode = engine.bodyVisible; next(Step.RaiseHand, nowMs) }
            }
            Step.RaiseHand -> {
                val ok = engine.hands[0].visible && engine.hands[0].pose == HandPose.Open
                progress = if (ok) progress + dt / 1.0f else (progress - dt * 2).coerceAtLeast(0f)
                if (progress >= 1f) { next(Step.Targets, nowMs); onSample() }
            }
            else -> {}
        }
        this.w = screenW; this.h = screenH
    }

    private var w = 1f; private var h = 1f

    private fun next(s: Step, nowMs: Long) { step = s; progress = 0f; stepStart = nowMs }

    private fun capture() {
        if (step != Step.Targets) return
        if (engine.lastAnchorBodySpace != bodyMode) return
        val (tx, ty) = targets[index]
        samples += floatArrayOf(engine.lastAnchorB[0], engine.lastAnchorB[1], tx, ty)
        onSample()
        index++
        if (index >= targets.size) finish()
    }

    private fun finish() {
        val fit = Calibration.fit(bodyMode, samples)
        if (fit == null) { index = 0; samples.clear(); return }
        engine.applyCalibration(fit.first)
        errorPx = fit.second * kotlin.math.max(w, h)
        step = Step.Done
        onDone()
    }

    fun restart(nowMs: Long) = start(true, nowMs)
}

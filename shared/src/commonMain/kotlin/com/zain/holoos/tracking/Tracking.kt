package com.zain.holoos.tracking

/** One tracked hand: 21 landmarks as x,y,z triples in mirrored image coordinates (0..1). */
class TrackedHand(val isRight: Boolean, val score: Float, val pts: FloatArray) {
    fun x(i: Int) = pts[i * 3]
    fun y(i: Int) = pts[i * 3 + 1]
    fun z(i: Int) = pts[i * 3 + 2]
}

/**
 * One camera frame worth of landmarks. Only coordinates ever reach the app: no pixels,
 * no face. [shoulders] is (leftX, leftY, rightX, rightY) or null when no body was seen.
 */
class TrackFrame(
    val id: Int,
    val captureMs: Double,
    val receivedMs: Long,
    val cameraFps: Float,
    val width: Int,
    val height: Int,
    val shoulders: FloatArray?,
    val hands: List<TrackedHand>,
) {
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 16f / 9f
}

enum class TrackerStatus { Off, NotInstalled, Starting, Running, NoSignal, Failed }

/**
 * Connection to a hand-tracking source. The receiving thread writes [latest]; the UI
 * frame loop reads it. All gesture maths then runs on the CPU in the frame loop, while
 * the GPU only renders.
 */
interface TrackerLink {
    val latest: TrackFrame?
    val status: TrackerStatus
    /** "PHONE" when the phone sensor is streaming, else "WEBCAM". */
    val source: String
    fun stop()
}

/** Desktop: launches tools/tracker/tracker.py and listens on UDP. Android: not yet (null). */
expect fun startTracker(): TrackerLink?

/** Tiny key/value persistence for calibration. */
expect fun loadSetting(key: String): String?
expect fun saveSetting(key: String, value: String)

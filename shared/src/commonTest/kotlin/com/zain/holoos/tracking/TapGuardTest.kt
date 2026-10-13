package com.zain.holoos.tracking

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * False clicks seen in the session log: closing into a fist or opening out of one passes
 * through a pinch shape, and finger landmarks shake at the camera's border.
 */
class TapGuardTest {
    private class Taps : GestureSink {
        var taps = 0
        override fun onCursor(x: Float, y: Float, timeMs: Long) {}
        override fun onPress(pressed: Boolean) {}
        override fun onTap(x: Float, y: Float) { taps++ }
        override fun onDrag(dx: Float, dy: Float) {}
        override fun onGrip(closed: Boolean) {}
        override fun onFling(vx: Float, vy: Float) {}
        override fun onHold() {}
        override fun onSpread(amount: Float, at: Offset) {}
        override fun onReturnIgnored(kind: String) {}
        override fun onZoom(amount: Float, at: Offset) {}
        override fun onBack() {}
        override fun onHome() {}
        override fun onAccept() {}
        override fun onHandsLost() {}
    }

    private enum class P { Open, Pinch, Fist }

    private val aspect = 16f / 9f

    /** A right hand, palm centred at image ([cx], 0.5). Offsets in height units. */
    private fun hand(p: P, cx: Float): TrackedHand {
        val pts = mutableListOf(
            0f to 0.12f,
            -0.04f to 0.09f, -0.07f to 0.06f, -0.10f to 0.03f, -0.13f to 0.0f,
            -0.03f to 0f, -0.035f to -0.05f, -0.04f to -0.08f, -0.045f to -0.11f,
            0f to 0f, 0f to -0.055f, 0f to -0.09f, 0f to -0.12f,
            0.03f to 0.005f, 0.035f to -0.045f, 0.04f to -0.08f, 0.045f to -0.11f,
            0.055f to 0.02f, 0.065f to -0.02f, 0.07f to -0.05f, 0.075f to -0.075f,
        )
        when (p) {
            P.Open -> {}
            P.Pinch -> pts[4] = pts[8]
            P.Fist -> for (mcp in intArrayOf(5, 9, 13, 17)) {
                val (x, y) = pts[mcp]
                pts[mcp + 1] = x to y - 0.03f // pip up
                pts[mcp + 2] = x to y - 0.01f // dip folded back
                pts[mcp + 3] = x to y + 0.02f // tip curled into the palm
            }
        }
        val arr = FloatArray(63)
        pts.forEachIndexed { i, (dx, dy) -> arr[i * 3] = cx + dx / aspect; arr[i * 3 + 1] = 0.5f + dy }
        return TrackedHand(true, 0.95f, arr)
    }

    private fun taps(seq: List<P>, cx: Float = 0.5f): Int {
        val rec = Taps()
        val engine = GestureEngine(rec).apply { phantomFilter = false }
        var t = 1_000_000L
        seq.forEachIndexed { i, p ->
            t += 33
            engine.process(TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(hand(p, cx))), t, 1920f, 1080f)
        }
        return rec.taps
    }

    private fun n(p: P, k: Int) = List(k) { p }

    @Test fun pinchAndReleaseTaps() {
        assertEquals(1, taps(n(P.Open, 15) + n(P.Pinch, 4) + n(P.Open, 10)))
    }

    @Test fun closingIntoAFistDoesNotTap() {
        assertEquals(0, taps(n(P.Open, 15) + n(P.Pinch, 3) + n(P.Fist, 15)))
    }

    @Test fun openingOutOfAFistDoesNotTap() {
        assertEquals(0, taps(n(P.Fist, 15) + n(P.Pinch, 3) + n(P.Open, 15)))
    }

    @Test fun oneFramePinchFlickerDoesNotTap() {
        assertEquals(0, taps(n(P.Open, 15) + n(P.Pinch, 1) + n(P.Open, 10)))
    }

    @Test fun noTapsAtTheCameraEdge() {
        assertEquals(0, taps(n(P.Open, 15) + n(P.Pinch, 4) + n(P.Open, 10), cx = 0.03f))
    }

    /** Pointing hand; the index finger is dipped [dip]° toward the camera at its knuckle. */
    private fun pointHand(dip: Float): TrackedHand {
        val h = hand(P.Fist, 0.5f)
        val arr = h.pts.copyOf()
        val kx = arr[5 * 3]; val ky = arr[5 * 3 + 1]
        val r = dip * kotlin.math.PI.toFloat() / 180f
        for ((k, len) in listOf(6 to 0.05f, 7 to 0.08f, 8 to 0.11f)) {
            arr[k * 3] = kx
            arr[k * 3 + 1] = ky - len * kotlin.math.cos(r)
            arr[k * 3 + 2] = -len * kotlin.math.sin(r) / aspect
        }
        return TrackedHand(true, 0.95f, arr)
    }

    private fun pointTaps(dips: List<Float>): Int {
        val rec = Taps()
        val engine = GestureEngine(rec).apply { phantomFilter = false }
        var t = 1_000_000L
        dips.forEachIndexed { i, d ->
            t += 33
            engine.process(TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(pointHand(d))), t, 1920f, 1080f)
        }
        return rec.taps
    }

    private fun dips(d: Float, k: Int) = List(k) { d }

    @Test fun indexDipAndLiftTaps() {
        assertEquals(1, pointTaps(dips(0f, 15) + dips(20f, 1) + dips(40f, 2) + dips(15f, 1) + dips(0f, 10)))
    }

    @Test fun slowPressHeldHalfASecondTaps() {
        // As in the log: down over 4 frames, held ~0.5 s, back up.
        assertEquals(1, pointTaps(dips(12f, 15) + listOf(18f, 25f, 32f, 48f) + dips(49f, 15) + listOf(30f, 18f, 12f) + dips(10f, 10)))
    }

    @Test fun indexCurledAndHeldDoesNotTap() {
        assertEquals(0, pointTaps(dips(0f, 15) + dips(45f, 45)))
    }

    @Test fun slowIndexDriftDoesNotTap() {
        assertEquals(0, pointTaps(dips(0f, 15) + List(60) { it * 0.5f } + dips(30f, 10)))
    }

    @Test fun aFrozenObjectSeenAsAHandIsIgnored() {
        // Log: a fixed "hand" at the bottom right, never moving, flipping poses.
        val engine = GestureEngine(Taps())
        var t = 1_000_000L
        repeat(60) { i ->
            t += 33
            val p = if (i % 7 < 3) P.Pinch else P.Open
            engine.process(TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(hand(p, 0.65f))), t, 1920f, 1080f)
        }
        assertEquals(false, engine.hands[0].visible, "a hand that never moved must not count")
    }

    @Test fun aRealHandCountsOnceItMoves() {
        val engine = GestureEngine(Taps())
        var t = 1_000_000L
        repeat(20) { i ->
            t += 33
            engine.process(TrackFrame(i, t.toDouble(), t, 30f, 1280, 720, null, listOf(hand(P.Open, 0.3f + i * 0.01f))), t, 1920f, 1080f)
        }
        assertEquals(true, engine.hands[0].visible)
    }
}
